package com.example.trafykamerasikotlin.ui.screens

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorNavBar
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.LiveUiState
import com.example.trafykamerasikotlin.ui.viewmodel.LiveViewModel
import tv.danmaku.ijk.media.player.IjkMediaPlayer

private const val TAG = "Trafy.LiveScreen"

@Composable
fun LiveScreen(
    device: DeviceInfo?,
    viewModel: LiveViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(device) {
        if (device != null) viewModel.startStream(device)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onLeave() }
    }

    Box(
        modifier         = modifier
            .fillMaxSize()
            .background(ColorBackground),
        contentAlignment = Alignment.Center
    ) {
        when (val state = uiState) {
            is LiveUiState.NotConnected -> NotConnectedPlaceholder()
            is LiveUiState.Preparing    -> PreparingPlaceholder()
            is LiveUiState.Playing      -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Video player — centered, width-constrained, 16:9 aspect ratio
                    Box(
                        modifier         = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        RtspPlayer(rtspUrl = state.rtspUrl)
                    }
                    // Camera switcher tab bar — shown only for multi-camera Easytech devices
                    if (state.cameras.size > 1) {
                        CameraTabBar(
                            cameras          = state.cameras,
                            selectedCamera   = state.selectedCamera,
                            onCameraSelected = { viewModel.switchCamera(it) },
                        )
                    }
                }
            }
        }
    }
}

// ── Placeholder composables ────────────────────────────────────────────────

@Composable
private fun NotConnectedPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector        = Icons.Filled.Videocam,
            contentDescription = "Live view",
            tint               = ColorTextSecondary,
            modifier           = Modifier.size(64.dp)
        )
        Text(
            text  = "Live View",
            style = MaterialTheme.typography.headlineMedium,
            color = ColorTextPrimary
        )
        Text(
            text  = "Connect to your dashcam to start streaming",
            style = MaterialTheme.typography.bodyMedium,
            color = ColorTextSecondary
        )
    }
}

@Composable
private fun PreparingPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = ColorPrimary)
        Text(
            text  = "Preparing stream…",
            style = MaterialTheme.typography.bodyMedium,
            color = ColorTextSecondary
        )
    }
}

// ── Camera tab bar ─────────────────────────────────────────────────────────

/**
 * Horizontal tab bar for switching between cameras on multi-camera Easytech devices.
 * Each tab index corresponds directly to the switchcam parameter value (0, 1, 2).
 */
@Composable
private fun CameraTabBar(
    cameras          : List<String>,
    selectedCamera   : Int,
    onCameraSelected : (Int) -> Unit,
) {
    val safeIndex = selectedCamera.coerceIn(0, cameras.lastIndex)
    Column(modifier = Modifier.fillMaxWidth().background(ColorBackground)) {
        HorizontalDivider(color = ColorDivider, thickness = 0.5.dp)
        TabRow(
            selectedTabIndex = safeIndex,
            containerColor   = ColorNavBar,
            contentColor     = ColorPrimary,
            indicator        = { tabPositions ->
                SecondaryIndicator(
                    modifier  = Modifier.tabIndicatorOffset(tabPositions[safeIndex]),
                    color     = ColorPrimary,
                )
            },
        ) {
            cameras.forEachIndexed { index, label ->
                Tab(
                    selected = safeIndex == index,
                    onClick  = { onCameraSelected(index) },
                    text     = {
                        Text(
                            text  = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (safeIndex == index) ColorPrimary else ColorTextSecondary,
                        )
                    },
                )
            }
        }
    }
}

// ── RTSP player ────────────────────────────────────────────────────────────

/**
 * Plays an RTSP stream using IjkMediaPlayer (FFmpeg-based).
 *
 * ExoPlayer, Android MediaPlayer, and libVLC all fail against HiDVR / Easytech
 * dashcam RTSP because they abort when the camera returns 400 to the RTSP OPTIONS
 * request.  IjkPlayer uses FFmpeg's libavformat RTSP client which ignores the
 * OPTIONS failure and continues to DESCRIBE, exactly as the reference GoLook app does.
 *
 * Key FFmpeg options:
 *   rtsp_transport = tcp   — RTP data over TCP (avoids UDP on dashcam WiFi)
 *   stimeout       = 5s    — socket timeout so we don't hang indefinitely
 */
@Composable
private fun RtspPlayer(rtspUrl: String) {

    val ijkPlayer = remember(rtspUrl) {
        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkMediaPlayer().apply {
            // FFmpeg format options
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "stimeout",       "5000000")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration","100000")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",      "1048576")
            // Disable FFmpeg input buffering — live RTSP streams should start immediately
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags",         "nobuffer")
            // Codec options
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
            // Player options
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",            1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared",      1L)
            // Disable packet buffering: IjkPlayer normally waits until the packet
            // buffer reaches its watermark before emitting BUFFERING_END and starting
            // rendering.  Easytech's AAC audio stream has an invalid sampling-rate
            // index (13) which breaks audio-codec initialisation; the player then
            // tries to sync audio/video and the buffer watermark is never reached,
            // leaving the stream frozen in BUFFERING_START state indefinitely.
            // Setting packet-buffering=0 bypasses that watermark and renders video
            // frames as soon as they arrive from the decoder.
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
        }
    }

    DisposableEffect(rtspUrl) {
        onDispose {
            Log.d(TAG, "RtspPlayer dispose — releasing IjkPlayer")
            ijkPlayer.stop()
            ijkPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                sv.holder.addCallback(object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d(TAG, "surfaceCreated — starting IjkPlayer for $rtspUrl")
                        try {
                            ijkPlayer.setDisplay(holder)
                            ijkPlayer.dataSource = rtspUrl
                            ijkPlayer.setOnPreparedListener { mp ->
                                Log.d(TAG, "IjkPlayer prepared — starting playback")
                                mp.start()
                            }
                            ijkPlayer.setOnErrorListener { _, what, extra ->
                                Log.e(TAG, "IjkPlayer error what=$what extra=$extra")
                                false
                            }
                            ijkPlayer.setOnInfoListener { _, what, extra ->
                                Log.d(TAG, "IjkPlayer info what=$what extra=$extra")
                                false
                            }
                            ijkPlayer.prepareAsync()
                        } catch (e: Exception) {
                            Log.e(TAG, "IjkPlayer setup failed: ${e.message}")
                        }
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, w: Int, h: Int,
                    ) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d(TAG, "surfaceDestroyed — stopping IjkPlayer")
                        ijkPlayer.stop()
                        ijkPlayer.setDisplay(null)
                    }
                })
            }
        },
        // Width fills the container; height is derived from the 16:9 ratio so the
        // landscape dashcam video is never stretched to fit a portrait screen.
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    )
}
