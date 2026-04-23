package com.example.trafykamerasikotlin.data.vision.detectors

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.ModelLoader
import com.example.trafykamerasikotlin.data.vision.ModelSource
import com.example.trafykamerasikotlin.data.vision.ModelTelemetry
import com.example.trafykamerasikotlin.data.vision.VehicleClass
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnBridge
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnDetectorSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NCNN-backed vehicle detector. Single-threaded — the VisionPipeline feeds
 * it from one inference coroutine; concurrent [detect] calls are undefined
 * behaviour.
 */
class NcnnVehicleDetector(
    private val context: Context,
    private val modelSource: ModelSource,
    private val useGpu: Boolean = true,
    private val confThreshold: Float = 0.25f,
    private val iouThreshold:  Float = 0.45f,
) : VehicleDetector {

    private var telemetry: ModelTelemetry? = null
    private val whitelist = VehicleClass.WHITELIST

    val modelTelemetry: ModelTelemetry? get() = telemetry

    override suspend fun initialize() {
        withContext(Dispatchers.Default) {
            telemetry = ModelLoader.loadYolo(context, SLOT, modelSource, useGpu)
                ?: throw IllegalStateException(
                    "Failed to load YOLO model: ${NcnnBridge.lastError(SLOT).orEmpty()}"
                )
            Log.i(TAG, "initialized: $telemetry")
        }
    }

    override suspend fun detect(frame: Frame): List<Detection> = withContext(Dispatchers.Default) {
        val bitmap = frame.bitmap.asArgb8888()
        val flat = NcnnBridge.detectBitmap(SLOT, bitmap, confThreshold, iouThreshold)
        if (flat.isEmpty()) return@withContext emptyList()

        val out = ArrayList<Detection>(flat.size / 6)
        var i = 0
        while (i + 5 < flat.size) {
            val cls = VehicleClass.fromCocoIndex(flat[i].toInt())
            if (cls in whitelist) {
                out += Detection(
                    cls        = cls,
                    confidence = flat[i + 1],
                    bbox       = RectF(flat[i + 2], flat[i + 3], flat[i + 4], flat[i + 5]),
                )
            }
            i += 6
        }
        out
    }

    override fun release() {
        NcnnBridge.release(SLOT)
        telemetry = null
    }

    /** NCNN's AndroidBitmap hook requires ARGB_8888. Convert defensively. */
    private fun Bitmap.asArgb8888(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888 && !isRecycled) {
            this
        } else {
            val converted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(converted)
            canvas.drawBitmap(this, Matrix(), null)
            converted
        }

    companion object {
        private const val TAG = "Trafy.VehicleDetector"
        private val SLOT = NcnnDetectorSlot.VEHICLE

        /**
         * Asset paths for the bundled YOLO detectors. Matches the layout
         * written by `scripts/export-yolo-ncnn.py`.
         *
         * Chunk 1 default is YOLO11n: mature NCNN Vulkan + CPU support, ~39ms
         * CPU reference latency. YOLO26n is exportable (the script's default)
         * and is faster on paper, but ncnn 20260113's CPU backend produces
         * zero box coordinates for its new-style box head; use YOLO26N_SOURCE
         * only when you explicitly want Vulkan-only behavior.
         */
        val DEFAULT_YOLO11N_SOURCE = ModelSource.Assets(
            paramAssetPath  = "models/yolo11n/yolo11n.param",
            binAssetPath    = "models/yolo11n/yolo11n.bin",
            labelsAssetPath = "models/yolo11n/labels.txt",
        )

        val YOLO26N_SOURCE = ModelSource.Assets(
            paramAssetPath  = "models/yolo26n/yolo26n.param",
            binAssetPath    = "models/yolo26n/yolo26n.bin",
            labelsAssetPath = "models/yolo26n/labels.txt",
        )
    }
}
