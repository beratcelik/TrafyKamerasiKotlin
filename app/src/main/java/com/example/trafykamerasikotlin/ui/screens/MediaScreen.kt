package com.example.trafykamerasikotlin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.DownloadState
import com.example.trafykamerasikotlin.ui.viewmodel.MediaUiState
import com.example.trafykamerasikotlin.ui.viewmodel.MediaUserMessage
import com.example.trafykamerasikotlin.ui.viewmodel.MediaViewModel
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.viewinterop.AndroidView
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession
import com.example.trafykamerasikotlin.data.media.AllwinnerSdInfo
import com.example.trafykamerasikotlin.data.media.MjpegRtspPlayer
import com.example.trafykamerasikotlin.ui.components.AllwinnerFilePlayer
import com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay
import com.example.trafykamerasikotlin.ui.viewmodel.LiveVisionOverlayViewModel
import tv.danmaku.ijk.media.player.IjkMediaPlayer

@Composable
fun MediaScreen(
    device: DeviceInfo?,
    network: Network?,
    modifier: Modifier = Modifier,
    viewModel: MediaViewModel,
) {
    val uiState              by viewModel.uiState.collectAsStateWithLifecycle()
    val downloading          by viewModel.downloading.collectAsStateWithLifecycle()
    val downloadProgress     by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val playbackUri          by viewModel.playbackUri.collectAsStateWithLifecycle()
    val preparingPlay        by viewModel.isPreparingPlayback.collectAsStateWithLifecycle()
    val allwinnerPlaybackUri by viewModel.allwinnerPlaybackUri.collectAsStateWithLifecycle()
    val snackbarHostState    = remember { SnackbarHostState() }

    val context = LocalContext.current

    LaunchedEffect(device) {
        if (device != null) viewModel.load(device)
        else viewModel.onLeave() // ensure playback exits if user disconnected while on tab
    }

    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            val text = when (msg) {
                MediaUserMessage.PlaybackFailed    -> context.getString(R.string.media_error_playback_failed)
                MediaUserMessage.DownloadFailed    -> context.getString(R.string.media_error_download_failed)
                MediaUserMessage.BusyPlayback      -> context.getString(R.string.media_error_busy_playback)
                is MediaUserMessage.DownloadComplete ->
                    context.getString(R.string.media_download_complete_fmt, msg.filename)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // App backgrounding: exit playback so the cam can keep recording while
    // we're not visible. Foregrounding: re-fetch (only fires when we'd
    // actually been backgrounded, not on initial compose — initial load is
    // already covered by LaunchedEffect(device) above, and a duplicate
    // load() call would race the LaunchedEffect's call and cancel it).
    // Compose dispose still calls onLeave to handle tab navigation.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP  -> viewModel.onBackground()
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onLeave()
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    // In-app overlay URL for HiSilicon/Easytech/etc. HTTP file playback. GeneralPlus
    // and Allwinner have their own VM-driven flows (playbackUri / allwinnerPlaybackUri).
    var inAppVideoUrl by remember { mutableStateOf<String?>(null) }
    // Full-screen photo viewer URL.
    var inAppPhotoUrl by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Initial-pass pointer listener: every touch on the Media tab
            // counts as activity, resetting the auto-exit-playback watchdog.
            // PointerEventPass.Initial means we observe events before any
            // child consumes them, so we don't interfere with clicks/scrolls.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        viewModel.reportInteraction()
                    }
                }
            }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        val aiOverlayEnabled by viewModel.aiOverlayEnabled.collectAsStateWithLifecycle()

        Text(
            text     = stringResource(R.string.media_title),
            style    = MaterialTheme.typography.headlineMedium,
            color    = ColorTextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        (uiState as? MediaUiState.Loaded)?.sdInfo?.let { sd -> SdUsageRow(sd) }

        when (val state = uiState) {
            is MediaUiState.NotConnected -> NotConnectedMediaContent()
            is MediaUiState.Loading      -> LoadingMediaContent()
            is MediaUiState.Error        -> ErrorMediaContent(onRetry = { viewModel.reload() })
            is MediaUiState.Loaded -> {
                // Group videos by camera channel (front/back/inside) from filename suffix.
                // Only tabs for cameras that actually have footage are shown.
                // Photos tab is hidden when no photos exist — Trafy Uno never
                // emits photos so the tab would be perpetually empty there.
                val videosByCamera = groupVideosByCamera(state.videos)
                val cameraTabs     = listOf("Front", "Back", "Inside")
                    .filter { videosByCamera.containsKey(it) }
                val tabs           = buildList {
                    addAll(cameraTabs)
                    if (state.photos.isNotEmpty()) add("Photos")
                }

                if (tabs.isEmpty()) {
                    EmptyMediaContent(isPhoto = false)
                } else {
                // Clamp in case a reload returns fewer tabs than current index
                val safeTab        = selectedTab.coerceIn(0, tabs.lastIndex)

                TabRow(
                    selectedTabIndex = safeTab,
                    containerColor   = ColorBackground,
                    contentColor     = ColorPrimary,
                    indicator        = { positions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(positions[safeTab]),
                            color    = ColorPrimary
                        )
                    },
                    divider = {
                        Box(modifier = Modifier.fillMaxWidth().background(ColorDivider))
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected               = safeTab == index,
                            onClick                = { selectedTab = index },
                            selectedContentColor   = ColorPrimary,
                            unselectedContentColor = ColorTextSecondary,
                            text = {
                                Text(text = cameraTabLabel(title), style = MaterialTheme.typography.titleMedium)
                            }
                        )
                    }
                }

                val selectedTitle = tabs[safeTab]
                val isPhotoTab = selectedTitle == "Photos"
                val files      = if (isPhotoTab) state.photos
                                 else videosByCamera[selectedTitle] ?: emptyList()

                if (files.isEmpty()) {
                    EmptyMediaContent(isPhoto = isPhotoTab)
                } else {
                    MediaGrid(
                        files             = files,
                        device            = device,
                        downloading       = downloading,
                        downloadProgress  = downloadProgress,
                        aiOverlayOn       = aiOverlayEnabled,
                        onToggleAi        = { viewModel.setAiOverlay(it) },
                        onPlay            = { viewModel.playFile(it) },
                        onPlayAllwinner   = { viewModel.startAllwinnerStream(it) },
                        onPlayInApp       = { url -> inAppVideoUrl = url },
                        onViewPhoto       = { url -> inAppPhotoUrl = url },
                        // Route through the burn-in flow when the AI toggle is on
                        // AND the camera supports it (GP via AVI/MJPEG, HiSilicon
                        // family via HTTP MP4). Allwinner and toggle-off both
                        // fall through to the plain download.
                        onDownload        = { f ->
                            val aiBurnIn = aiOverlayEnabled && device != null &&
                                device.protocol != ChipsetProtocol.ALLWINNER_V853
                            if (aiBurnIn) {
                                viewModel.downloadWithOverlay(f)
                            } else {
                                viewModel.download(f)
                            }
                        },
                        onCancelDownload  = { viewModel.cancelDownload(it) },
                        onDelete          = { viewModel.delete(it) }
                    )
                }
                }
            }
        }
    }

    if (preparingPlay) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = ColorPrimary)
        }
    }

    playbackUri?.let { uri ->
        VideoPlayerOverlay(url = uri, onDismiss = { viewModel.clearPlaybackUri() })
    }

    allwinnerPlaybackUri?.let { uri ->
        AllwinnerPlaybackOverlay(url = uri, onDismiss = { viewModel.stopAllwinnerStream() })
    }

    inAppVideoUrl?.let { url ->
        IjkVideoPlayerOverlay(
            url       = url,
            network   = network,
            onDismiss = { inAppVideoUrl = null },
        )
    }

    inAppPhotoUrl?.let { url ->
        PhotoViewerOverlay(
            url       = url,
            network   = network,
            onDismiss = { inAppPhotoUrl = null },
        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier  = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        snackbar  = { data ->
            Snackbar(
                containerColor = ColorSurface,
                contentColor   = ColorTextPrimary,
            ) { Text(data.visuals.message) }
        }
    )
    } // Box
}

