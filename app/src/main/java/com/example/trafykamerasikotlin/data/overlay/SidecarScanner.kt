package com.example.trafykamerasikotlin.data.overlay

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.trafykamerasikotlin.data.video.AviMjpegReader
import com.example.trafykamerasikotlin.data.video.AviMjpegVideoSource
import com.example.trafykamerasikotlin.data.video.MediaCodecVideoSource
import com.example.trafykamerasikotlin.data.video.VideoFrameSource
import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnPlateDetector
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnVehicleDetector
import com.example.trafykamerasikotlin.data.vision.ocr.OnnxPlateOcr
import com.example.trafykamerasikotlin.data.vision.VotedPlateText
import com.example.trafykamerasikotlin.data.vision.tracker.ByteTracker
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection
import com.example.trafykamerasikotlin.data.vision.voting.PlateVoteBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans a freshly-downloaded video file once and writes an overlay sidecar.
 * The sidecar lets the in-app playback overlay render the same AI chrome
 * the live stream shows — **without** the heavy two-pass burn-in
 * [com.example.trafykamerasikotlin.data.video.OfflineVideoProcessor] does.
 *
 * Cadence: matches live (1-in-N sampling, default N=3). At ~10 Hz inference
 * on a mid-range phone, a 60-second clip's scan finishes in roughly 20
 * seconds — vs. the multi-minute MediaCodec re-encode the old pixel-bake
 * needs.
 *
 * Plate-lock UX parity: the OEM-style "plate locked from frame 0" feel that
 * the old Pass 1 produced is preserved via a single-pass + finalise step.
 * Per-frame entries are buffered in memory; once the walk finishes we
 * resolve the per-track *final* voted text from the accumulated
 * [PlateVoteBook] and stamp it back into every entry's plate rows before
 * writing the file. The playback overlay sees a locked plate from the very
 * first frame it renders.
 *
 * Cancellation: cooperative — every loop iteration calls [ensureActive], so
 * the user dismissing the download cancels the scan within one frame's
 * inference time. Detectors release in `finally`.
 *
 * Resource management: the scanner instantiates its OWN detectors so a
 * concurrent live screen (which keeps the `LiveVisionPipeline`'s detectors
 * loaded) doesn't fight us for GPU contexts.
 */
class SidecarScanner(private val context: Context) {

    companion object {
        private const val TAG = "Trafy.SidecarScanner"
        const val DEFAULT_INFERENCE_EVERY_N = 3

        /** Bail out of the scan if we see no plates after this many inference frames. */
        private const val EMPTY_INFERENCE_FRAMES_BEFORE_ABORT_MIN = 30L
        private const val EMPTY_INFERENCE_FRAMES_BEFORE_ABORT_MAX = 120L

        /**
         * Process-wide serialisation gate. Two concurrent [scan] calls would
         * race on NCNN's process-wide detector slots
         * ([com.example.trafykamerasikotlin.data.vision.ncnn.NcnnDetectorSlot])
         * — each scanner constructs its own [NcnnVehicleDetector] /
         * [NcnnPlateDetector] but both load into the SAME native slot
         * (VEHICLE=0, PLATE=1), and the second `initialize()` overwrites the
         * first's native handles. When the first scan then calls `detect()`,
         * it walks a dangling pointer → hard native crash (verified on a
         * real device: F DEBUG tombstone at 19:50:08 after a sibling scan
         * completed mid-flight).
         *
         * Holding this Mutex across the whole scan keeps NCNN-slot ownership
         * single-threaded. Subsequent downloads queue here while the active
         * one finishes; cooperative cancellation still works because
         * [Mutex.withLock] releases the gate when a cancelled body unwinds.
         */
        private val scanLock = Mutex()
    }

