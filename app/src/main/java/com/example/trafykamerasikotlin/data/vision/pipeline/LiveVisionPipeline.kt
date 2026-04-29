package com.example.trafykamerasikotlin.data.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnPlateDetector
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnVehicleDetector
import com.example.trafykamerasikotlin.data.vision.ocr.OnnxPlateOcr
import com.example.trafykamerasikotlin.data.vision.tracker.ByteTracker
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection
import com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram
import com.example.trafykamerasikotlin.data.vision.voting.PlateVoteBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frame-source-agnostic vision pipeline.
 *
 * Owns the whole Chunk 1–5 inference graph (vehicle detector → plate
 * detector → OCR → ByteTracker → vote book) behind a single [submit]
 * entry point. Callers push [Bitmap]s and observe the [scene] flow.
 *
 * Cadence + backpressure: every Nth submitted frame (see [inferenceEveryN])
 * is forwarded to a single-slot, DROP_OLDEST channel consumed by one
 * inference coroutine. If inference is slower than submissions — and on
 * mid-range phones it always will be — newer frames evict older staged
 * ones. "Live > complete," per the spec's §3.
 *
 * Models lazy-load on first inference. A failure in any single stage
 * (model init, detect, OCR) is logged and swallowed — the scene flow keeps
 * emitting whatever reached the end, so the overlay never just freezes.
 */