@Composable
private fun SdUsageRow(sd: AllwinnerSdInfo) {
    val usedBytes = (sd.totalBytes - sd.freeBytes).coerceAtLeast(0L)
    val usedGb  = usedBytes / 1_000_000_000f
    val totalGb = sd.totalBytes / 1_000_000_000f
    Text(
        text     = stringResource(R.string.media_sd_usage_fmt, usedGb, totalGb),
        style    = MaterialTheme.typography.labelMedium,
        color    = ColorTextSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun AllwinnerPlaybackOverlay(url: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    // AI overlay state — single shared, persistent preference (default ON).
    // Backed by AiOverlayPreferences/SharedPreferences; toggling here is
    // observed by Live + every other playback overlay.
    val (aiOverlayEnabled, setAiOverlay) =
        com.example.trafykamerasikotlin.data.settings.rememberAiOverlayPreference()
    val aiViewModel: LiveVisionOverlayViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val aiScene by aiViewModel.scene.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AllwinnerFilePlayer(
            fileUri = url,
            modifier = Modifier.align(Alignment.Center),
            onFrame = if (aiOverlayEnabled) aiViewModel::onFrame else null,
        )

        // Bounding-box overlay sized to the same 16:9 letterbox region as
        // the player so detection coords align with the rendered frame.
        val overlayScene = if (aiOverlayEnabled) aiScene else null
        val overlaySourceSize = overlayScene?.let {
            android.util.Size(it.sourceFrameSize.width, it.sourceFrameSize.height)
        } ?: android.util.Size(0, 0)
        BoundingBoxOverlay(
            scene = overlayScene,
            sourceSize = overlaySourceSize,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        IconButton(
            onClick  = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_close_cd),
                tint               = Color.White
            )
        }

        // AI overlay toggle — top-right, debug-gated. Same UX as the GP and
        // HiSilicon-family playback overlays.
        if (com.example.trafykamerasikotlin.BuildConfig.DEBUG) {
            AiOverlayToggleButton(
                enabled  = aiOverlayEnabled,
                onToggle = { setAiOverlay(!aiOverlayEnabled) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
    }
}

// ── Camera grouping helpers ─────────────────────────────────────────────────

/**
 * Determines the camera channel of a video file from its filename.
 * Suffix rules (HiDVR / Easytech / GeneralPlus) take precedence:
 *   _f  → Front   _b  → Back   _i  → Inside
 * Allwinner V853 uses a leading-letter convention instead:
 *   `F…\.ts` → Front,  `B…\.ts` → Back
 * Files without a recognised marker are treated as Front.
 */
private fun cameraOf(file: MediaFile): String {
    // Dual-cam HiSilicon firmware tells us the channel via the source directory;
    // honor that hint when it's set (it's authoritative — the filename may carry
    // no suffix at all on those devices).
    file.cameraHint?.let { return it }
    val base = file.name.substringBeforeLast('.').lowercase()
    return when {
        base.endsWith("_f") -> "Front"
        base.endsWith("_b") -> "Back"
        base.endsWith("_i") -> "Inside"
        // Allwinner: leading 'F' / 'B' on .ts segments.
        file.name.endsWith(".ts", ignoreCase = true) && file.name.firstOrNull() == 'F' -> "Front"
        file.name.endsWith(".ts", ignoreCase = true) && file.name.firstOrNull() == 'B' -> "Back"
        else                -> "Front"
    }
}

private fun groupVideosByCamera(videos: List<MediaFile>): Map<String, List<MediaFile>> =
    videos.groupBy { cameraOf(it) }

/**
 * Display label for a camera/photos tab. Tab keys stay ASCII internally ("Front",
 * "Back", "Inside", "Photos") so groupVideosByCamera and tab routing continue to work
 * regardless of locale; only the user-visible label flips.
 */
@Composable
private fun cameraTabLabel(key: String): String = when (key) {
    "Front"  -> stringResource(R.string.media_tab_front)
    "Back"   -> stringResource(R.string.media_tab_back)
    "Inside" -> stringResource(R.string.media_tab_inside)
    "Photos" -> stringResource(R.string.media_tab_photos)
    else     -> key
}

// ── State placeholders ──────────────────────────────────────────────────────

@Composable
private fun NotConnectedMediaContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector        = Icons.Filled.Movie,
                contentDescription = null,
                tint               = ColorTextSecondary,
                modifier           = Modifier.size(52.dp)
            )
            Text(
                text      = stringResource(R.string.common_not_connected_title),
                style     = MaterialTheme.typography.titleLarge,
                color     = ColorTextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text      = stringResource(R.string.media_not_connected_body),
                style     = MaterialTheme.typography.bodyMedium,
                color     = ColorTextSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

@Composable
private fun LoadingMediaContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = ColorPrimary)
            Text(
                text  = stringResource(R.string.media_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary
            )
        }
    }
}