    sealed class State {
        data object Idle : State()
        data object WarmingUp : State()
        data class Scanning(
            /** 0..1 — fraction of decoded frames walked so far. */
            val fractionDone: Float,
            val framesScanned: Long,
            val totalFrames: Long,
        ) : State()
        data class Done(val entryCount: Int, val sidecarFile: File) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Scan [inputVideo] and write the sidecar for [videoBasename]. Returns
     * the sidecar file on success, null if no useful sidecar was produced
     * (e.g. zero plates ever seen — we still write the entries so the
     * overlay can draw vehicle slabs without plates).
     */
    suspend fun scan(
        inputVideo: File,
        videoBasename: String,
        inferenceEveryN: Int = DEFAULT_INFERENCE_EVERY_N,
    ): File? = withContext(Dispatchers.Default) {
        // Serialise across all concurrent scans in this process so
        // detector-slot races (see [scanLock] docs) can't crash the
        // pipeline. The `isLocked` read is racy but harmless — it only
        // decides whether to log "waiting", not whether to wait.
        if (scanLock.isLocked) {
            Log.i(TAG, "another scan is in progress — queueing ${inputVideo.name}")
        }
        scanLock.withLock {
            doScan(inputVideo, videoBasename, inferenceEveryN)
        }
    }

    private suspend fun doScan(
        inputVideo: File,
        videoBasename: String,
        inferenceEveryN: Int,
    ): File? {
        _state.value = State.WarmingUp
        var vehicleDet: NcnnVehicleDetector? = null
        var plateDet:   NcnnPlateDetector?   = null
        var ocr:        OnnxPlateOcr?        = null
        try {
            // Open whichever source matches the container. Order matters:
            // AVI/MJPEG is what GeneralPlus delivers and what neither
            // MediaCodec nor MediaMetadataRetriever handles cleanly, so
            // probe extension first.
            val source: VideoFrameSource = openSourceFor(inputVideo)
                ?: throw IllegalStateException("no readable video source for ${inputVideo.name}")

            vehicleDet = NcnnVehicleDetector(context, NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE, useGpu = true)
                .also { it.initialize() }
            plateDet   = NcnnPlateDetector(context, useGpu = true).also { it.initialize() }
            ocr        = OnnxPlateOcr(context).also { it.initialize() }
            Log.i(TAG, "detectors ready — beginning scan of ${inputVideo.name}")

            val tracker  = ByteTracker()
            val voteBook = PlateVoteBook()
            val entries  = ArrayList<SidecarEntry>(512)

            // Per-track snapshot of the strongest voted plate text seen
            // anywhere in the scan. We MUST snapshot per-frame here rather
            // than walk `voteBook.bestText()` at the end: the live pipeline
            // contract calls `voteBook.prune(activeTrackIds)` every
            // inference frame (see runInference below), which IRREVERSIBLY
            // erases vote histograms for tracks that leave the frame.
            // Without this snapshot, every car that drives out of view
            // mid-scan loses its locked plate — which would render the
            // 'plate locked from frame 0' headline UX inoperable on any
            // moving-traffic clip.
            val finalVoted = HashMap<Int, VotedPlateText>(8)

            val totalFrames = source.totalFrames.takeIf { it > 0 } ?: -1L
            val abortAfterEmpty = (totalFrames.coerceAtLeast(150L) / inferenceEveryN / 3)
                .coerceIn(EMPTY_INFERENCE_FRAMES_BEFORE_ABORT_MIN, EMPTY_INFERENCE_FRAMES_BEFORE_ABORT_MAX)
            // O(1) flag — we used to walk `entries.none { ... }` per frame,
            // which is O(N²) for parked-car clips and erodes the scan-time
            // budget the new pipeline exists to honor.
            var hasSeenAnyDetection = false
            var emptyInferences = 0L

            var scanned = 0L
            source.use { src ->
                for (frame in src.frames()) {
                    currentCoroutineContext().ensureActive()
                    val argb = frame.bitmap
                    val runInference = (frame.frameIndex % inferenceEveryN == 0L)
                    if (runInference) {
                        val (tracks, plates) = runInference(
                            argb, frame.presentationTimeUs * 1_000L,
                            tracker, voteBook, vehicleDet, plateDet, ocr,
                            finalVotedOut = finalVoted,
                        )

                        val seenAnyDetection = tracks.isNotEmpty() || plates.isNotEmpty()
                        if (seenAnyDetection) {
                            hasSeenAnyDetection = true
                            emptyInferences = 0L
                            entries += buildEntry(
                                ptsUs        = frame.presentationTimeUs,
                                sourceWidth  = src.width,
                                sourceHeight = src.height,
                                tracks       = tracks,
                                plates       = plates,
                            )
                        } else {
                            emptyInferences++
                        }

                        // Bail out when we've burned the budget on empty
                        // frames AND haven't seen ANY road agents (vehicles
                        // OR plates). Earlier code aborted on plate-free
                        // inferences alone, which killed the entire vehicle
                        // overlay on plate-shy clips.
                        if (!hasSeenAnyDetection && emptyInferences >= abortAfterEmpty) {
                            Log.i(TAG, "early-abort after $emptyInferences detection-free inferences")
                            argb.recycle()
                            break
                        }
                    }
                    argb.recycle()
                    scanned++

                    if (scanned % 4L == 0L) {
                        val frac = if (totalFrames > 0) (scanned.toFloat() / totalFrames).coerceIn(0f, 1f) else 0f
                        _state.value = State.Scanning(frac, scanned, totalFrames)
                    }
                }
            }

            // Finalise plate text from the per-frame snapshot. The snapshot
            // wins over a post-prune `voteBook.bestText()` walk because votes
            // for departed tracks have already been pruned.
            val finalised: List<SidecarEntry> = entries.map { e ->
                if (e.plates.isEmpty()) e
                else e.copy(plates = e.plates.map { p ->
                    val final = finalVoted[p.parentTrackId]
                    if (final != null) p.copy(
                        text      = final.text.takeIf { it.isNotEmpty() },
                        votes     = final.votes,
                        agreement = final.agreement,
                    ) else p
                })
            }

            val outFile = OverlaySidecarStore.sidecarFileFor(context, videoBasename)
            outFile.parentFile?.mkdirs()
            OverlaySidecarStore.Writer(outFile).use { w ->
                finalised.forEach { w.append(it) }
            }
            Log.i(TAG, "wrote ${finalised.size} entries → ${outFile.name} (${finalVoted.size} tracks locked)")
            _state.value = State.Done(finalised.size, outFile)
            return outFile
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed: ${t.message}", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            return null
        } finally {
            runCatching { vehicleDet?.release() }
            runCatching { plateDet?.release() }
            runCatching { ocr?.release() }
        }
    }

