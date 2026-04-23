package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.ModelTelemetry
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnPlateDetector
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnVehicleDetector
import com.example.trafykamerasikotlin.data.vision.frame.FileFrameSource
import com.example.trafykamerasikotlin.data.vision.ncnn.LibLoadState
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnBridge
import com.example.trafykamerasikotlin.data.vision.ocr.OnnxPlateOcr
import com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Drives the Vision Debug screen.
 *
 * - Lazily ensures the NCNN native lib is present and reports any reason it's
 *   missing (so collaborators who haven't run `setup-ncnn.sh` see a clear
 *   message rather than a crash).
 * - Rebuilds the [NcnnVehicleDetector] when the Vulkan/CPU toggle flips.
 * - Runs single-frame inference on a user-picked MP4 via [FileFrameSource].
 * - Runs a synthetic benchmark to separate model speed from video-decode cost.
 */
class VisionDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<VisionDebugState>(VisionDebugState.Idle)
    val state: StateFlow<VisionDebugState> = _state.asStateFlow()

    private val _useGpu = MutableStateFlow(true)
    val useGpu: StateFlow<Boolean> = _useGpu.asStateFlow()

    private val _telemetry = MutableStateFlow<ModelTelemetry?>(null)
    val telemetry: StateFlow<ModelTelemetry?> = _telemetry.asStateFlow()

    private val _plateTelemetry = MutableStateFlow<ModelTelemetry?>(null)
    val plateTelemetry: StateFlow<ModelTelemetry?> = _plateTelemetry.asStateFlow()

    private val _scene = MutableStateFlow<TrackedScene?>(null)
    val scene: StateFlow<TrackedScene?> = _scene.asStateFlow()

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private val _latency = MutableStateFlow(LatencyHistogram.Snapshot.EMPTY)
    val latency: StateFlow<LatencyHistogram.Snapshot> = _latency.asStateFlow()

    val backendState: LibLoadState get() = NcnnBridge.ensureLibLoaded()

    private val histogram = LatencyHistogram()
    private var detector: NcnnVehicleDetector? = null
    private var plateDetector: NcnnPlateDetector? = null
    private var plateOcr: OnnxPlateOcr? = null

    init {
        NcnnBridge.ensureLibLoaded()
    }

    fun setUseGpu(useGpu: Boolean) {
        if (_useGpu.value == useGpu) return
        _useGpu.value = useGpu
        // Model needs to be reloaded under the new backend — tear down and
        // let the next inference re-initialize lazily.
        viewModelScope.launch(Dispatchers.Default) {
            detector?.release()
            detector = null
            _telemetry.value = null
            plateDetector?.release()
            plateDetector = null
            _plateTelemetry.value = null
        }
    }

    fun runOnPickedVideo(uri: Uri) {
        viewModelScope.launch {
            runOnUri(uri)
        }
    }

    /**
     * Runs inference N times on a synthetic gradient pattern; reports cold-start
     * and warm stats. Decouples model-cost from file-decode cost.
     */
    fun runBenchmark(iterations: Int = 30) {
        viewModelScope.launch {
            val det = ensureDetectorOrReportError() ?: return@launch
            histogram.clear()
            _state.value = VisionDebugState.Benchmarking
            val synthetic = makeSyntheticFrame()
            _currentBitmap.value = synthetic.bitmap

            // cold-start: first call includes any JIT/Vulkan pipeline compilation.
            val cold = runCatching { measureTimeMillis { det.detect(synthetic) } }.getOrDefault(-1L)
            if (cold < 0) {
                val err = NcnnBridge.lastError(com.example.trafykamerasikotlin.data.vision.ncnn.NcnnDetectorSlot.VEHICLE).orEmpty()
                _state.value = VisionDebugState.Error("benchmark cold-start failed: $err")
                return@launch
            }

            repeat(iterations) {
                val ms = runCatching { measureTimeMillis { det.detect(synthetic) } }.getOrDefault(-1L).toInt()
                if (ms >= 0) histogram.record(ms)
                _latency.value = histogram.snapshot()
            }

            _state.value = VisionDebugState.BenchmarkDone(
                coldStartMs = cold.toInt(),
                snapshot = histogram.snapshot(),
            )
        }
    }

    private suspend fun runOnUri(uri: Uri) {
        val det = ensureDetectorOrReportError() ?: return
        _state.value = VisionDebugState.Decoding
        val frame = withContext(Dispatchers.IO) {
            val src = FileFrameSource(getApplication(), uri)
            src.start()
            try {
                src.frames.first()
            } catch (t: Throwable) {
                Log.w(TAG, "no frame from $uri", t)
                null
            }
        }
        if (frame == null) {
            _state.value = VisionDebugState.Error("no frame decoded from picked video")
            return
        }
        _currentBitmap.value = frame.bitmap

        _state.value = VisionDebugState.Inferring
        val startedVeh = System.nanoTime()
        val dets = runCatching { det.detect(frame) }.getOrElse {
            _state.value = VisionDebugState.Error("inference failed: ${it.message ?: "unknown"}")
            return
        }
        val vehicleLatencyMs = ((System.nanoTime() - startedVeh) / 1_000_000L).toInt()
        histogram.record(vehicleLatencyMs)
        _latency.value = histogram.snapshot()

        // Chunk 2: run plate detection inside each vehicle's bounding box.
        // Runs only if the plate detector is initialized; failure to load
        // doesn't break the vehicle pipeline.
        val (plateDets, plateLatencyMs) = runPlateDetectionIfReady(frame, dets)

        _scene.value = TrackedScene(
            sourceFrameSize    = Size(frame.bitmap.width, frame.bitmap.height),
            detections         = dets,
            timestampNanos     = frame.timestampNanos,
            inferenceLatencyMs = vehicleLatencyMs,
            plates             = plateDets,
            plateLatencyMs     = plateLatencyMs,
        )
        _state.value = VisionDebugState.FrameResult(dets.size, vehicleLatencyMs, plateDets?.size ?: 0)
    }

    private suspend fun runPlateDetectionIfReady(
        frame: Frame,
        vehicles: List<Detection>,
    ): Pair<List<PlateDetection>?, Int?> {
        val plateDet = ensurePlateDetectorOrLogError() ?: return null to null
        if (vehicles.isEmpty()) return emptyList<PlateDetection>() to 0

        val startedPlate = System.nanoTime()
        val rawPlates = mutableListOf<PlateDetection>()
        vehicles.forEachIndexed { idx, v ->
            val x = v.bbox.left.toInt()
            val y = v.bbox.top.toInt()
            val w = (v.bbox.right - v.bbox.left).toInt()
            val h = (v.bbox.bottom - v.bbox.top).toInt()
            rawPlates += runCatching {
                plateDet.detectInCrop(frame.bitmap, idx, x, y, w, h)
            }.getOrElse {
                Log.w(TAG, "plate detect on vehicle $idx failed", it)
                emptyList()
            }
        }

        // Chunk 3: for every detected plate, run OCR on the plate crop.
        // Best-effort; failure does not remove the plate box — just leaves
        // recognition=null so the overlay shows box-without-text.
        val ocr = ensurePlateOcrOrLogError()
        val withText = if (ocr != null) rawPlates.map { p ->
            val cropBmp = cropBitmap(frame.bitmap, p.bbox) ?: return@map p
            val recog = runCatching { ocr.recognize(cropBmp) }
                .onFailure { Log.w(TAG, "OCR failed on plate ${p.parentVehicleIndex}", it) }
                .getOrNull()
            cropBmp.recycle()
            if (recog != null) p.copy(recognition = recog) else p
        } else rawPlates

        val latencyMs = ((System.nanoTime() - startedPlate) / 1_000_000L).toInt()
        return withText to latencyMs
    }

    private fun cropBitmap(src: android.graphics.Bitmap, bbox: android.graphics.RectF): android.graphics.Bitmap? {
        val x = bbox.left.toInt().coerceIn(0, src.width - 1)
        val y = bbox.top.toInt().coerceIn(0, src.height - 1)
        val w = (bbox.right  - bbox.left).toInt().coerceAtLeast(1).coerceAtMost(src.width - x)
        val h = (bbox.bottom - bbox.top ).toInt().coerceAtLeast(1).coerceAtMost(src.height - y)
        if (w < 4 || h < 4) return null
        val argb = if (src.config == android.graphics.Bitmap.Config.ARGB_8888) src
            else src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        return android.graphics.Bitmap.createBitmap(argb, x, y, w, h)
    }

    private suspend fun ensurePlateOcrOrLogError(): OnnxPlateOcr? {
        val existing = plateOcr
        if (existing != null) return existing
        val built = OnnxPlateOcr(context = getApplication())
        return try {
            built.initialize()
            plateOcr = built
            built
        } catch (t: Throwable) {
            Log.w(TAG, "plate OCR init failed; continuing without OCR text", t)
            null
        }
    }

    private suspend fun ensurePlateDetectorOrLogError(): NcnnPlateDetector? {
        val existing = plateDetector
        if (existing != null && _plateTelemetry.value != null) return existing
        val built = NcnnPlateDetector(
            context     = getApplication(),
            useGpu      = _useGpu.value,
        )
        return try {
            built.initialize()
            plateDetector = built
            _plateTelemetry.value = built.modelTelemetry
            built
        } catch (t: Throwable) {
            Log.w(TAG, "plate detector init failed; continuing without plates", t)
            null
        }
    }

    /** Returns null and pushes an error state when the detector can't be prepared. */
    private suspend fun ensureDetectorOrReportError(): NcnnVehicleDetector? {
        val lib = NcnnBridge.ensureLibLoaded()
        if (lib !is LibLoadState.Loaded) {
            _state.value = VisionDebugState.BackendMissing(lib)
            return null
        }
        val existing = detector
        if (existing != null && _telemetry.value != null) return existing

        _state.value = VisionDebugState.LoadingModel
        // YOLO11n is Chunk 1's current default — mature NCNN support on both
        // CPU and Vulkan. YOLO26n is exportable via `scripts/export-yolo-ncnn.py`
        // but its CPU box-head is broken in ncnn 20260113 (zero box coords
        // despite correct class scores); revisit once upstream ships a fix.
        val builder = NcnnVehicleDetector(
            context = getApplication(),
            modelSource = NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE,
            useGpu = _useGpu.value,
        )
        return try {
            builder.initialize()
            detector = builder
            _telemetry.value = builder.modelTelemetry
            builder
        } catch (t: Throwable) {
            val err = NcnnBridge.lastError(com.example.trafykamerasikotlin.data.vision.ncnn.NcnnDetectorSlot.VEHICLE).orEmpty()
            _state.value = VisionDebugState.Error(
                "model load failed: ${t.message ?: "unknown"} • $err"
            )
            null
        }
    }

    private fun makeSyntheticFrame(): Frame {
        // Cheap procedural pattern — a gradient is enough to exercise the model.
        val size = 640
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            0xFF223355.toInt(), 0xFFAACCEE.toInt(),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return Frame(bitmap = bmp, timestampNanos = System.nanoTime())
    }

    override fun onCleared() {
        detector?.release()
        detector = null
        plateDetector?.release()
        plateDetector = null
        plateOcr?.release()
        plateOcr = null
        super.onCleared()
    }

    companion object { private const val TAG = "Trafy.VisionDebugVM" }
}

sealed class VisionDebugState {
    object Idle : VisionDebugState()
    object Decoding : VisionDebugState()
    object LoadingModel : VisionDebugState()
    object Inferring : VisionDebugState()
    object Benchmarking : VisionDebugState()
    data class BenchmarkDone(val coldStartMs: Int, val snapshot: LatencyHistogram.Snapshot) : VisionDebugState()
    data class FrameResult(val numDetections: Int, val latencyMs: Int, val numPlates: Int = 0) : VisionDebugState()
    data class BackendMissing(val state: LibLoadState) : VisionDebugState()
    data class Error(val message: String) : VisionDebugState()
}
