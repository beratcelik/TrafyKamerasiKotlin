package com.example.trafykamerasikotlin.data.vision

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.vision.ncnn.NcnnBridge
import java.security.MessageDigest
import kotlin.system.measureTimeMillis

/**
 * Source of a model's `.param` + `.bin`. Chunk 1 ships only [Assets]; the
 * [LocalFile] branch exists so a later Trafy-hosted model-over-the-air step
 * (verified via the same signed-manifest infra as `data/update/`) slots in
 * without touching call sites.
 */
sealed class ModelSource {
    data class Assets(
        val paramAssetPath: String,
        val binAssetPath:   String,
        val labelsAssetPath: String? = null,
    ) : ModelSource()

    data class LocalFile(
        val paramFilePath: String,
        val binFilePath:   String,
        val labelsFilePath: String? = null,
    ) : ModelSource()
}

/** What the debug screen needs to render load-time + integrity info. */
data class ModelTelemetry(
    val paramSizeBytes: Long,
    val binSizeBytes:   Long,
    val paramSha256Prefix: String,
    val binSha256Prefix:   String,
    val loadTimeMillis: Long,
    val vulkan:         Boolean,
) {
    val totalSizeBytes: Long get() = paramSizeBytes + binSizeBytes
}

object ModelLoader {

    private const val TAG = "Trafy.ModelLoader"

    /**
     * Hand off the model to [NcnnBridge] and return [ModelTelemetry] for the
     * debug screen. Returns null if the load failed; callers should render an
     * error state using `NcnnBridge.lastError()`.
     */
    fun loadYolo(
        context: Context,
        source:  ModelSource,
        useGpu:  Boolean,
    ): ModelTelemetry? {
        val lib = NcnnBridge.ensureLibLoaded()
        if (lib !is com.example.trafykamerasikotlin.data.vision.ncnn.LibLoadState.Loaded) {
            Log.w(TAG, "ensureLibLoaded returned $lib — cannot load model")
            return null
        }

        return when (source) {
            is ModelSource.Assets -> loadFromAssets(context, source, useGpu)
            is ModelSource.LocalFile -> loadFromLocalFile(source, useGpu)
        }
    }

    private fun loadFromAssets(
        context: Context,
        source:  ModelSource.Assets,
        useGpu:  Boolean,
    ): ModelTelemetry? {
        val assets = context.assets
        val (paramSize, paramSha) = assets.fingerprint(source.paramAssetPath) ?: return null.also {
            Log.e(TAG, "param asset missing: ${source.paramAssetPath}")
        }
        val (binSize, binSha) = assets.fingerprint(source.binAssetPath) ?: return null.also {
            Log.e(TAG, "bin asset missing: ${source.binAssetPath}")
        }

        var ok = false
        val elapsed = measureTimeMillis {
            ok = NcnnBridge.loadModel(assets, source.paramAssetPath, source.binAssetPath, useGpu)
        }
        if (!ok) {
            Log.e(TAG, "NcnnBridge.loadModel failed: ${NcnnBridge.lastError()}")
            return null
        }
        return ModelTelemetry(
            paramSizeBytes      = paramSize,
            binSizeBytes        = binSize,
            paramSha256Prefix   = paramSha,
            binSha256Prefix     = binSha,
            loadTimeMillis      = elapsed,
            vulkan              = useGpu,
        )
    }

    private fun loadFromLocalFile(source: ModelSource.LocalFile, useGpu: Boolean): ModelTelemetry? {
        // Chunk 1 doesn't implement runtime model downloads. Leaving this
        // branch unreachable (would-be MOA step fills it in) rather than
        // shipping a half-finished pipeline.
        Log.e(TAG, "ModelSource.LocalFile is not wired in Chunk 1 — use ModelSource.Assets")
        return null
    }

    private fun android.content.res.AssetManager.fingerprint(path: String): Pair<Long, String>? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            open(path).use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    total += n
                }
            }
            total to digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        } catch (t: Throwable) {
            Log.w(TAG, "fingerprint($path) failed", t)
            null
        }
}
