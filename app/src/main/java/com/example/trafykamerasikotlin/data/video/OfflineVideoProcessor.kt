package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnPlateDetector
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnVehicleDetector
import com.example.trafykamerasikotlin.data.vision.ocr.OnnxPlateOcr
import com.example.trafykamerasikotlin.data.vision.tracker.ByteTracker
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection
import com.example.trafykamerasikotlin.data.vision.voting.PlateVoteBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Decode an AVI → run AI on every frame → encode an MP4 with the overlay
 * burned into every pixel.
 *
 * Unlike [com.example.trafykamerasikotlin.data.vision.pipeline.LiveVisionPipeline]
 * this runs **synchronously**: each frame is detected, tracked, OCR'd and
 * voted BEFORE it's handed to the encoder. That's the right trade-off
 * for offline processing — we have all the wall-clock time we need, and
 * in exchange every output frame carries the AI output for exactly that
 * frame (no "the scene is still for the previous frame because inference
 * is 3 frames behind" ambiguity the live path has).
 *
 * Models are pre-warmed before the encode loop starts, so short clips
 * (a few seconds) don't miss the overlay entirely waiting for model load.
 */
class OfflineVideoProcessor(
    private val context: Context,
    /** Run inference on every Nth frame. 1 = every frame (max quality). */
    private val inferenceEveryN: Int = 3,
    /** OCR on every Nth _plate_ detection (plates don't move much frame-to-frame). */
    private val ocrEveryN: Int = 1,
) {

    sealed class State {
        object Idle : State()
        object WarmingUp : State()
        /**
         * `phase` exposes which of the two passes is running. `fractionDone`
         * spans both passes — 0..0.5 is scan, 0.5..1.0 is encode. Callers
         * that only display a single bar can use it directly; callers that
         * want a label can read [phase].
         */
        data class Processing(
            val fractionDone: Float,
            val frameIndex:   Long,
            val totalFrames:  Long,
            val phase:        Phase = Phase.ENCODE,
        ) : State()
        data class Done(val outputFile: File, val frameCount: Long) : State()
        data class Failed(val message: String) : State()

        enum class Phase { SCAN, ENCODE }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Legacy entry point: wraps the input AVI in an [AviMjpegVideoSource]
     * and forwards to [process]. Kept so `MediaViewModel.downloadWithOverlay`
     * doesn't need to know about the source abstraction.
     */
    suspend fun process(
        inputAvi: File,
        outputMp4: File,
        clipStartEpochMs: Long? = null,
    ) {
        val reader = AviMjpegReader(inputAvi)
        process(AviMjpegVideoSource(reader), outputMp4, clipStartEpochMs)
    }

    /**
     * Generic entry point. [source] supplies decoded bitmaps + timing info;
     * we run inference on each frame and re-encode with the overlay burned
     * into every pixel.
     *
     * When [clipStartEpochMs] is provided and the user has opted into GPS
     * logging, the processor loads the phone GPS log for the clip's window
     * and hands it to the encoder so HUD widgets show real values. When
     * the toggle is off or the log has no coverage, the encoder falls back
     * to `HudEgoEstimator`'s synthetic numbers.
     *
     * Blocks the calling coroutine — run on Dispatchers.Default or similar.
     * [source] is closed on exit regardless of outcome.
     */
    suspend fun process(
        source: VideoFrameSource,
        outputMp4: File,
        clipStartEpochMs: Long? = null,
    ) {
        _state.value = State.WarmingUp
        var encoder: OverlayVideoEncoder? = null
        var vehicle: NcnnVehicleDetector? = null
        var plate:   NcnnPlateDetector? = null
        var ocr:     OnnxPlateOcr? = null

        try {
            val srcW = source.width.takeIf { it > 0 } ?: 1920
            val srcH = source.height.takeIf { it > 0 } ?: 1080
            val fps  = if (source.microsPerFrame > 0) (1_000_000L / source.microsPerFrame).toInt() else 30
            val (outW, outH) = scaleToMaxWidth(srcW, srcH, maxWidth = 1280)

            // Pre-warm detectors BEFORE we open the encoder. On a cold start
            // the vehicle model takes ~5 s on Vulkan — if we let that fire
            // inside the encode loop, short videos finish first and we
            // produce an overlay-less output.
            vehicle = NcnnVehicleDetector(context, NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE, useGpu = true)
            vehicle.initialize()
            plate   = NcnnPlateDetector(context, useGpu = true)
            plate.initialize()
            ocr     = OnnxPlateOcr(context)
            ocr.initialize()
            Log.i(TAG, "detectors warm: vehicle + plate + OCR ready")

            Log.i(TAG, "processing → ${outputMp4.name}  src=${srcW}x${srcH} out=${outW}x${outH}@${fps}fps totalFrames=${source.totalFrames}")

            val sourceSize = Size(srcW, srcH)
            val totalFrames = source.totalFrames.takeIf { it > 0 } ?: -1L

            // ── Pass 1: scan for the best plate crop per vehicle track ──
            // We iterate the entire video once just to find the largest
            // OCR-corroborated plate sample per track id. ByteTracker's id
            // assignment is deterministic on the same input, so the ids we
            // produce here will match the ones produced in pass 2 and the
            // crops will pair up. We pay one extra decode + AI run; in
            // exchange the burned-in video shows the SHARPEST sample of
            // each plate from frame 1 (no need to wait for the car to come
            // close before the plate becomes readable).
            Log.i(TAG, "pass 1: scanning for best plate samples")
            val bestPlates = scanForBestPlates(
                source     = source,
                sourceSize = sourceSize,
                vehicle    = vehicle,
                plate      = plate,
                ocr        = ocr,
                totalFrames = totalFrames,
            )
            Log.i(TAG, "pass 1 done: locked ${bestPlates.size} plates")

            // ── Load the phone GPS log for the clip window ──
            // Phone is the single source of truth: every Trafy chipset we
            // explored either lacks a per-clip GPS retrieval path or reboots
            // on the wire-level probe. When the pref is off or the log has
            // no rows for this window, the encoder falls back to the
            // synthetic estimator (current behavior, no regression).
            val resolvedTrack: com.example.trafykamerasikotlin.data.sensors.GpsTrack? =
                if (clipStartEpochMs == null ||
                    !com.example.trafykamerasikotlin.data.settings.GpsLoggingPreferences.get(context)
                ) null
                else {
                    val durMs = if (totalFrames > 0)
                        totalFrames * source.microsPerFrame.coerceAtLeast(33_333L) / 1_000L
                    else 0L
                    com.example.trafykamerasikotlin.data.sensors.GpsLogStore(context)
                        .load(clipStartEpochMs, clipStartEpochMs + durMs)
                }

            // ── Pass 2: encode, with pass 1's crops + GPS track handed in ──
            encoder = OverlayVideoEncoder(
                outputFile        = outputMp4,
                width             = outW,
                height            = outH,
                frameRate         = fps.coerceAtLeast(15).coerceAtMost(60),
                initialPlateCrops = bestPlates,
                gpsTrack          = resolvedTrack,
                clipStartEpochMs  = clipStartEpochMs ?: 0L,
            )

            val tracker  = ByteTracker()
            val voteBook = PlateVoteBook()
            var lastScene: TrackedScene? = null

            var encoded = 0L
            for (frame in source.frames()) {
                val argb = frame.bitmap
                val runInference = (frame.frameIndex % inferenceEveryN == 0L)
                if (runInference) {
                    val scene = runSyncInference(
                        frameBitmap = argb,
                        frameSource = sourceSize,
                        tracker     = tracker,
                        voteBook    = voteBook,
                        vehicle     = vehicle,
                        plate       = plate,
                        ocr         = ocr,
                        timestampNs = frame.presentationTimeUs * 1000L,
                    )
                    lastScene = scene
                }

                encoder.encodeFrame(argb, lastScene, frame.presentationTimeUs)
                argb.recycle()
                encoded++

                if (encoded % 4L == 0L) {
                    val encodeFraction = if (totalFrames > 0) (encoded.toFloat() / totalFrames).coerceIn(0f, 1f) else 0f
                    val overall = 0.5f + 0.5f * encodeFraction
                    _state.value = State.Processing(overall, encoded, totalFrames, State.Phase.ENCODE)
                }
            }

            encoder.finish()
            encoder = null
            source.close()
            _state.value = State.Done(outputMp4, encoded)
            Log.i(TAG, "done: ${encoded} frames encoded to ${outputMp4.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "process failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            try { outputMp4.delete() } catch (_: Throwable) {}
        } finally {
            try { encoder?.finish() } catch (_: Throwable) {}
            try { source.close()    } catch (_: Throwable) {}
            try { vehicle?.release() } catch (_: Throwable) {}
            try { plate?.release()   } catch (_: Throwable) {}
            try { ocr?.release()     } catch (_: Throwable) {}
        }
    }

    /**
     * Pass 1: iterate the entire video and pick the largest plate crop per
     * track id. "Largest" is a good proxy for "sharpest" — at the offline
     * stage we have no real-time deadline, so spending one extra decode is
     * fine, and the resulting per-track best sample is dramatically more
     * legible than whatever happened to be visible at lock-on-the-fly time.
     *
     * We DON'T persist the per-frame [TrackedScene]s — pass 2 re-runs the
     * tracker from scratch on the same video, which (with deterministic
     * detectors + same input order) produces matching track ids.
     */
    private suspend fun scanForBestPlates(
        source: VideoFrameSource,
        sourceSize: Size,
        vehicle: NcnnVehicleDetector,
        plate:   NcnnPlateDetector,
        ocr:     OnnxPlateOcr,
        totalFrames: Long,
    ): Map<Int, Bitmap> {
        val tracker  = ByteTracker()
        val voteBook = PlateVoteBook()
        val bestArea = HashMap<Int, Float>()
        val bestCrop = HashMap<Int, Bitmap>()

        // Early-abort guard. Many clips have no vehicles at all (parked,
        // empty road, indoor). Burning ~16 minutes of AI inference on a
        // 60-second clip just to confirm "0 plates" is awful UX. If we
        // haven't seen a single plate detection after this many
        // inference-eligible frames, bail and let pass 2 do its work
        // with the no-prelocked-crops state — pass 2's per-frame lock-
        // on-confidence logic still produces a billboard if plates show
        // up later.
        val abortAfterEmptyInferences = (totalFrames.coerceAtLeast(150L) / inferenceEveryN / 3)
            .coerceIn(30L, 120L)
        var inferencesSinceLastPlate = 0L

        var scanned = 0L
        for (frame in source.frames()) {
            val argb = frame.bitmap
            val runInference = (frame.frameIndex % inferenceEveryN == 0L)
            if (runInference) {
                val scene = runSyncInference(
                    frameBitmap = argb,
                    frameSource = sourceSize,
                    tracker     = tracker,
                    voteBook    = voteBook,
                    vehicle     = vehicle,
                    plate       = plate,
                    ocr         = ocr,
                    timestampNs = frame.presentationTimeUs * 1000L,
                )
                val seenAnyPlate = !scene.plates.isNullOrEmpty()
                inferencesSinceLastPlate = if (seenAnyPlate) 0L else (inferencesSinceLastPlate + 1L)

                scene.plates?.forEach { p ->
                    val trackId = p.parentTrackId ?: return@forEach
                    val area = p.bbox.width() * p.bbox.height()
                    if (area < MIN_PLATE_AREA_FOR_LOCK) return@forEach
                    val prev = bestArea[trackId] ?: 0f
                    if (area > prev) {
                        val crop = cropBitmap(argb, p.bbox) ?: return@forEach
                        bestCrop[trackId]?.recycle()
                        bestCrop[trackId] = crop
                        bestArea[trackId] = area
                    }
                }

                if (bestCrop.isEmpty() && inferencesSinceLastPlate >= abortAfterEmptyInferences) {
                    Log.i(TAG, "pass 1 early-abort: no plates seen in ${inferencesSinceLastPlate} inferences — letting pass 2 handle billboards on-the-fly")
                    argb.recycle()
                    break
                }
            }
            argb.recycle()
            scanned++

            if (scanned % 4L == 0L) {
                val scanFraction = if (totalFrames > 0) (scanned.toFloat() / totalFrames).coerceIn(0f, 1f) else 0f
                val overall = 0.5f * scanFraction
                _state.value = State.Processing(overall, scanned, totalFrames, State.Phase.SCAN)
            }
        }
        return bestCrop
    }

    /**
     * Synchronous run of vehicle → plate → OCR → tracker → voting on one
     * frame. Returns the [TrackedScene] snapshot to pass to the encoder.
     * Much simpler than the live pipeline — we don't need channels or
     * sampling because there's no real-time deadline to miss.
     */
    private suspend fun runSyncInference(
        frameBitmap: Bitmap,
        frameSource: Size,
        tracker: ByteTracker,
        voteBook: PlateVoteBook,
        vehicle: NcnnVehicleDetector,
        plate:   NcnnPlateDetector,
        ocr:     OnnxPlateOcr,
        timestampNs: Long,
    ): TrackedScene {
        val frame = Frame(bitmap = frameBitmap, timestampNanos = timestampNs)
        val vehicles: List<Detection> = vehicle.detect(frame)
        val tracks: List<TrackedDetection> = tracker.update(vehicles)
        voteBook.prune(tracker.activeTrackIds())

        val plates: List<PlateDetection> = if (tracks.isNotEmpty()) {
            tracks.flatMap { t ->
                val x = t.bbox.left.toInt()
                val y = t.bbox.top.toInt()
                val w = (t.bbox.right  - t.bbox.left).toInt()
                val h = (t.bbox.bottom - t.bbox.top ).toInt()
                val raw = runCatching { plate.detectInCrop(frameBitmap, t.trackId, x, y, w, h) }
                    .getOrElse { emptyList() }
                raw.map { p -> p.copy(parentTrackId = t.trackId) }
            }
        } else emptyList()

        val plated: List<PlateDetection> = plates.map { p ->
            val trackId = p.parentTrackId
            val crop = cropBitmap(frameBitmap, p.bbox) ?: return@map p
            val recog = runCatching { ocr.recognize(crop) }.getOrNull()
            crop.recycle()
            if (trackId != null && recog != null && recog.text.isNotEmpty()) {
                voteBook.record(trackId, recog.text)
            }
            val voted = trackId?.let { voteBook.bestText(it) }
            p.copy(recognition = recog, votedText = voted)
        }

        return TrackedScene(
            sourceFrameSize    = frameSource,
            detections         = vehicles,
            timestampNanos     = timestampNs,
            inferenceLatencyMs = 0,  // not tracked in offline mode
            plates             = plated,
            tracks             = tracks,
        )
    }

    private fun cropBitmap(src: Bitmap, bbox: RectF): Bitmap? {
        val x = bbox.left.toInt().coerceIn(0, src.width - 1)
        val y = bbox.top.toInt().coerceIn(0, src.height - 1)
        val w = (bbox.right  - bbox.left).toInt().coerceAtLeast(1).coerceAtMost(src.width - x)
        val h = (bbox.bottom - bbox.top ).toInt().coerceAtLeast(1).coerceAtMost(src.height - y)
        if (w < 4 || h < 4) return null
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src
            else src.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createBitmap(argb, x, y, w, h)
    }

    private fun scaleToMaxWidth(srcW: Int, srcH: Int, maxWidth: Int): Pair<Int, Int> {
        if (srcW <= maxWidth) {
            val w = if (srcW % 2 == 0) srcW else srcW - 1
            val h = if (srcH % 2 == 0) srcH else srcH - 1
            return w to h
        }
        val scale = maxWidth.toFloat() / srcW
        val w = maxWidth
        val rawH = (srcH * scale).toInt()
        val h = if (rawH % 2 == 0) rawH else rawH - 1
        return w to h
    }

    companion object {
        private const val TAG = "Trafy.OfflineVideo"

        /**
         * Below this source-pixel area we don't bother locking a crop —
         * the resulting billboard would be a blur regardless. Tuned to
         * roughly match the size at which our OCR starts being reliable.
         */
        private const val MIN_PLATE_AREA_FOR_LOCK = 20f * 8f
    }
}