    /**
     * One inference frame — vehicle → tracker → plate per track → OCR per
     * plate → vote. Returns the tracks + plate detections the scanner
     * should persist (NB: confidence + recognition fields are stripped at
     * serialise time, so we keep the in-memory shape simple).
     */
    private suspend fun runInference(
        bitmap: Bitmap,
        timestampNanos: Long,
        tracker: ByteTracker,
        voteBook: PlateVoteBook,
        vehicleDet: NcnnVehicleDetector,
        plateDet:   NcnnPlateDetector,
        ocr:        OnnxPlateOcr,
        /**
         * Mutated in place — the strongest current voted text per
         * trackId is captured into this map at end-of-frame so the
         * post-scan finalisation step doesn't rely on `voteBook.bestText`
         * (whose state is wiped by `voteBook.prune` once a track exits).
         */
        finalVotedOut: HashMap<Int, VotedPlateText>,
    ): Pair<List<TrackedDetection>, List<PlateDetection>> {
        val frame = Frame(bitmap = bitmap, timestampNanos = timestampNanos)
        val vehicles: List<Detection> = vehicleDet.detect(frame)
        val tracks: List<TrackedDetection> = tracker.update(vehicles)

        val plates: List<PlateDetection> = if (tracks.isNotEmpty()) {
            tracks.flatMap { t ->
                val x = t.bbox.left.toInt()
                val y = t.bbox.top.toInt()
                val w = (t.bbox.right  - t.bbox.left).toInt()
                val h = (t.bbox.bottom - t.bbox.top ).toInt()
                val raw = runCatching { plateDet.detectInCrop(bitmap, t.trackId, x, y, w, h) }
                    .getOrElse { emptyList() }
                raw.map { p -> p.copy(parentTrackId = t.trackId) }
            }
        } else emptyList()

        // OCR per plate (votes accumulate in voteBook for the snapshot below).
        val plated: List<PlateDetection> = plates.map { p ->
            val trackId = p.parentTrackId
            val crop = cropBitmap(bitmap, p.bbox) ?: return@map p
            val recog = runCatching { ocr.recognize(crop) }.getOrNull()
            crop.recycle()
            if (trackId != null && recog != null && recog.text.isNotEmpty()) {
                voteBook.record(trackId, recog.text)
            }
            p.copy(recognition = recog)
        }

        // Snapshot the CURRENT best text for every track in view BEFORE
        // pruning takes them away. More votes accumulate over time, so the
        // last write for any track is always the strongest — direct put
        // (no "highest votes wins" merge) is correct.
        tracks.forEach { t ->
            voteBook.bestText(t.trackId)?.let { finalVotedOut[t.trackId] = it }
        }

        // Now prune. Order matters: snapshot before this line, never after.
        voteBook.prune(tracker.activeTrackIds())

        return tracks to plated
    }

