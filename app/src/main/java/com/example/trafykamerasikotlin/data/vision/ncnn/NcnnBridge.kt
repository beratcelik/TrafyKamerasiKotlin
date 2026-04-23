package com.example.trafykamerasikotlin.data.vision.ncnn

import android.content.res.AssetManager
import android.util.Log
import com.example.trafykamerasikotlin.BuildConfig

/**
 * Single point of contact between Kotlin and libtrafy_vision.so.
 *
 * Deliberately `object` (process-wide singleton) — the C++ detector instance
 * is also a singleton guarded by a mutex on the native side. Double-init is
 * guarded here too so we don't `System.loadLibrary` twice across ViewModel
 * recreates.
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
                // CMake was skipped because scripts/setup-ncnn.sh hasn't been run.
                // Report the specific reason — distinct from "lib built but
                // missing at runtime" so ops messages are actionable.
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
    fun probe(): String? =
        if (ensureLibLoaded() is LibLoadState.Loaded) nativeProbe() else null

    /** Load a model from the APK's assets. False on failure — see [lastError]. */
    fun loadModel(
        assets:   AssetManager,
        paramAsset: String,
        binAsset:   String,
        useGpu:     Boolean,
    ): Boolean = ensureLibLoaded() is LibLoadState.Loaded &&
        nativeLoadModel(assets, paramAsset, binAsset, useGpu)

    /**
     * Run inference. Returns raw `[cls, conf, x1, y1, x2, y2, …]` flat array
     * in ORIGINAL frame coordinates. Empty on failure.
     * Bitmap MUST be [android.graphics.Bitmap.Config.ARGB_8888].
     */
    fun detectBitmap(
        bitmap: android.graphics.Bitmap,
        confThreshold: Float = 0.25f,
        iouThreshold:  Float = 0.45f,
    ): FloatArray =
        if (ensureLibLoaded() is LibLoadState.Loaded) nativeDetectBitmap(bitmap, confThreshold, iouThreshold)
        else floatArrayOf()

    /** Last native error (empty string if none). */
    fun lastError(): String? =
        if (ensureLibLoaded() is LibLoadState.Loaded) nativeLastError() else null

    fun release() {
        if (libState is LibLoadState.Loaded) {
            try { nativeRelease() } catch (t: Throwable) { Log.w(TAG, "release() ignored", t) }
        }
    }

    // ----- JNI -----
    @JvmStatic private external fun nativeProbe(): String
    @JvmStatic private external fun nativeLoadModel(
        assets: AssetManager, paramAsset: String, binAsset: String, useGpu: Boolean,
    ): Boolean
    @JvmStatic private external fun nativeDetectBitmap(
        bitmap: android.graphics.Bitmap, confThreshold: Float, iouThreshold: Float,
    ): FloatArray
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeRelease()
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
