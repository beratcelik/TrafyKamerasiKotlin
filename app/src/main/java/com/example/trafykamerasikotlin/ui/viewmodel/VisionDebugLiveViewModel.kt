package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnPlateDetector
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnVehicleDetector
import com.example.trafykamerasikotlin.data.vision.frame.MjpegFrameSource
import com.example.trafykamerasikotlin.data.vision.ncnn.LibLoadState
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnBridge
import com.example.trafykamerasikotlin.data.vision.ocr.OnnxPlateOcr
import com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-stream variant of VisionDebugViewModel — pulls frames from a
 * [MjpegFrameSource] and runs the same detection + OCR pipeline Chunk 1–3
 * built for picked MP4s.
 *
 * Threading:
 *  - The MJPEG player runs its receive thread inside [MjpegFrameSource].
 *  - A frame-collector coroutine on Dispatchers.Default collects every
 *    emitted frame, increments a sampler counter, and submits every N-th
 *    frame (N=3 per Chunk 4 plan) into a single-slot channel for inference.
 *  - A separate inference coroutine consumes from that channel serially,
 *    so NCNN + ONNX sessions never see concurrent calls.
 *
 * Drop policy: channel capacity 1 with DROP_OLDEST — if inference is
 * slower than the sampler rate, newer frames overwrite staged ones. This
 * matches the spec's "live > complete" backpressure rule.
 */
class VisionDebugLiveViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _scene = MutableStateFlow<TrackedScene?>(null)
    val scene: StateFlow<TrackedScene?> = _scene.asStateFlow()

    private val _sourceFrameSize = MutableStateFlow<Size?>(null)
    val sourceFrameSize: StateFlow<Size?> = _sourceFrameSize.asStateFlow()

    private val _latency = MutableStateFlow(LatencyHistogram.Snapshot.EMPTY)
    val latency: StateFlow<LatencyHistogram.Snapshot> = _latency.asStateFlow()

    private val _frameCounter = MutableStateFlow(0)
    val frameCounter: StateFlow<Int> = _frameCounter.asStateFlow()

    private val histogram = LatencyHistogram()
    private val frameIdx = AtomicInteger(0)

    private var pendingUrl: String? = null
    private var pendingNetwork: Network? = null
    private var surface: Surface? = null
    private var source: MjpegFrameSource? = null
    private var collectorJob: Job? = null
    private var inferenceJob: Job? = null

    private val inferenceChannel = Channel<Frame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var vehicle: NcnnVehicleDetector? = null
    private var plate:   NcnnPlateDetector?  = null
    private var ocr:     OnnxPlateOcr?       = null

    /** Called by the SurfaceView's SurfaceHolder.Callback when the surface becomes available. */
    fun setSurface(s: Surface) {
        Log.d(TAG, "setSurface: surface=$s")
        surface = s
        maybeStart()
    }

    fun clearSurface() {
        Log.d(TAG, "clearSurface")
        surface = null
        stopStream()
    }

    /**
     * User hits "Connect": remember the URL + the dashcam Wi-Fi [Network] and
     * start if the surface is ready. The network MUST be the one that owns
     * 192.168.25.1 (or whatever the dashcam's LAN address is) — the phone is
     * usually multi-homed (dashcam Wi-Fi + cellular at the same time), and
     * sockets default to routing via cellular, which can't reach the camera.
     */
    fun connect(rtspUrl: String, network: Network?) {
        Log.i(TAG, "connect: url=$rtspUrl network=$network surface=$surface")
        pendingUrl = rtspUrl
        pendingNetwork = network
        _state.value = LiveState.Connecting
        maybeStart()
    }

    fun disconnect() {
        stopStream()
        pendingUrl = null
        _state.value = LiveState.Idle
    }

    private fun maybeStart() {
        if (source != null) { Log.d(TAG, "maybeStart: source already running"); return }
        val url  = pendingUrl ?: run { Log.d(TAG, "maybeStart: no pending url"); return }
        val surf = surface    ?: run { Log.d(TAG, "maybeStart: waiting for surface"); return }

        if (NcnnBridge.ensureLibLoaded() !is LibLoadState.Loaded) {
            Log.w(TAG, "NCNN lib not available — video will play without AI overlay")
        }

        // Prefer the explicit dashcam network supplied by the caller (via
        // DashcamViewModel.connectedNetwork). Fall back to activeNetwork only
        // if nothing was passed; that's almost never right when the phone has
        // cellular data.
        val network: Network? = pendingNetwork ?: activeDashcamNetwork()
        Log.i(TAG, "maybeStart: starting MjpegFrameSource url=$url network=$network")
        val src = MjpegFrameSource(
            rtspUrl = url,
            network = network,
            displaySurface = surf,
            onError = { msg ->
                Log.e(TAG, "stream error: $msg")
                _state.value = LiveState.Error(msg)
            },
        )
        src.start()
        source = src
        _state.value = LiveState.Streaming

        collectorJob = viewModelScope.launch(Dispatchers.Default) {
            src.frames.collect { frame ->
                val size = Size(frame.bitmap.width, frame.bitmap.height)
                if (_sourceFrameSize.value != size) _sourceFrameSize.value = size
                val n = frameIdx.incrementAndGet()
                _frameCounter.value = n
                if (n % INFERENCE_EVERY_N == 0) {
                    // trySend with DROP_OLDEST: if inference is behind, the
                    // previous staged frame is evicted here (memory-leak
                    // acceptable for now; bitmap GC reclaims it).
                    inferenceChannel.trySend(frame)
                }
            }
        }

        inferenceJob = viewModelScope.launch(Dispatchers.Default) {
            for (frame in inferenceChannel) {
                runCatching { processFrame(frame) }.onFailure {
                    Log.w(TAG, "pipeline failure (continuing)", it)
                }
            }
        }
    }

    private suspend fun processFrame(frame: Frame) {
        val det = ensureVehicleDetectorOrNull() ?: return
        val t0 = System.nanoTime()
        val vehicles = det.detect(frame)
        val vehicleMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
        histogram.record(vehicleMs)
        _latency.value = histogram.snapshot()

        val plateDet = ensurePlateDetectorOrNull()
        val ocrEngine = ensureOcrOrNull()
        val plates = if (plateDet != null && vehicles.isNotEmpty()) {
            vehicles.flatMapIndexed { idx, v ->
                val x = v.bbox.left.toInt()
                val y = v.bbox.top.toInt()
                val w = (v.bbox.right  - v.bbox.left).toInt()
                val h = (v.bbox.bottom - v.bbox.top ).toInt()
                val raw = runCatching { plateDet.detectInCrop(frame.bitmap, idx, x, y, w, h) }
                    .getOrElse { emptyList() }
                if (ocrEngine == null) raw
                else raw.map { p ->
                    val crop = cropBitmap(frame.bitmap, p.bbox) ?: return@map p
                    val recog = runCatching { ocrEngine.recognize(crop) }.getOrNull()
                    crop.recycle()
                    if (recog != null) p.copy(recognition = recog) else p
                }
            }
        } else emptyList()

        _scene.value = TrackedScene(
            sourceFrameSize    = Size(frame.bitmap.width, frame.bitmap.height),
            detections         = vehicles,
            timestampNanos     = frame.timestampNanos,
            inferenceLatencyMs = vehicleMs,
            plates             = plates,
        )
    }

    private fun cropBitmap(src: Bitmap, bbox: android.graphics.RectF): Bitmap? {
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
            context = getApplication(),
            modelSource = NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE,
            useGpu = true,
        )
        return try { built.initialize(); vehicle = built; built }
        catch (t: Throwable) { Log.w(TAG, "vehicle detector init failed", t); null }
    }

    private suspend fun ensurePlateDetectorOrNull(): NcnnPlateDetector? {
        plate?.let { return it }
        val built = NcnnPlateDetector(context = getApplication(), useGpu = true)
        return try { built.initialize(); plate = built; built }
        catch (t: Throwable) { Log.w(TAG, "plate detector init failed", t); null }
    }

    private suspend fun ensureOcrOrNull(): OnnxPlateOcr? {
        ocr?.let { return it }
        val built = OnnxPlateOcr(context = getApplication())
        return try { built.initialize(); ocr = built; built }
        catch (t: Throwable) { Log.w(TAG, "OCR init failed", t); null }
    }

    /**
     * Pull the Wi-Fi Network bound to the dashcam SSID from the system, so
     * the RTSP/UDP sockets route through the camera's AP instead of cellular.
     * Returns null if nothing qualifies — the stream will still try via the
     * default network, which is fine when the phone is connected to the
     * dashcam Wi-Fi as its only network.
     */
    private fun activeDashcamNetwork(): Network? {
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.activeNetwork
    }

    private fun stopStream() {
        source?.stop()
        source = null
        collectorJob?.cancel(); collectorJob = null
        inferenceJob?.cancel(); inferenceJob = null
        _scene.value = null
        frameIdx.set(0)
    }

    override fun onCleared() {
        stopStream()
        vehicle?.release(); vehicle = null
        plate?.release();   plate = null
        ocr?.release();     ocr = null
        inferenceChannel.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Trafy.VisionLiveVM"
        /** Spec §3 cadence: inference every N frames. Chunk 5 auto-tunes this. */
        private const val INFERENCE_EVERY_N = 3
    }
}

sealed class LiveState {
    object Idle : LiveState()
    object Connecting : LiveState()
    object Streaming  : LiveState()
    data class Error(val message: String) : LiveState()
}
