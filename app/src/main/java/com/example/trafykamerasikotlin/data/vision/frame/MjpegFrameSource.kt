package com.example.trafykamerasikotlin.data.vision.frame

import android.graphics.Bitmap
import android.net.Network
import android.view.Surface
import com.example.trafykamerasikotlin.data.media.MjpegRtspPlayer
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.FrameSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [FrameSource] backed by [MjpegRtspPlayer]. Subscribes to the player's
 * per-frame tap, copies each decoded [Bitmap] (the player recycles the
 * original immediately after the callback) and emits it as a [Frame].
 *
 * Buffer policy: [BufferOverflow.DROP_OLDEST] with replay=1 + extra cap=1.
 * If the inference pipeline can't keep up, newer frames overwrite older
 * ones in the buffer — "live > complete" per the spec's §3 backpressure
 * policy.
 *
 * The player still needs a Surface for its main display render path, so
 * the caller must provide one. For debug screens that only care about
 * passing frames to the vision pipeline (no on-screen video), pass a
 * dummy Surface backed by a SurfaceTexture.
 */
class MjpegFrameSource(
    private val rtspUrl: String,
    private val network: Network?,
    private val displaySurface: Surface,
    private val onError: ((String) -> Unit)? = null,
) : FrameSource {

    private val _frames = MutableSharedFlow<Frame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<Frame> = _frames.asSharedFlow()

    private var player: MjpegRtspPlayer? = null

    override fun start() {
        val p = MjpegRtspPlayer(displaySurface, network)
        p.onFrame = { bmp ->
            // Copy out before the player recycles the source bitmap. ARGB_8888
            // is what downstream NCNN + ORT consumers expect.
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            _frames.tryEmit(Frame(bitmap = copy, timestampNanos = System.nanoTime()))
        }
        p.onError = { msg -> onError?.invoke(msg) }
        p.start(rtspUrl)
        player = p
    }

    override fun stop() {
        player?.onFrame = null
        player?.onError = null
        player?.stop()
        player = null
    }
}
