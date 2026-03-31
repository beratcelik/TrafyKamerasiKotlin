package com.example.trafykamerasikotlin.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.MediaUiState
import com.example.trafykamerasikotlin.ui.viewmodel.MediaViewModel

@Composable
fun MediaScreen(
    device: DeviceInfo?,
    modifier: Modifier = Modifier,
    viewModel: MediaViewModel,
) {
    val uiState     by viewModel.uiState.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()

    LaunchedEffect(device) {
        if (device != null) viewModel.load(device)
        else viewModel.onLeave() // ensure playback exits if user disconnected while on tab
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onLeave() }
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Text(
            text     = "Media",
            style    = MaterialTheme.typography.headlineMedium,
            color    = ColorTextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        when (val state = uiState) {
            is MediaUiState.NotConnected -> NotConnectedMediaContent()
            is MediaUiState.Loading      -> LoadingMediaContent()
            is MediaUiState.Error        -> ErrorMediaContent(
                message = state.message,
                onRetry = { viewModel.reload() }
            )
            is MediaUiState.Loaded -> {
                // Group videos by camera channel (front/back/inside) from filename suffix.
                // Only tabs for cameras that actually have footage are shown.
                val videosByCamera = groupVideosByCamera(state.videos)
                val cameraTabs     = listOf("Front", "Back", "Inside")
                    .filter { videosByCamera.containsKey(it) }
                val tabs           = cameraTabs + "Photos"
                // Clamp in case a reload returns fewer cameras than current tab index
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
                                Text(text = title, style = MaterialTheme.typography.titleMedium)
                            }
                        )
                    }
                }

                val isPhotoTab = safeTab == tabs.lastIndex
                val files      = if (isPhotoTab) state.photos
                                 else videosByCamera[cameraTabs.getOrNull(safeTab)] ?: emptyList()

                if (files.isEmpty()) {
                    EmptyMediaContent(isPhoto = isPhotoTab)
                } else {
                    MediaGrid(
                        files       = files,
                        downloading = downloading,
                        onDownload  = { viewModel.download(it) },
                        onDelete    = { viewModel.delete(it) }
                    )
                }
            }
        }
    }
}

// ── Camera grouping helpers ─────────────────────────────────────────────────

/**
 * Determines the camera channel of a video file from its filename suffix.
 *   _f  → Front  (single camera or multi-camera front)
 *   _b  → Back   (rear camera)
 *   _i  → Inside (third/inside camera)
 * Files without a recognised suffix are treated as Front (single-camera devices).
 */
private fun cameraOf(file: MediaFile): String {
    val base = file.name.substringBeforeLast('.').lowercase()
    return when {
        base.endsWith("_f") -> "Front"
        base.endsWith("_b") -> "Back"
        base.endsWith("_i") -> "Inside"
        else                -> "Front"
    }
}

private fun groupVideosByCamera(videos: List<MediaFile>): Map<String, List<MediaFile>> =
    videos.groupBy { cameraOf(it) }

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
                text      = "Not connected",
                style     = MaterialTheme.typography.titleLarge,
                color     = ColorTextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text      = "Connect to your dashcam on the Home screen first",
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
                text  = "Loading media files…",
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary
            )
        }
    }
}

@Composable
private fun ErrorMediaContent(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text      = message,
                style     = MaterialTheme.typography.bodyLarge,
                color     = ColorTextPrimary,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                shape   = RoundedCornerShape(12.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
            ) {
                Text("Retry", color = ColorTextPrimary)
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
                text  = if (isPhoto) "No photos captured" else "No videos recorded",
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
    downloading: Set<String>,
    onDownload: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit,
) {
    val context = LocalContext.current
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
                file    = file,
                onClick = { actionTarget = file }
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
                    // Play
                    if (!file.isPhoto) {
                        DialogActionRow(
                            icon    = Icons.Filled.PlayArrow,
                            label   = "Play",
                            color   = ColorPrimary,
                            onClick = {
                                actionTarget = null
                                playVideo(context, file.httpUrl)
                            }
                        )
                        HorizontalDivider(color = ColorDivider, thickness = 0.5.dp)
                    }
                    // Download
                    DialogActionRow(
                        icon    = Icons.Filled.Download,
                        label   = if (downloading.contains(file.name)) "Downloading…" else "Download",
                        color   = ColorTextPrimary,
                        onClick = {
                            actionTarget = null
                            onDownload(file)
                        }
                    )
                    HorizontalDivider(color = ColorDivider, thickness = 0.5.dp)
                    // Delete
                    DialogActionRow(
                        icon    = Icons.Filled.Delete,
                        label   = "Delete",
                        color   = Color(0xFFE53935),
                        onClick = {
                            actionTarget = null
                            deleteTarget = file
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text("Cancel", color = ColorTextSecondary)
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
                Text("Delete file?", style = MaterialTheme.typography.titleMedium, color = ColorTextPrimary)
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
                    Text("Delete", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = ColorTextSecondary)
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

            // Bottom filename overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text     = formatFileName(file.name),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Play icon badge for videos
            if (!file.isPhoto) {
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

private fun playVideo(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        // FLAG_ACTIVITY_CLEAR_TASK ensures a fresh player every time — without it
        // the Xiaomi video player reuses its existing task and fails on second play.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

