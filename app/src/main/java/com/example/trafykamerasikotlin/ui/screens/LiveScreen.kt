package com.example.trafykamerasikotlin.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Network
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorNavBar
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession
import com.example.trafykamerasikotlin.data.media.MjpegRtspPlayer
import com.example.trafykamerasikotlin.ui.viewmodel.CaptureKind
import com.example.trafykamerasikotlin.ui.viewmodel.CaptureState
import com.example.trafykamerasikotlin.ui.viewmodel.LiveUiState
import com.example.trafykamerasikotlin.ui.viewmodel.LiveViewModel
import tv.danmaku.ijk.media.player.IjkMediaPlayer

private const val TAG = "Trafy.LiveScreen"

@Composable
fun LiveScreen(
    device: DeviceInfo?,
    network: Network?,
    viewModel: LiveViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState        by viewModel.uiState.collectAsStateWithLifecycle()
    val captureState   by viewModel.captureState.collectAsStateWithLifecycle()
    val snackbarHost   = remember { SnackbarHostState() }
    val ctx            = LocalContext.current
    val isLandscape    = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // While the Live tab is visible, let the activity follow the phone's sensor
    // orientation even if system auto-rotate is locked — so tilting the phone
    // rotates the stream into landscape. Restored when the user navigates away.
    DisposableEffect(Unit) {
        val activity = ctx as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(device) {
        if (device != null) viewModel.startStream(device, network)
    }

    LaunchedEffect(Unit) {
        viewModel.captureMessages.collect { msg -> snackbarHost.showSnackbar(msg) }
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
            is LiveUiState.NotConnected     -> NotConnectedPlaceholder()
            is LiveUiState.Preparing        -> PreparingPlaceholder()
            is LiveUiState.AllwinnerCapture -> AllwinnerCaptureView(
                captureState = captureState,
                onPhoto      = { viewModel.capturePhoto() },
                onVideo      = { viewModel.captureVideo() },
            )
            is LiveUiState.Playing -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Video player — centered, width-constrained, 16:9 aspect ratio.
                    // key(selectedCamera) forces a full player tear-down + rebuild when the
                    // user switches cameras on chipsets that share one RTSP URL (HiDVR).
                    Box(
                        modifier         = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.runtime.key(state.selectedCamera) {
                            if (state.useMjpeg) {
                                MjpegLivePlayer(rtspUrl = state.rtspUrl)
                            } else {
                                RtspPlayer(rtspUrl = state.rtspUrl)
                            }
                        }
                    }
                    // Camera switcher tab bar — shown only when multiple cameras exist
                    // AND the user is in portrait. Landscape is a chrome-free view.
                    if (!isLandscape && state.cameras.size > 1) {
                        CameraTabBar(
                            cameras          = state.cameras,
                            selectedCamera   = state.selectedCamera,
                            onCameraSelected = { viewModel.switchCamera(it) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier  = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            snackbar  = { data ->
                Snackbar(
                    containerColor = ColorSurface,
                    contentColor   = ColorTextPrimary,
                ) { Text(data.visuals.message) }
            }
        )
    }
}

// ── Allwinner remote-capture view ───────────────────────────────────────────

/**
 * Standalone Live-tab view for Allwinner V853.
 *
 * Shows a "live stream coming soon" placeholder for now. The remote-capture wiring
 * (LiveViewModel.capturePhoto/captureVideo + AllwinnerCaptureRepository) is kept
 * intact but not surfaced here because the user wants capture to trigger only
 * over mobile-internet (cloud path), not the dashcam's Wi-Fi. When the cloud
 * implementation lands, the buttons come back here guarded by a connectivity check.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun AllwinnerCaptureView(
    captureState: CaptureState,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(60.dp))
        Icon(
            imageVector        = Icons.Filled.Videocam,
            contentDescription = null,
            tint               = ColorTextSecondary,
            modifier           = Modifier.size(64.dp),
        )
        Text(
            text      = stringResource(R.string.live_coming_soon_title),
            style     = MaterialTheme.typography.headlineSmall,
            color     = ColorTextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text      = stringResource(R.string.live_coming_soon_body),
            style     = MaterialTheme.typography.bodyMedium,
            color     = ColorTextSecondary,
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun CaptureButton(
    icon: ImageVector,
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier         = Modifier
                .size(96.dp)
                .background(
                    color = if (enabled) ColorPrimary else ColorPrimary.copy(alpha = 0.35f),
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color       = Color.White,
                    strokeWidth = 3.dp,
                    modifier    = Modifier.size(42.dp),
                )
            } else {
                Icon(
                    imageVector        = icon,
                    contentDescription = label,
                    tint               = Color.White,
                    modifier           = Modifier.size(42.dp),
                )
            }
        }
        Text(
            text  = label,
            style = MaterialTheme.typography.labelLarge,
            color = ColorTextPrimary,
        )
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
            contentDescription = stringResource(R.string.live_view_cd),
            tint               = ColorTextSecondary,
            modifier           = Modifier.size(64.dp)
        )
        Text(
            text  = stringResource(R.string.live_not_connected_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ColorTextPrimary
        )
        Text(
            text  = stringResource(R.string.live_not_connected_body),
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
            text  = stringResource(R.string.live_preparing),
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

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        // Portrait: fill width, letterbox top/bottom. Landscape: fill height so the
        // video grows into the longer axis instead of being clamped. 16:9 aspect
        // preserved in both — no stretching of the 1920×1080 dashcam image.
        modifier = Modifier
            .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .aspectRatio(16f / 9f)
    )
}

// ── MJPEG RTSP player (GeneralPlus) ───────────────────────────────────────

/**
 * Plays an MJPEG-over-RTP live stream using [MjpegRtspPlayer].
 *
 * GeneralPlus cameras stream MJPEG (RTP payload type 26, JPEG/90000) which
 * IjkPlayer cannot decode (its FFmpeg build omits the MJPEG codec).
 * This composable reuses the same custom player that handles file playback.
 */
@Composable
private fun MjpegLivePlayer(rtspUrl: String) {

    val bufferingState = remember { mutableStateOf(true) }
    val playerRef      = remember { mutableStateOf<MjpegRtspPlayer?>(null) }
    val isLandscape    = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playerSize     = Modifier
        .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
        .aspectRatio(16f / 9f)

    Box(
        modifier         = playerSize,
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.d(TAG, "MjpegLivePlayer surfaceCreated — $rtspUrl")
                            val player = MjpegRtspPlayer(
                                surface = holder.surface,
                                network = GeneralplusSession.getBoundNetwork(),
                            )
                            player.onFirstFrame = { bufferingState.value = false }
                            playerRef.value = player
                            player.start(rtspUrl)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder, format: Int, w: Int, h: Int,
                        ) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.d(TAG, "MjpegLivePlayer surfaceDestroyed — stopping")
                            playerRef.value?.stop()
                            playerRef.value = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering spinner — shown until the first JPEG frame is decoded
        if (bufferingState.value) {
            CircularProgressIndicator(color = ColorPrimary)
        }
    }

    DisposableEffect(rtspUrl) {
        onDispose {
            Log.d(TAG, "MjpegLivePlayer dispose — stopping")
            playerRef.value?.stop()
            playerRef.value = null
        }
    }
}
