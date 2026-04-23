package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.pipeline.LiveVisionPipeline
import com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight VM that wraps a [LiveVisionPipeline] for the production Live
 * tab. Unlike [VisionDebugLiveViewModel], this VM does NOT own a video
 * player — the real [LiveScreen] already manages one via
 * `MjpegLivePlayer` / `RtspPlayer`. We just hook into its per-frame tap.
 *
 * Lifecycle: the pipeline is built lazily on [onFrame] to avoid paying the
 * ~5 s model load when AI overlay is disabled. Released in [onCleared].
 */
class LiveVisionOverlayViewModel(app: Application) : AndroidViewModel(app) {

    @Volatile private var pipeline: LiveVisionPipeline? = null

    /** Snapshot of the latest TrackedScene emitted by the pipeline. Null while idle. */
    val scene: StateFlow<TrackedScene?> get() = pipelineOrStart().scene

    /** Rolling latency stats for the inference side of the pipeline. */
    val latency: StateFlow<LatencyHistogram.Snapshot> get() = pipelineOrStart().latency

    /** Number of frames forwarded to the pipeline since start — mainly for the debug badge. */
    val submittedCount: StateFlow<Int> get() = pipelineOrStart().submittedCount

    /**
     * Hand the pipeline one decoded frame. Intended to be wired to
     * [com.example.trafykamerasikotlin.data.media.MjpegRtspPlayer.onFrame].
     *
     * Safe to call from the MJPEG receive thread — the pipeline queues
     * internally and does inference on its own dispatcher.
     */
    fun onFrame(bitmap: Bitmap) {
        pipelineOrStart().submit(bitmap)
    }

    /** Start with a clean tracker / vote book — e.g. after the stream reconnects. */
    fun reset() {
        pipeline?.reset()
    }

    private fun pipelineOrStart(): LiveVisionPipeline {
        pipeline?.let { return it }
        synchronized(this) {
            pipeline?.let { return it }
            val p = LiveVisionPipeline(context = getApplication())
            p.start()
            pipeline = p
            return p
        }
    }

    override fun onCleared() {
        pipeline?.release()
        pipeline = null
        super.onCleared()
    }
}