@Composable
private fun ErrorMediaContent(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text      = stringResource(R.string.media_load_failed),
                style     = MaterialTheme.typography.bodyLarge,
                color     = ColorTextPrimary,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                shape   = RoundedCornerShape(12.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
            ) {
                Text(
                    text  = stringResource(R.string.common_retry),
                    color = ColorTextPrimary
                )
            }
        }
    }
}

@Composable
private fun EmptyMediaContent(isPhoto: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector        = if (isPhoto) Icons.Filled.Photo else Icons.Filled.Movie,
                contentDescription = null,
                tint               = ColorTextSecondary,
                modifier           = Modifier.size(48.dp)
            )
            Text(
                text  = stringResource(
                    if (isPhoto) R.string.media_empty_photos else R.string.media_empty_videos
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary
            )
        }
    }
}

// ── Media grid ──────────────────────────────────────────────────────────────

@Composable
private fun MediaGrid(
    files: List<MediaFile>,
    device: DeviceInfo?,
    downloading: Set<String>,
    downloadProgress: Map<String, DownloadState>,
    aiOverlayOn: Boolean,
    onToggleAi: (Boolean) -> Unit,
    onPlay: (MediaFile) -> Unit,
    onPlayAllwinner: (MediaFile) -> Unit,
    onPlayInApp: (String) -> Unit,
    onViewPhoto: (String) -> Unit,
    onDownload: (MediaFile) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDelete: (MediaFile) -> Unit,
) {
    var actionTarget by remember { mutableStateOf<MediaFile?>(null) }
    var deleteTarget by remember { mutableStateOf<MediaFile?>(null) }

    LazyVerticalGrid(
        columns             = GridCells.Fixed(2),
        contentPadding      = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        items(files, key = { it.path }) { file ->
            MediaFileCard(
                file          = file,
                isDownloading = downloading.contains(file.name),
                downloadState = downloadProgress[file.name],
                onClick       = { actionTarget = file }
            )
        }
    }

    // Action dialog: Play / Download / Delete
    actionTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            containerColor   = ColorSurface,
            title = {
                Text(
                    text     = file.name.substringBeforeLast('.'),
                    style    = MaterialTheme.typography.titleMedium,
                    color    = ColorTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Play (video) / View (photo)
                    if (file.isPhoto) {
                        DialogActionRow(
                            icon    = Icons.Filled.Photo,
                            label   = stringResource(R.string.media_action_view),
                            color   = ColorPrimary,
                            onClick = {
                                actionTarget = null
                                onViewPhoto(file.httpUrl)
                            }
                        )
                        HorizontalDivider(color = ColorDivider, thickness = 0.5.dp)
                    } else {
                        DialogActionRow(
                            icon    = Icons.Filled.PlayArrow,
                            label   = stringResource(R.string.media_action_play),
                            color   = ColorPrimary,
                            onClick = {
                                actionTarget = null
                                when (device?.protocol) {
                                    ChipsetProtocol.GENERALPLUS -> {
                                        // GP: call StartPlayback over GPSOCKET first; the ViewModel
                                        // will emit the RTSP URL via playbackUri when ready.
                                        onPlay(file)
                                    }
                                    ChipsetProtocol.ALLWINNER_V853 -> {
                                        // Allwinner: buffer RTP2P to a temp .ts then play via IjkPlayer.
                                        onPlayAllwinner(file)
                                    }
                                    else -> onPlayInApp(file.httpUrl)
                                }
                            }
                        )
                        HorizontalDivider(color = ColorDivider, thickness = 0.5.dp)
                    }
                    // Download / Cancel — when this chipset supports AI burn-in
                    // (GP / HiDVR-family; not Allwinner V853), the Download row
                    // gets a trailing AI toggle. Toggling it just flips the
                    // shared preference; tapping the row text/icon triggers the
                    // download (which routes through the global flag at the
                    // call site).
                    if (downloading.contains(file.name)) {
                        DialogActionRow(
                            icon    = Icons.Filled.Close,
                            label   = stringResource(R.string.media_action_cancel_download),
                            color   = Color(0xFFE53935),
                            onClick = {
                                actionTarget = null
                                onCancelDownload(file.name)
                            }
                        )
                    } else {
                        val aiDownloadSupported = device != null &&
                            device.protocol != ChipsetProtocol.ALLWINNER_V853
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        actionTarget = null
                                        onDownload(file)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = ColorTextPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = stringResource(R.string.media_action_download),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ColorTextPrimary,
                                )
                            }
                            if (aiDownloadSupported) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            color = if (aiOverlayOn) ColorPrimary
                                                    else ColorSurface.copy(alpha = 0.6f),
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                        )
                                        .clickable { onToggleAi(!aiOverlayOn) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AutoAwesome,
                                        contentDescription = stringResource(R.string.live_ai_overlay_toggle_cd),
                                        tint = if (aiOverlayOn) Color.White else ColorTextSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Delete — Allwinner firmware doesn't support deletion (the OEM app
                    // has no delete button either), so we hide the option entirely.
                    if (device?.protocol != ChipsetProtocol.ALLWINNER_V853) {
                        HorizontalDivider(color = ColorDivider, thickness = 0.5.dp)
                        DialogActionRow(
                            icon    = Icons.Filled.Delete,
                            label   = stringResource(R.string.common_delete),
                            color   = Color(0xFFE53935),
                            onClick = {
                                actionTarget = null
                                deleteTarget = file
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(
                        text  = stringResource(R.string.common_cancel),
                        color = ColorTextSecondary
                    )
                }
            }
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor   = ColorSurface,
            title = {
                Text(
                    text  = stringResource(R.string.media_dialog_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = ColorTextPrimary
                )
            },
            text = {
                Text(
                    text  = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(file)
                    deleteTarget = null
                }) {
                    Text(
                        text  = stringResource(R.string.common_delete),
                        color = Color(0xFFE53935)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(
                        text  = stringResource(R.string.common_cancel),
                        color = ColorTextSecondary
                    )
                }
            }
        )
    }
}

@Composable
private fun DialogActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

// ── File card ───────────────────────────────────────────────────────────────

@Composable
private fun MediaFileCard(
    file: MediaFile,
    isDownloading: Boolean,
    downloadState: DownloadState?,  // null = non-GP or not downloading; non-null = GP progress
    onClick: () -> Unit,
) {
    Card(
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier  = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail
            SubcomposeAsyncImage(
                model             = file.thumbnailUrl,
                contentDescription = null,
                contentScale      = ContentScale.Crop,
                modifier          = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier         = Modifier.fillMaxSize().background(ColorSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color    = ColorPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                },
                error = {
                    Box(
                        modifier         = Modifier.fillMaxSize().background(ColorSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = if (file.isPhoto) Icons.Filled.Photo else Icons.Filled.Movie,
                            contentDescription = null,
                            tint               = ColorTextSecondary,
                            modifier           = Modifier.size(32.dp)
                        )
                    }
                }
            )

            // Bottom overlay: filename, or download progress with MB + speed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                if (isDownloading && downloadState != null) {
                    Text(
                        text     = stringResource(
                            R.string.media_download_progress_fmt,
                            downloadState.pct,
                            downloadState.speedMbPerSec,
                        ),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text     = stringResource(
                            R.string.media_download_size_fmt,
                            downloadState.receivedMb,
                            downloadState.totalMb,
                        ),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    LinearProgressIndicator(
                        progress   = { downloadState.pct / 100f },
                        modifier   = Modifier.fillMaxWidth().height(3.dp),
                        color      = ColorPrimary,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                } else if (isDownloading) {
                    Text(
                        text     = stringResource(R.string.media_downloading_indeterminate),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    LinearProgressIndicator(
                        modifier   = Modifier.fillMaxWidth().height(3.dp),
                        color      = ColorPrimary,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                } else {
                    Text(
                        text     = formatFileName(file.name),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            // Play icon badge for videos (hidden while downloading)
            if (!file.isPhoto && !isDownloading) {
                Icon(
                    imageVector        = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint               = Color.White.copy(alpha = 0.85f),
                    modifier           = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                )
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Converts "2024_01_15_120000.mp4" → "2024-01-15 12:00:00".
 * Falls back to the raw name if it doesn't match the expected pattern.
 */
private fun formatFileName(name: String): String {
    val base = name.substringBeforeLast('.')
    return if (base.length >= 17 && base[4] == '_' && base[7] == '_' && base[10] == '_') {
        "${base.substring(0, 4)}-${base.substring(5, 7)}-${base.substring(8, 10)} " +
        "${base.substring(11, 13)}:${base.substring(13, 15)}:${base.substring(15, 17)}"
    } else {
        base
    }
}

// ── In-app video player overlay ─────────────────────────────────────────────

/**
 * Full-screen video overlay using custom MjpegRtspPlayer.
 * IjkPlayer's FFmpeg build lacks the MJPEG decoder (codec id 7), so we use our
 * own RFC 2435 JPEG-over-RTP reassembly player instead.
 * Shown when a GeneralPlus file is ready to stream via RTSP.
 * Dismissed by the back button or the back gesture.
 */
@Composable
private fun VideoPlayerOverlay(url: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    val bufferingState = remember { mutableStateOf(true) }
    val playerRef      = remember { mutableStateOf<MjpegRtspPlayer?>(null) }

    // AI overlay toggle — single shared, persistent preference (default ON).
    // Historical note: GP playback once timed out with AI submission live
    // because the older RTP path was fragile; if that regresses we'll handle
    // it in MjpegRtspPlayer rather than special-casing the default here.
    val (aiOverlayEnabled, setAiOverlay) =
        com.example.trafykamerasikotlin.data.settings.rememberAiOverlayPreference()
    val aiViewModel: LiveVisionOverlayViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val aiScene by aiViewModel.scene.collectAsStateWithLifecycle()

    // Frame count (diagnostic only).
    val frameCount = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    // Build the per-frame tap that forwards bitmaps into the AI pipeline.
    // Wrapped in `rememberUpdatedState` so the AndroidView factory's
    // captured reference always sees the latest lambda — toggling the
    // AI switch then doesn't require tearing down the player.
    //
    // IMPORTANT: this runs on the MJPEG receive thread, just before the
    // surface is drawn. Keep it fast — expensive work here delays the
    // first frame render, which is what made the overlay look like a
    // "stuck buffering spinner" to the user. The pipeline's submit()
    // is already non-blocking (Channel.trySend + DROP_OLDEST), but we
    // defensively wrap in a try/catch so any exception can't wedge the
    // player thread.
    val onFrameLambda: ((android.graphics.Bitmap) -> Unit)? = if (aiOverlayEnabled) {
        { bmp ->
            val n = frameCount.incrementAndGet()
            if (n <= 3 || n % 60 == 0) Log.d("MediaScreen", "VideoPlayerOverlay frame #$n → pipeline")
            try { aiViewModel.onFrame(bmp) } catch (t: Throwable) {
                Log.w("MediaScreen", "AI onFrame threw, dropping: ${t.message}")
            }
        }
    } else null
    val currentOnFrame by androidx.compose.runtime.rememberUpdatedState(onFrameLambda)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.d("MediaScreen", "VideoPlayerOverlay surfaceCreated — $url")
                            val player = MjpegRtspPlayer(
                                surface = holder.surface,
                                network = GeneralplusSession.getBoundNetwork(),
                            )
                            player.onFirstFrame = {
                                bufferingState.value = false
                            }
                            // Route per-frame bitmaps into the AI pipeline when
                            // the toggle is on. `currentOnFrame` is wrapped with
                            // rememberUpdatedState above so the captured ref always
                            // reads the latest lambda — flipping the toggle doesn't
                            // require tearing down the player.
                            player.onFrame = { bmp -> currentOnFrame?.invoke(bmp) }
                            playerRef.value = player
                            player.start(url)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.d("MediaScreen", "VideoPlayerOverlay surfaceDestroyed")
                            playerRef.value?.stop()
                            playerRef.value = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // AI bounding-box overlay — drawn above the SurfaceView. Always composed
        // (BoundingBoxOverlay handles a null scene internally by early-returning
        // before drawing) so the Canvas node is in the tree before the first
        // scene arrives. Mounting it lazily caused a z-order glitch where the
        // first non-null scene didn't paint above the SurfaceView until the
        // user toggled the AI switch off/on. Mirrors LiveScreen.
        val overlayScene = if (aiOverlayEnabled) aiScene else null
        val overlaySourceSize = overlayScene?.let {
            android.util.Size(it.sourceFrameSize.width, it.sourceFrameSize.height)
        } ?: android.util.Size(0, 0)
        BoundingBoxOverlay(
            scene = overlayScene,
            sourceSize = overlaySourceSize,
            modifier = Modifier.fillMaxSize(),
        )

        if (bufferingState.value) {
            CircularProgressIndicator(
                color    = ColorPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        IconButton(
            onClick  = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_close_cd),
                tint               = Color.White
            )
        }

        // AI overlay toggle — top-right, mirrors the Live tab's placement
        // so muscle memory carries over.
        AiOverlayToggleButton(
            enabled  = aiOverlayEnabled,
            onToggle = { setAiOverlay(!aiOverlayEnabled) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }

    DisposableEffect(url) {
        onDispose {
            Log.d("MediaScreen", "VideoPlayerOverlay dispose — stopping MjpegRtspPlayer")
            playerRef.value?.stop()
            playerRef.value = null
        }
    }
}

/**
 * Round icon toggle for the AI overlay, sized + styled to match the one
 * used on the Live tab. Kept private to MediaScreen; LiveScreen has its
 * own copy so the two files stay self-contained while the UX settles.
 * Both move to Settings for the release build.
 */
@Composable
private fun AiOverlayToggleButton(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (enabled) ColorPrimary else ColorSurface.copy(alpha = 0.6f),
                    shape = androidx.compose.foundation.shape.CircleShape,
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

// ── In-app HTTP video player (HiSilicon / Easytech / MStar / Novatek) ───────

/**
 * Full-screen overlay that plays an HTTP MP4/h264 URL via IjkPlayer. Used for
 * every HiDVR-family chipset where the camera exposes recordings directly over
 * HTTP. GeneralPlus (RTSP/MJPEG) and Allwinner (RTP2P temp file) use their own
 * overlays upstream.
 *
 * AI overlay (HiSilicon Chunk H1): IjkPlayer renders to a [TextureView] so we
 * can poll [TextureView.getBitmap] off-thread to feed the vision pipeline.
 * Toggle defaults OFF — tap the camera icon (top-right) to opt in. The
 * polling loop only runs while the toggle is on, so the off path costs the
 * same as the original SurfaceView path.
 */
@Composable
private fun IjkVideoPlayerOverlay(url: String, network: Network?, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    val bufferingState = remember { mutableStateOf(true) }
    val ctx = LocalContext.current

    // Let the overlay follow the phone's physical orientation via the sensor —
    // FULL_SENSOR ignores the system-level rotation lock, so tilting the phone
    // rotates the video even when auto-rotate is off. Restored on dismiss.
    DisposableEffect(Unit) {
        val activity = ctx as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Bind the whole process to the dashcam Wi-Fi so IjkPlayer's internal FFmpeg
    // HTTP socket can reach 192.168.0.1. Without this, the phone's default network
    // (cellular) is used and the connection times out. Mirrors LiveViewModel.
    DisposableEffect(network) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(network)
        Log.i("MediaScreen", "IjkVideoPlayerOverlay: bound process to $network")
        onDispose {
            cm.bindProcessToNetwork(null)
            Log.i("MediaScreen", "IjkVideoPlayerOverlay: unbound process")
        }
    }

    val ijkPlayer = remember(url) {
        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkMediaPlayer().apply {
            // HTTP file playback — keep analyzeduration small so playback starts quickly.
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", "100000")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",       "1048576")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags",          "nobuffer")
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC,  "skip_loop_filter", 48L)
            // Software decoding for the playback overlay. The hardware
            // OMX.qcom.video.decoder.avc on Adreno-class chipsets rejects
            // SurfaceTexture-backed Surfaces (the kind a TextureView gives
            // us), causing `feed_input_buffer: SDL_AMediaCodec_getInputBuffer
            // failed` immediately after start — audio plays, video stays
            // black. FFmpeg's software H.264 decoder works with any Surface,
            // and the AI-overlay tap polls TextureView.bitmap so we need a
            // TextureView regardless. Costs CPU but works.
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",             0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared",      1L)
        }
    }

    // ── AI overlay state ───────────────────────────────────────────────────
    // Single shared, persistent preference (default ON). Lives in
    // AiOverlayPreferences so toggling here is reflected on Live + every
    // other playback screen, and persists across app restarts.
    val (aiOverlayEnabled, setAiOverlay) =
        com.example.trafykamerasikotlin.data.settings.rememberAiOverlayPreference()
    val aiViewModel: LiveVisionOverlayViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val aiScene by aiViewModel.scene.collectAsStateWithLifecycle()

    // TextureView reference for the polling coroutine — assigned by the
    // SurfaceTextureListener once the GL surface is ready.
    val textureViewRef = remember { mutableStateOf<android.view.TextureView?>(null) }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Constrain to 16:9 so the 1920x1080 dashcam footage is letterboxed on a
        // portrait phone instead of being stretched to fill the screen.
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).also { tv ->
                    tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture, w: Int, h: Int,
                        ) {
                            Log.d("MediaScreen", "IjkVideoPlayerOverlay surfaceTextureAvailable ${w}x$h — $url")
                            try {
                                ijkPlayer.setSurface(android.view.Surface(surface))
                                ijkPlayer.dataSource = url
                                ijkPlayer.setOnPreparedListener { mp ->
                                    mp.start()
                                    bufferingState.value = false
                                }
                                ijkPlayer.setOnErrorListener { _, what, extra ->
                                    Log.e("MediaScreen", "IjkVideoPlayerOverlay error what=$what extra=$extra")
                                    false
                                }
                                ijkPlayer.prepareAsync()
                                textureViewRef.value = tv
                            } catch (e: Exception) {
                                Log.e("MediaScreen", "IjkVideoPlayerOverlay setup failed: ${e.message}")
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture, w: Int, h: Int,
                        ) {}

                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture,
                        ): Boolean {
                            Log.d("MediaScreen", "IjkVideoPlayerOverlay surfaceTextureDestroyed")
                            ijkPlayer.stop()
                            ijkPlayer.setSurface(null)
                            textureViewRef.value = null
                            return true   // releases the SurfaceTexture
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture,
                        ) {}
                    }
                }
            },
            modifier = Modifier
                .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                .aspectRatio(16f / 9f)
        )

        // ── AI bitmap polling ──────────────────────────────────────────────
        // Only active when the toggle is on AND the player has rendered its
        // first frame. We poll at ~10 fps (100 ms tick); the pipeline's own
        // sampler then drops 2 of every 3 (`inferenceEveryN = 3`) so actual
        // inference runs ~3 fps. Each `getBitmap` allocates ~5 MB at 1080p
        // letterboxed; we recycle right after submit since the pipeline
        // copies internally before parking on its inference channel.
        val aiActive = aiOverlayEnabled && !bufferingState.value
        val pollKey = textureViewRef.value
        LaunchedEffect(aiActive, pollKey) {
            if (!aiActive || pollKey == null) return@LaunchedEffect
            var firstFrameLogged = false
            while (true) {
                kotlinx.coroutines.delay(100)
                val tv = textureViewRef.value ?: break
                if (!tv.isAvailable) continue
                val bmp = try { tv.bitmap } catch (t: Throwable) {
                    Log.w("MediaScreen", "getBitmap failed: ${t.message}")
                    null
                } ?: continue
                if (!firstFrameLogged) {
                    Log.i("MediaScreen", "AI poll: first frame ${bmp.width}x${bmp.height}")
                    firstFrameLogged = true
                }
                try { aiViewModel.onFrame(bmp) } catch (t: Throwable) {
                    Log.w("MediaScreen", "AI onFrame threw: ${t.message}")
                } finally {
                    bmp.recycle()
                }
            }
        }

        // AI bounding-box overlay — always composed (handles null scene
        // internally) so the Canvas node is in the tree before the first
        // scene arrives. Mirrors the GP playback overlay pattern.
        val overlayScene = if (aiOverlayEnabled) aiScene else null
        val overlaySourceSize = overlayScene?.let {
            android.util.Size(it.sourceFrameSize.width, it.sourceFrameSize.height)
        } ?: android.util.Size(0, 0)
        BoundingBoxOverlay(
            scene = overlayScene,
            sourceSize = overlaySourceSize,
            modifier = Modifier
                .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                .aspectRatio(16f / 9f),
        )

        if (bufferingState.value) {
            CircularProgressIndicator(
                color    = ColorPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Back arrow only in portrait — in landscape the video owns the full screen
        // and the user dismisses via system back (BackHandler) or by tilting upright.
        if (!isLandscape) {
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_close_cd),
                    tint               = Color.White
                )
            }
        }

        // AI overlay toggle — top-right, same UX as GP playback.
        AiOverlayToggleButton(
            enabled  = aiOverlayEnabled,
            onToggle = { setAiOverlay(!aiOverlayEnabled) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }

    DisposableEffect(url) {
        onDispose {
            Log.d("MediaScreen", "IjkVideoPlayerOverlay dispose — releasing IjkPlayer")
            ijkPlayer.stop()
            ijkPlayer.release()
        }
    }
}

// ── In-app photo viewer ─────────────────────────────────────────────────────

/**
 * Full-screen photo overlay. Loads the image from the dashcam over HTTP using
 * Coil and fits it inside the screen while preserving aspect. Binds the process
 * network to the dashcam Wi-Fi so Coil's OkHttp can reach the camera even when
 * the phone also has cellular data; follows the phone sensor so the view rotates
 * when the user tilts the device, regardless of system rotation lock.
 */
@Composable
private fun PhotoViewerOverlay(url: String, network: Network?, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val ctx = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(Unit) {
        val activity = ctx as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(network) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(network)
        onDispose { cm.bindProcessToNetwork(null) }
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model              = url,
            contentDescription = null,
            contentScale       = ContentScale.Fit,
            modifier           = Modifier.fillMaxSize(),
            loading = {
                CircularProgressIndicator(
                    color    = ColorPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
            },
            error = {
                Icon(
                    imageVector        = Icons.Filled.Photo,
                    contentDescription = null,
                    tint               = ColorTextSecondary,
                    modifier           = Modifier.size(64.dp),
                )
            },
        )

        // Back arrow hidden in landscape for a chrome-free view; BackHandler still fires on system back.
        if (!isLandscape) {
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_close_cd),
                    tint               = Color.White,
                )
            }
        }
    }
}
