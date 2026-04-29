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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.setValue
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
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay
import com.example.trafykamerasikotlin.ui.viewmodel.CaptureKind
import com.example.trafykamerasikotlin.ui.viewmodel.CaptureState
import com.example.trafykamerasikotlin.ui.viewmodel.LiveUiState
import com.example.trafykamerasikotlin.ui.viewmodel.LiveViewModel
import com.example.trafykamerasikotlin.ui.viewmodel.LiveVisionOverlayViewModel
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

    // AI overlay (Chunks 1–5 vision pipeline). Defaults to ON; user choice
    // persists across launches via AiOverlayPreferences (SharedPreferences).
    // Same flow is shared with every other on-screen toggle so toggling here
    // is reflected on Media/playback screens immediately.
    val (aiOverlayEnabled, setAiOverlay) =
        com.example.trafykamerasikotlin.data.settings.rememberAiOverlayPreference()
    val aiViewModel: LiveVisionOverlayViewModel = viewModel()
    val aiScene by aiViewModel.scene.collectAsStateWithLifecycle()

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
            is LiveUiState.AllwinnerLive    -> AllwinnerLiveView(
                state           = state,
                aiOverlayOn     = aiOverlayEnabled,
                onToggleAi      = { setAiOverlay(!aiOverlayEnabled) },
                aiViewModel     = aiViewModel,
                aiScene         = aiScene,
                isLandscape     = isLandscape,
                onSwitchCamera  = { viewModel.switchAllwinnerCamera(it) },
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
                            // Both code paths now feed the vision pipeline. GP via
                            // direct decoded-bitmap tap (fast, free); HiSilicon
                            // family via TextureView.getBitmap() polling at ~10 fps
                            // (Chunk H2). When AI is off, both paths skip the tap
                            // entirely so non-AI users pay nothing.
                            val aiActive = aiOverlayEnabled
                            if (state.useMjpeg) {
                                MjpegLivePlayer(
                                    rtspUrl = state.rtspUrl,
                                    onFrame = if (aiActive) aiViewModel::onFrame else null,
                                    overlayScene = if (aiActive) aiScene else null,
                                )
                            } else {
                                RtspPlayer(
                                    rtspUrl = state.rtspUrl,
                                    onFrame = if (aiActive) aiViewModel::onFrame else null,
                                    overlayScene = if (aiActive) aiScene else null,
                                )
                            }
                        }
                        // AI overlay toggle — top-right, shown for every chipset
                        // that has a video player (MJPEG GP, H.264 HiSilicon
                        // family). Allwinner uses a separate capture UI and is
                        // excluded by the surrounding `when (uiState)` branch.
                        AiOverlayToggle(
                            enabled  = aiOverlayEnabled,
                            onToggle = { setAiOverlay(!aiOverlayEnabled) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                        )
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
 * Live-tab view for Allwinner V853 — uses the rtp2p UDP transport
 * (reverse-engineered from a CloudSpirit PCAP). The MediaViewModel's
 * AllwinnerLiveRepository drains UDP into a temp .ts file and hands its URI
 * here once buffered; we play it through the existing [AllwinnerFilePlayer]
 * (which also wires the AI overlay tap via TextureView.getBitmap polling).
 *
 * Buffering / failure UI mirrors the GP and HiSilicon paths: spinner while
 * waiting, error message + retry hint if the watchdog times out.
 */
@Composable
private fun AllwinnerLiveView(
    state: LiveUiState.AllwinnerLive,
    aiOverlayOn: Boolean,
    onToggleAi: () -> Unit,
    aiViewModel: LiveVisionOverlayViewModel,
    aiScene: com.example.trafykamerasikotlin.data.vision.TrackedScene?,
    isLandscape: Boolean,
    onSwitchCamera: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier         = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier            = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint               = ColorTextSecondary,
                        modifier           = Modifier.size(48.dp),
                    )
                    Text(
                        text      = "Canlı yayın başlatılamadı",
                        style     = MaterialTheme.typography.titleMedium,
                        color     = ColorTextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text      = "Kamerayla bağlantı kurulamadı. Wi-Fi’yi kontrol edip tekrar deneyin.",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = ColorTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                state.localFileUri == null || state.buffering -> {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = com.example.trafykamerasikotlin.ui.theme.ColorPrimary,
                    )
                }
                else -> {
                    // We have a buffered URI — render the live stream through
                    // AllwinnerFilePlayer (TextureView + IjkPlayer + AI tap).
                    androidx.compose.runtime.key(state.camid) {
                        com.example.trafykamerasikotlin.ui.components.AllwinnerFilePlayer(
                            fileUri = state.localFileUri,
                            onFrame = if (aiOverlayOn) aiViewModel::onFrame else null,
                        )
                    }
                    // Bounding-box overlay when AI is on (sized to the same
                    // 16:9 letterbox as the player).
                    val overlayScene = if (aiOverlayOn) aiScene else null
                    val overlaySrcSize = overlayScene?.sourceFrameSize
                        ?: android.util.Size(0, 0)
                    com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay(
                        scene      = overlayScene,
                        sourceSize = overlaySrcSize,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                }
            }
            // AI overlay toggle — top-right, same UX as other chipsets.
            if (state.localFileUri != null && !state.failed) {
                AiOverlayToggle(
                    enabled  = aiOverlayOn,
                    onToggle = onToggleAi,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }
        // Front / back camera switcher (Allwinner has both). Hidden in
        // landscape so the video owns the chrome-free frame.
        if (!isLandscape) {
            CameraTabBar(
                cameras          = listOf(
                    stringResource(R.string.media_tab_front),
                    stringResource(R.string.media_tab_back),
                ),
                selectedCamera   = state.camid,
                onCameraSelected = onSwitchCamera,
            )
        }
    }
}

