package com.example.trafykamerasikotlin.ui.components

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
 * Tuning rationale (different from the live-RTSP config in LiveScreen):
 *   - probesize/analyzeduration low: the file starts with TS packets, no need
 *     to scan for codec params
 *   - packet-buffering enabled: avoids the audio-desync workaround we use for
 *     Easytech live streams — local file playback doesn't need it
 *   - fflags = discardcorrupt: the stream sometimes stalls mid-file (the OEM
 *     app has the same issue); this lets the decoder resynchronise instead of
 *     failing outright
 */
@Composable
fun AllwinnerFilePlayer(fileUri: String, modifier: Modifier = Modifier) {

    val player = remember(fileUri) {
        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", "100000")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",       "524288")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags",          "discardcorrupt")
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC,  "skip_loop_filter", 48L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",            1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared",      1L)
        }
    }

    DisposableEffect(fileUri) {
        onDispose {
            Log.d(TAG, "dispose — releasing IjkPlayer")
            try { player.stop() } catch (_: Exception) {}
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d(TAG, "surfaceCreated — preparing $fileUri")
                        try {
                            player.setDisplay(holder)
                            player.dataSource = fileUri
                            player.setOnPreparedListener { mp -> mp.start() }
                            player.setOnErrorListener { _, what, extra ->
                                Log.e(TAG, "IjkPlayer error what=$what extra=$extra")
                                false
                            }
                            player.setOnInfoListener { _, what, extra ->
                                Log.d(TAG, "IjkPlayer info what=$what extra=$extra")
                                false
                            }
                            player.prepareAsync()
                        } catch (e: Exception) {
                            Log.e(TAG, "IjkPlayer setup failed: ${e.message}", e)
                        }
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d(TAG, "surfaceDestroyed — stopping IjkPlayer")
                        try { player.stop() } catch (_: Exception) {}
                        player.setDisplay(null)
                    }
                })
            }
        },
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
}
