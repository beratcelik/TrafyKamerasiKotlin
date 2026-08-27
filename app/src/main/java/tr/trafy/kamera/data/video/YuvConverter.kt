package tr.trafy.kamera.data.video

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

/**
 * Thin Kotlin façade over libyuv's `Android420ToARGB`. Replaces the
 * pure-Kotlin pixel loop that used to live in [VideoFrameSource]'s
 * `yuv420ToArgb` — at 1440p the loop dominated the sidecar scan
 * (~300-700 ms/frame). libyuv's hand-tuned NEON SIMD path runs the same
 * conversion in ~20-40 ms/frame on the same hardware.
 *
 * The native side handles every Android YUV layout (I420 / NV12 / NV21)
 * automatically via the U-plane pixel stride, so no codec-specific branching
 * here on the Kotlin side.
 */
object YuvConverter {

    init {
        // The same .so that hosts NcnnBridge — see CMakeLists.txt.
        System.loadLibrary("trafy_vision")
    }

    /**
     * Convert an Android [Image] in YUV_420_888 format directly into the
     * pixels of [dst] (must be ARGB_8888 and sized exactly [Image.getWidth] x
     * [Image.getHeight]). Returns `true` on success.
     *
     * The caller still owns [Image] and is responsible for closing it.
     * [dst]'s pixels are locked + written + unlocked inside the JNI call.
     */
    fun android420ToArgb(image: Image, dst: Bitmap): Boolean {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "expected YUV_420_888, got format=${image.format}"
        }
        require(dst.config == Bitmap.Config.ARGB_8888) {
            "destination bitmap must be ARGB_8888, got ${dst.config}"
        }
        val planes = image.planes
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        // U/V pixel stride is identical for both chroma planes in
        // YUV_420_888 — libyuv only takes one value (the dispatch key).
        val uvPixelStride = u.pixelStride
        return nativeAndroid420ToArgb(
            y.buffer, y.rowStride,
            u.buffer, u.rowStride,
            v.buffer, v.rowStride,
            uvPixelStride,
            dst, image.width, image.height,
        )
    }

    @JvmStatic
    private external fun nativeAndroid420ToArgb(
        yPlane: ByteBuffer, yStride: Int,
        uPlane: ByteBuffer, uStride: Int,
        vPlane: ByteBuffer, vStride: Int,
        uvPixelStride: Int,
        dst: Bitmap, width: Int, height: Int,
    ): Boolean
}