class LiveVisionPipeline(
    private val context: Context,
    private val useGpu: Boolean = true,
    /** Sampler: only every Nth submitted frame goes to inference. */
    private val inferenceEveryN: Int = 3,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _scene = MutableStateFlow<TrackedScene?>(null)
    val scene: StateFlow<TrackedScene?> = _scene.asStateFlow()

    private val _latency = MutableStateFlow(LatencyHistogram.Snapshot.EMPTY)
    val latency: StateFlow<LatencyHistogram.Snapshot> = _latency.asStateFlow()

    private val _submittedCount = MutableStateFlow(0)
    val submittedCount: StateFlow<Int> = _submittedCount.asStateFlow()

    private val histogram = LatencyHistogram()
    private val sampler  = AtomicInteger(0)
    private val tracker  = ByteTracker()
    private val voteBook = PlateVoteBook()

    private var vehicle: NcnnVehicleDetector? = null
    private var plate:   NcnnPlateDetector?   = null
    private var ocr:     OnnxPlateOcr?        = null

    private val inferenceChannel = Channel<Frame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var inferenceJob: Job? = null

    fun start() {
        if (inferenceJob?.isActive == true) return
        inferenceJob = scope.launch {
            for (frame in inferenceChannel) {
                runCatching { processFrame(frame) }.onFailure {
                    Log.w(TAG, "pipeline failure (continuing)", it)
                }
            }
        }
    }

    /**
     * Hand the pipeline a frame. The bitmap must be [Bitmap.Config.ARGB_8888]
     * and owned by the caller — the pipeline copies internally if it keeps
     * the pixels past the call. Typically called from a video player's
     * per-frame tap.
     *
     * Sampler runs BEFORE the copy: at 1080p ARGB_8888 the bitmap.copy is
     * ~8 MB of allocation per call, and with [inferenceEveryN]=3 we'd be
     * burning two thirds of those copies just to drop them at trySend.
     * Returning early on skipped frames keeps the on-frame thread cheap.
     */
    fun submit(bitmap: Bitmap, timestampNanos: Long = System.nanoTime()) {
        val n = sampler.incrementAndGet()
        _submittedCount.value = n
        if (n % inferenceEveryN != 0) return

        // The caller is about to recycle its bitmap. Copy before we park it
        // in a channel the inference thread reads later. ARGB_8888 is the
        // lingua franca of the Android bitmap APIs we need downstream.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        inferenceChannel.trySend(Frame(bitmap = copy, timestampNanos = timestampNanos))
    }

    fun reset() {
        tracker.reset()
        voteBook.clear()
        sampler.set(0)
        _scene.value = null
        histogram.clear()
        _latency.value = LatencyHistogram.Snapshot.EMPTY
    }

    fun release() {
        // Native ncnn detect/recognize calls don't observe Kotlin cancellation
        // — tearing down a detector mid-call destroys mutexes the call still
        // holds and trips FORTIFY (`pthread_mutex_lock on a destroyed mutex`).
        // Wait for the in-flight inference to drain before releasing native
        // resources. Bounded so a stuck native call can't hang the UI thread.
        runBlocking {
            withTimeoutOrNull(2_000L) {
                inferenceJob?.cancelAndJoin()
            }
        }
        inferenceJob = null
        scope.cancel()
        vehicle?.release(); vehicle = null
        plate?.release();   plate = null
        ocr?.release();     ocr = null
        inferenceChannel.close()
    }

    // ── internals ──────────────────────────────────────────────────────────

    private suspend fun processFrame(frame: Frame) {
        val det = ensureVehicleDetectorOrNull() ?: return
        val t0 = System.nanoTime()
        val vehicles = det.detect(frame)
        val vehicleMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
        histogram.record(vehicleMs)
        _latency.value = histogram.snapshot()

        val tracks: List<TrackedDetection> = tracker.update(vehicles)
        voteBook.prune(tracker.activeTrackIds())

        val plateDet  = ensurePlateDetectorOrNull()
        val ocrEngine = ensureOcrOrNull()

        val plates = if (plateDet != null && tracks.isNotEmpty()) {
            tracks.flatMap { t ->
                val x = t.bbox.left.toInt()
                val y = t.bbox.top.toInt()
                val w = (t.bbox.right  - t.bbox.left).toInt()
                val h = (t.bbox.bottom - t.bbox.top ).toInt()
                val raw = runCatching { plateDet.detectInCrop(frame.bitmap, t.trackId, x, y, w, h) }
                    .getOrElse { emptyList() }
                raw.map { p -> p.copy(parentTrackId = t.trackId) }
            }
        } else emptyList()

        val plated: List<PlateDetection> = if (ocrEngine != null) plates.map { p ->
            val trackId = p.parentTrackId
            val crop = cropBitmap(frame.bitmap, p.bbox) ?: return@map p
            val recog = runCatching { ocrEngine.recognize(crop) }.getOrNull()
            crop.recycle()
            if (trackId != null && recog != null && recog.text.isNotEmpty()) {
                voteBook.record(trackId, recog.text)
            }
            val voted = trackId?.let { voteBook.bestText(it) }
            p.copy(recognition = recog, votedText = voted)
        } else plates

        _scene.value = TrackedScene(
            sourceFrameSize    = Size(frame.bitmap.width, frame.bitmap.height),
            detections         = vehicles,
            timestampNanos     = frame.timestampNanos,
            inferenceLatencyMs = vehicleMs,
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

    private suspend fun ensureVehicleDetectorOrNull(): NcnnVehicleDetector? {
        vehicle?.let { return it }
        val built = NcnnVehicleDetector(
            context = context,
            modelSource = NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE,
            useGpu = useGpu,
        )
        return try { built.initialize(); vehicle = built; built }
        catch (t: Throwable) { Log.w(TAG, "vehicle detector init failed", t); null }
    }

    private suspend fun ensurePlateDetectorOrNull(): NcnnPlateDetector? {
        plate?.let { return it }
        val built = NcnnPlateDetector(context = context, useGpu = useGpu)
        return try { built.initialize(); plate = built; built }
        catch (t: Throwable) { Log.w(TAG, "plate detector init failed", t); null }
    }

    private suspend fun ensureOcrOrNull(): OnnxPlateOcr? {
        ocr?.let { return it }
        val built = OnnxPlateOcr(context = context)
        return try { built.initialize(); ocr = built; built }
        catch (t: Throwable) { Log.w(TAG, "OCR init failed", t); null }
    }

    companion object { private const val TAG = "Trafy.LivePipeline" }
}