/**
 * Standalone Live-tab view for Allwinner V853.
 *
 * Shows a "live stream coming soon" placeholder for now. The remote-capture wiring
 * (LiveViewModel.capturePhoto/captureVideo + AllwinnerCaptureRepository) is kept
 * intact but not surfaced here because the user wants capture to trigger only
 * over mobile-internet (cloud path), not the dashcam's Wi-Fi. When the cloud
 * implementation lands, the buttons come back here guarded by a connectivity check.
 */
@Suppress("UNUSED_PARAMETER", "unused")
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
private fun RtspPlayer(
    rtspUrl: String,
    onFrame: ((android.graphics.Bitmap) -> Unit)? = null,
    overlayScene: com.example.trafykamerasikotlin.data.vision.TrackedScene? = null,
) {

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

    // Use rememberUpdatedState so the captured `onFrame` reference inside the
    // polling loop and the SurfaceTextureListener always sees the latest
    // lambda — toggling AI doesn't have to bounce the player.
    val currentOnFrame by androidx.compose.runtime.rememberUpdatedState(onFrame)
    val textureViewRef = remember { mutableStateOf<android.view.TextureView?>(null) }
    val playbackStarted = remember { mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .aspectRatio(16f / 9f),
    ) {
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).also { tv ->
                    tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture, w: Int, h: Int,
                        ) {
                            Log.d(TAG, "RtspPlayer surfaceTextureAvailable ${w}x$h — $rtspUrl")
                            try {
                                ijkPlayer.setSurface(android.view.Surface(surface))
                                ijkPlayer.dataSource = rtspUrl
                                ijkPlayer.setOnPreparedListener { mp ->
                                    Log.d(TAG, "IjkPlayer prepared — starting playback")
                                    mp.start()
                                    playbackStarted.value = true
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
                                textureViewRef.value = tv
                            } catch (e: Exception) {
                                Log.e(TAG, "IjkPlayer setup failed: ${e.message}")
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture, w: Int, h: Int,
                        ) {}

                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture,
                        ): Boolean {
                            Log.d(TAG, "RtspPlayer surfaceTextureDestroyed")
                            ijkPlayer.stop()
                            ijkPlayer.setSurface(null)
                            textureViewRef.value = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture,
                        ) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── AI bitmap polling ─────────────────────────────────────────────
        // Mirrors the HiSilicon playback overlay (IjkVideoPlayerOverlay):
        // poll TextureView.bitmap at ~10 fps when AI is on, recycle after
        // submit (the pipeline copies internally before parking on its
        // inference channel). Loop cancels when onFrame goes null
        // (toggle off) or when the TextureView reference clears.
        val aiActive = onFrame != null && playbackStarted.value
        val pollKey  = textureViewRef.value
        LaunchedEffect(aiActive, pollKey) {
            if (!aiActive || pollKey == null) return@LaunchedEffect
            Log.d(TAG, "RtspPlayer: AI poll loop start")
            try {
                while (true) {
                    kotlinx.coroutines.delay(100)
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
                Log.d(TAG, "RtspPlayer: AI poll loop end")
            }
        }

        // Vision overlay — always composed (handles null scene internally),
        // sized to the same letterboxed video region so detection coords
        // align with the rendered frame. Same pattern as MjpegLivePlayer.
        val srcSize = overlayScene?.sourceFrameSize ?: android.util.Size(0, 0)
        BoundingBoxOverlay(
            scene = overlayScene,
            sourceSize = srcSize,
            modifier = Modifier.fillMaxSize(),
        )
    }
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
private fun MjpegLivePlayer(
    rtspUrl: String,
    /** Optional per-frame tap — used by the AI overlay to feed the vision pipeline. */
    onFrame: ((android.graphics.Bitmap) -> Unit)? = null,
    /** If non-null the AI bounding-box overlay is drawn on top of the player. */
    overlayScene: com.example.trafykamerasikotlin.data.vision.TrackedScene? = null,
) {

    val bufferingState = remember { mutableStateOf(true) }
    val playerRef      = remember { mutableStateOf<MjpegRtspPlayer?>(null) }
    val isLandscape    = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playerSize     = Modifier
        .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
        .aspectRatio(16f / 9f)

    // Canonical "keep the latest callback available to a long-lived View
    // factory" pattern. `rememberUpdatedState` gives us a State<T> whose
    // value is updated on every recomposition but whose identity stays
    // stable, so the factory lambda that captures it sees the latest
    // `onFrame` without us re-creating the player on every toggle.
    val currentOnFrame by androidx.compose.runtime.rememberUpdatedState(onFrame)

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
                            // Read via rememberUpdatedState so the toggle flips
                            // without bouncing the player.
                            player.onFrame = { bmp -> currentOnFrame?.invoke(bmp) }
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

        // Vision overlay — always composed, even when the scene is null.
        // Keeping it in the tree unconditionally avoids a subtle z-ordering
        // glitch where the overlay's first null → non-null transition
        // wouldn't surface above the SurfaceView until some other event
        // forced a re-layout (e.g. toggling the AI switch).
        val srcSize = overlayScene?.sourceFrameSize ?: android.util.Size(0, 0)
        BoundingBoxOverlay(
            scene = overlayScene,
            sourceSize = srcSize,
            modifier = Modifier.fillMaxSize(),
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

// ── AI overlay toggle (dev-only; moves to Settings for release) ────────────

/**
 * Small circular toggle rendered on top of the live video. Shows the
 * current state at a glance:
 *   - filled primary color + visible icon = overlay ON
 *   - translucent surface + icon = overlay OFF
 * When the overlay is ON we also render a one-line disclaimer so the
 * user knows plate reads are informational, not ADAS warnings.
 */
@Composable
private fun AiOverlayToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (enabled) ColorPrimary else ColorSurface.copy(alpha = 0.6f),
                    shape = CircleShape,
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.AutoAwesome,
                contentDescription = stringResource(R.string.live_ai_overlay_toggle_cd),
                tint               = if (enabled) Color.White else ColorTextSecondary,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

