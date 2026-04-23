package com.example.trafykamerasikotlin.data.vision.ncnn

import android.content.res.AssetManager
import android.util.Log
import com.example.trafykamerasikotlin.BuildConfig

/**
 * Single point of contact between Kotlin and libtrafy_vision.so.
 *
 * The native layer holds **multiple** YoloDetector instances keyed by an
 * integer slot id, so a long-lived vehicle detector and a long-lived plate
 * detector can coexist without either swapping its loaded model per frame
 * (which would cost ~5 seconds of Vulkan shader compilation each time).
 * Slot ids are chosen by Kotlin callers — see [NcnnDetectorSlot].
 *
 * If the native library is unavailable (collaborator hasn't run
 * `scripts/setup-ncnn.sh` + `scripts/export-yolo-ncnn.py`), [ensureLibLoaded]
 * returns a clean failure and the Vision Debug screen surfaces a
 * "backend not installed" state instead of crashing.
 */
object NcnnBridge {

    private const val TAG = "Trafy.NcnnBridge"
    private const val LIB_NAME = "trafy_vision"

    @Volatile private var libState: LibLoadState = LibLoadState.NotAttempted
    private val libLock = Any()

    /** The JNI lib must be present at runtime for any other method to succeed. */
    fun ensureLibLoaded(): LibLoadState {
        val cached = libState
        if (cached !is LibLoadState.NotAttempted) return cached
        synchronized(libLock) {
            val current = libState
            if (current !is LibLoadState.NotAttempted) return current
            val next = if (!BuildConfig.NCNN_PREBUILT_BUNDLED) {
                Log.w(TAG, "NCNN prebuilt blobs not bundled — native lib will not be present")
                LibLoadState.NotBundled
            } else try {
                System.loadLibrary(LIB_NAME)
                LibLoadState.Loaded
            } catch (t: UnsatisfiedLinkError) {
                Log.e(TAG, "System.loadLibrary($LIB_NAME) failed", t)
                LibLoadState.LoadFailed(t.message ?: "UnsatisfiedLinkError")
            } catch (t: Throwable) {
                Log.e(TAG, "System.loadLibrary($LIB_NAME) threw", t)
                LibLoadState.LoadFailed(t.message ?: t.javaClass.simpleName)
            }
            libState = next
            return next
        }
    }

    /** Short diagnostic string for the debug screen. */
    fun probe(slot: NcnnDetectorSlot): String? =
        if (ensureLibLoaded() is LibLoadState.Loaded) nativeProbe(slot.id) else null

    /** Load a model from the APK's assets. False on failure — see [lastError]. */
    fun loadModel(
        slot:       NcnnDetectorSlot,
        assets:     AssetManager,
        paramAsset: String,
        binAsset:   String,
        useGpu:     Boolean,
        targetSize: Int = 640,
    ): Boolean = ensureLibLoaded() is LibLoadState.Loaded &&
        nativeLoadModel(slot.id, assets, paramAsset, binAsset, useGpu, targetSize)

    /**
     * Run inference on the given slot's loaded model. Returns raw
     * `[cls, conf, x1, y1, x2, y2, …]` flat array in ORIGINAL bitmap
     * coordinates. Empty on failure.
     * Bitmap MUST be [android.graphics.Bitmap.Config.ARGB_8888].
     */
    fun detectBitmap(
        slot:   NcnnDetectorSlot,
        bitmap: android.graphics.Bitmap,
        confThreshold: Float = 0.25f,
        iouThreshold:  Float = 0.45f,
    ): FloatArray =
        if (ensureLibLoaded() is LibLoadState.Loaded) nativeDetectBitmap(slot.id, bitmap, confThreshold, iouThreshold)
        else floatArrayOf()

    /** Last native error on this slot (empty string if none). */
    fun lastError(slot: NcnnDetectorSlot): String? =
        if (ensureLibLoaded() is LibLoadState.Loaded) nativeLastError(slot.id) else null

    fun release(slot: NcnnDetectorSlot) {
        if (libState is LibLoadState.Loaded) {
            try { nativeRelease(slot.id) } catch (t: Throwable) { Log.w(TAG, "release($slot) ignored", t) }
        }
    }

    // ----- JNI -----
    @JvmStatic private external fun nativeProbe(id: Int): String
    @JvmStatic private external fun nativeLoadModel(
        id: Int, assets: AssetManager, paramAsset: String, binAsset: String,
        useGpu: Boolean, targetSize: Int,
    ): Boolean
    @JvmStatic private external fun nativeDetectBitmap(
        id: Int, bitmap: android.graphics.Bitmap, confThreshold: Float, iouThreshold: Float,
    ): FloatArray
    @JvmStatic private external fun nativeLastError(id: Int): String
    @JvmStatic private external fun nativeRelease(id: Int)
}

/**
 * Identifies one native YoloDetector instance. Keep this list short — each
 * slot reserves a fully-loaded model in memory (+Vulkan pipeline, ~30 MB
 * VRAM). Match these ids with the switch in yolo_jni.cpp::tag_for_id().
 */
enum class NcnnDetectorSlot(val id: Int) {
    VEHICLE(0),
    PLATE(1),
}

/** Discrete reasons the native layer might not be callable. */
sealed class LibLoadState {
    object NotAttempted : LibLoadState()
    /** `scripts/setup-ncnn.sh` + export script haven't been run on this checkout. */
    object NotBundled : LibLoadState()
    /** Lib was bundled but dlopen failed. Inspect [reason]. */
    data class LoadFailed(val reason: String) : LibLoadState()
    object Loaded : LibLoadState()
}
