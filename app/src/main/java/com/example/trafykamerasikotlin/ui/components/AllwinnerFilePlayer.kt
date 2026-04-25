package com.example.trafykamerasikotlin.ui.components

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import tv.danmaku.ijk.media.player.IjkMediaPlayer

private const val TAG = "Trafy.AllwinnerPlayer"

/**
 * Plays a progressively-written local MPEG-TS file via IjkPlayer.
 *
 * Used by Allwinner recorded-file playback: MediaViewModel streams RTP2P UDP
 * payloads into a cache file and hands its `file://` URI here once enough data
 * is buffered. IjkPlayer tolerates the file growing under it because the TS
 * container is self-framing.
 *
 * Renders to a [TextureView] so the AI-overlay path can poll
 * [TextureView.getBitmap] and feed the vision pipeline. When [onFrame] is
 * non-null, a 100 ms coroutine tick captures bitmaps and forwards them.
 *
 * Tuning rationale (different from the live-RTSP config in LiveScreen):
 *   - probesize/analyzeduration low: the file starts with TS packets, no need
 *     to scan for codec params
 *   - packet-buffering enabled: avoids the audio-desync workaround we use for
 *     Easytech live streams — local file playback doesn't need it
 *   - fflags = discardcorrupt: the stream sometimes stalls mid-file (the OEM
 *     app has the same issue); this lets the decoder resynchronise instead of
 *     failing outright
 *   - mediacodec=0 (software H.264 decode): same reason as IjkVideoPlayerOverlay
 *     — Qualcomm's OMX hardware decoder rejects SurfaceTexture-backed Surfaces
 *     and silently kills the video thread, leaving audio playing over a black
 *     frame. Software decode handles any Surface and works on this device.
 */
@Composable
fun AllwinnerFilePlayer(
    fileUri: String,
    modifier: Modifier = Modifier,
    onFrame: ((Bitmap) -> Unit)? = null,
) {

    val player = remember(fileUri) {
        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", "100000")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",       "524288")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags",          "discardcorrupt")
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC,  "skip_loop_filter", 48L)
            // Software decoding for the same reason as the HiSilicon-family
            // playback overlay — see IjkVideoPlayerOverlay in MediaScreen.kt.
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",            0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared",      1L)
        }
    }

    val currentOnFrame by rememberUpdatedState(onFrame)
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    val playbackStarted = remember { mutableStateOf(false) }

    DisposableEffect(fileUri) {
        onDispose {
            Log.d(TAG, "dispose — releasing IjkPlayer")
            try { player.stop() } catch (_: Exception) {}
            player.release()
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, w: Int, h: Int,
                        ) {
                            Log.d(TAG, "surfaceTextureAvailable ${w}x$h — preparing $fileUri")
                            try {
                                player.setSurface(Surface(surface))
                                player.dataSource = fileUri
                                player.setOnPreparedListener { mp ->
                                    mp.start()
                                    playbackStarted.value = true
                                }
                                player.setOnErrorListener { _, what, extra ->
                                    Log.e(TAG, "IjkPlayer error what=$what extra=$extra")
                                    false
                                }
                                player.setOnInfoListener { _, what, extra ->
                                    Log.d(TAG, "IjkPlayer info what=$what extra=$extra")
                                    false
                                }
                                player.prepareAsync()
                                textureViewRef.value = tv
                            } catch (e: Exception) {
                                Log.e(TAG, "IjkPlayer setup failed: ${e.message}", e)
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, w: Int, h: Int,
                        ) {}

                        override fun onSurfaceTextureDestroyed(
                            surface: SurfaceTexture,
                        ): Boolean {
                            Log.d(TAG, "surfaceTextureDestroyed — stopping IjkPlayer")
                            try { player.stop() } catch (_: Exception) {}
                            player.setSurface(null)
                            textureViewRef.value = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── AI bitmap polling ─────────────────────────────────────────────
        // Mirrors IjkVideoPlayerOverlay / RtspPlayer: 100 ms tick, copy
        // bitmap out of the TextureView, hand to the pipeline, recycle.
        // Loop only runs when `onFrame` is non-null and playback has begun.
        val aiActive = onFrame != null && playbackStarted.value
        val pollKey = textureViewRef.value
        LaunchedEffect(aiActive, pollKey) {
            if (!aiActive || pollKey == null) return@LaunchedEffect
            Log.d(TAG, "AI poll loop start")
            try {
                while (true) {
                    delay(100)
                    val tv = textureViewRef.value ?: break
                    if (!tv.isAvailable) continue
                    val bmp = try { tv.bitmap } catch (t: Throwable) {
                        Log.w(TAG, "getBitmap failed: ${t.message}")
                        null
                    } ?: continue
                    try { currentOnFrame?.invoke(bmp) } catch (t: Throwable) {
                        Log.w(TAG, "onFrame threw: ${t.message}")
                    } finally {
                        bmp.recycle()
                    }
                }
            } finally {
                Log.d(TAG, "AI poll loop end")
            }
        }
    }
}