    private fun buildEntry(
        ptsUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        tracks: List<TrackedDetection>,
        plates: List<PlateDetection>,
    ): SidecarEntry {
        val trackRows = tracks.map { t ->
            SidecarEntry.TrackRow(
                trackId = t.trackId,
                cls     = t.cls,
                bbox    = android.graphics.RectF(t.bbox),
            )
        }
        val plateRows = plates.mapNotNull { p ->
            val pid = p.parentTrackId ?: return@mapNotNull null
            SidecarEntry.PlateRow(
                parentTrackId = pid,
                bbox          = android.graphics.RectF(p.bbox),
                text          = null,  // finalised post-scan
                votes         = 0,
                agreement     = 0f,
            )
        }
        return SidecarEntry(
            presentationTimeUs = ptsUs,
            sourceWidth        = sourceWidth,
            sourceHeight       = sourceHeight,
            tracks             = trackRows,
            plates             = plateRows,
        )
    }

    private fun cropBitmap(src: Bitmap, bbox: android.graphics.RectF): Bitmap? {
        val x = bbox.left.toInt().coerceIn(0, src.width - 1)
        val y = bbox.top.toInt().coerceIn(0, src.height - 1)
        val w = (bbox.right  - bbox.left).toInt().coerceAtLeast(1).coerceAtMost(src.width - x)
        val h = (bbox.bottom - bbox.top ).toInt().coerceAtLeast(1).coerceAtMost(src.height - y)
        if (w < 4 || h < 4) return null
        // VideoFrameSource always yields ARGB_8888 today; the converted-copy
        // branch is dead but kept defensively. Recycle the intermediate so
        // we don't leak ~8 MB per crop if the assumption breaks.
        val needsConvert = src.config != Bitmap.Config.ARGB_8888
        val converted = if (needsConvert) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val crop = Bitmap.createBitmap(converted, x, y, w, h)
        if (needsConvert) converted.recycle()
        return crop
    }

    /**
     * AVI/MJPEG (GP) → AviMjpegVideoSource; everything else → MediaCodec
     * source. Matches the routing in `MediaViewModel.downloadWithOverlay`.
     */
    private fun openSourceFor(file: File): VideoFrameSource? = when {
        file.name.endsWith(".avi", ignoreCase = true) -> {
            try { AviMjpegVideoSource(AviMjpegReader(file)) }
            catch (t: Throwable) { Log.w(TAG, "AVI open failed: ${t.message}"); null }
        }
        else -> {
            try { MediaCodecVideoSource.open(file) }
            catch (t: Throwable) { Log.w(TAG, "MediaCodec open failed: ${t.message}"); null }
        }
    }
}
