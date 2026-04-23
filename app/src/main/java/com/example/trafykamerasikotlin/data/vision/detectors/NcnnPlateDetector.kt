package com.example.trafykamerasikotlin.data.vision.detectors

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.trafykamerasikotlin.data.vision.ModelLoader
import com.example.trafykamerasikotlin.data.vision.ModelSource
import com.example.trafykamerasikotlin.data.vision.ModelTelemetry
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnBridge
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnDetectorSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NCNN-backed plate detector. Runs on a per-vehicle crop, not the full frame.
 *
 * The underlying model is `morsetechlab/yolov11-license-plate-detection`
 * (nano, single-class). Output layout is identical to a vehicle YOLO11n
 * (same `in0`/`out0` blobs, 8400 anchors, just one class channel) so the
 * native `YoloDetector` wrapper handles it unchanged.
 *
 * Single-threaded — callers serialize via the VisionPipeline coroutine.
 */
class NcnnPlateDetector(
    private val context: Context,
    private val modelSource: ModelSource = DEFAULT_PLATE_SOURCE,
    private val useGpu: Boolean = true,
    // Lower than the vehicle threshold (0.25) because the morsetechlab model
    // is trained on international plates and returns softer confidences on
    // Turkish-format plates. A Chunk 5 fine-tune on Roboflow Turkish Number
    // Plates will let us raise this again.
    private val confThreshold: Float = 0.15f,
    private val iouThreshold:  Float = 0.45f,
) : PlateDetector {

    private var telemetry: ModelTelemetry? = null
    val modelTelemetry: ModelTelemetry? get() = telemetry

    override suspend fun initialize() {
        withContext(Dispatchers.Default) {
            telemetry = ModelLoader.loadYolo(context, SLOT, modelSource, useGpu)
                ?: throw IllegalStateException(
                    "Failed to load plate model: ${NcnnBridge.lastError(SLOT).orEmpty()}"
                )
            Log.i(TAG, "initialized: $telemetry")
        }
    }

    override suspend fun detectInCrop(
        fullFrame:    Bitmap,
        vehicleIndex: Int,
        cropX:        Int,
        cropY:        Int,
        cropW:        Int,
        cropH:        Int,
    ): List<PlateDetection> = withContext(Dispatchers.Default) {
        // Clip crop to frame bounds. A vehicle box near the edge can easily
        // extend past the frame once letterboxing / rounding is applied.
        val frameW = fullFrame.width
        val frameH = fullFrame.height
        val x = cropX.coerceIn(0, frameW - 1)
        val y = cropY.coerceIn(0, frameH - 1)
        val w = cropW.coerceAtLeast(1).coerceAtMost(frameW - x)
        val h = cropH.coerceAtLeast(1).coerceAtMost(frameH - y)
        if (w < MIN_CROP_PX || h < MIN_CROP_PX) return@withContext emptyList()

        val src = if (fullFrame.config == Bitmap.Config.ARGB_8888) fullFrame
            else fullFrame.copy(Bitmap.Config.ARGB_8888, false)

        // Bitmap.createBitmap does not share pixels — it's a copy — so the
        // native detector can lock/unlock this buffer without disturbing the
        // source frame.
        val crop = Bitmap.createBitmap(src, x, y, w, h)

        val flat = NcnnBridge.detectBitmap(SLOT, crop, confThreshold, iouThreshold)
        crop.recycle()
        if (flat.isEmpty()) return@withContext emptyList()

        val out = ArrayList<PlateDetection>(flat.size / 6)
        var i = 0
        while (i + 5 < flat.size) {
            // flat = [cls, conf, x1, y1, x2, y2, …] in CROP-LOCAL coords.
            val conf = flat[i + 1]
            val lx1  = flat[i + 2]
            val ly1  = flat[i + 3]
            val lx2  = flat[i + 4]
            val ly2  = flat[i + 5]
            // Shift back into full-frame coordinates.
            out += PlateDetection(
                parentVehicleIndex = vehicleIndex,
                confidence = conf,
                bbox = RectF(lx1 + x, ly1 + y, lx2 + x, ly2 + y),
            )
            i += 6
        }
        out
    }

    override fun release() {
        NcnnBridge.release(SLOT)
        telemetry = null
    }

    companion object {
        private const val TAG = "Trafy.PlateDetector"
        private val SLOT = NcnnDetectorSlot.PLATE

        /**
         * Skip crops smaller than this on either axis. The model expects
         * ≥640px input after letterbox; crops much smaller than this give
         * poor recall and waste inference time.
         */
        private const val MIN_CROP_PX = 32

        val DEFAULT_PLATE_SOURCE = ModelSource.Assets(
            paramAssetPath  = "models/plate/plate.param",
            binAssetPath    = "models/plate/plate.bin",
            labelsAssetPath = "models/plate/labels.txt",
        )
    }
}
