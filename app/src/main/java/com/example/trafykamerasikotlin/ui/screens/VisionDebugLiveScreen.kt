package com.example.trafykamerasikotlin.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Network
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDestructive
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSuccess
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.LiveState
import com.example.trafykamerasikotlin.ui.viewmodel.VisionDebugLiveViewModel

private const val DEFAULT_RTSP_URL = "rtsp://192.168.25.1:8080/?action=stream"

/**
 * Derive a sensible MJPEG-over-RTP URL from the connected device's protocol.
 * Our [MjpegRtspPlayer] can only parse RFC-2435 MJPEG payloads, which in the
 * current codebase is a GeneralPlus-specific path. Other chipsets stream
 * H.264 — they'll need Media3-based frame capture in a later chunk. For
 * now, return null for unsupported protocols so the UI can warn the user.
 */
private fun defaultRtspFor(device: DeviceInfo?): String? = when (device?.protocol) {
    ChipsetProtocol.GENERALPLUS -> "rtsp://${device.protocol.deviceIp}:8080/?action=stream"
    null -> null
    else -> null
}

private fun chipsetIsSupported(device: DeviceInfo?): Boolean =
    device?.protocol == ChipsetProtocol.GENERALPLUS

@Composable
fun VisionDebugLiveScreen(
    device: DeviceInfo?,
    network: Network?,
    modifier: Modifier = Modifier,
    viewModel: VisionDebugLiveViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scene by viewModel.scene.collectAsStateWithLifecycle()
    val sourceSize by viewModel.sourceFrameSize.collectAsStateWithLifecycle()
    val latency by viewModel.latency.collectAsStateWithLifecycle()
    val frameCount by viewModel.frameCounter.collectAsStateWithLifecycle()

    // Prefer a URL derived from the connected device. Falls back to the
    // documented GeneralPlus default so the screen is still usable when
    // the user manually types in a custom IP.
    var url by rememberSaveable(device?.protocol) {
        mutableStateOf(defaultRtspFor(device) ?: DEFAULT_RTSP_URL)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text  = stringResource(R.string.vision_debug_live_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ColorTextPrimary,
        )

        SupportedChipsetBanner(device = device)

        VideoWithOverlay(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            onSurfaceReady = viewModel::setSurface,
            onSurfaceGone = viewModel::clearSurface,
            scene = scene,
            sourceWidth = sourceSize?.width ?: 0,
            sourceHeight = sourceSize?.height ?: 0,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text(stringResource(R.string.vision_debug_live_url_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedTextColor = ColorTextPrimary,
                focusedTextColor = ColorTextPrimary,
                unfocusedBorderColor = ColorDivider,
                focusedBorderColor = ColorPrimary,
                cursorColor = ColorPrimary,
                focusedLabelColor = ColorPrimary,
                unfocusedLabelColor = ColorTextSecondary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.connect(url, network) },
                enabled = state !is LiveState.Streaming,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text(stringResource(R.string.vision_debug_live_connect), color = ColorTextPrimary) }
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                enabled = state is LiveState.Streaming || state is LiveState.Connecting,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text(stringResource(R.string.vision_debug_live_disconnect), color = ColorTextPrimary) }
        }

        StatusLine(state = state, frameCount = frameCount, latency = latency)
    }
}

@Composable
private fun SupportedChipsetBanner(device: DeviceInfo?) {
    val (color, text) = when {
        device == null -> ColorTextSecondary to stringResource(R.string.vision_debug_live_banner_not_connected)
        chipsetIsSupported(device) ->
            ColorSuccess to stringResource(R.string.vision_debug_live_banner_supported_fmt, device.protocol.displayName)
        else ->
            ColorDestructive to stringResource(R.string.vision_debug_live_banner_unsupported_fmt, device.protocol.displayName)
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun VideoWithOverlay(
    modifier: Modifier,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceGone:  () -> Unit,
    scene: com.example.trafykamerasikotlin.data.vision.TrackedScene?,
    sourceWidth:  Int,
    sourceHeight: Int,
) {
    Box(modifier = modifier.background(Color.Black, RoundedCornerShape(12.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { onSurfaceReady(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { onSurfaceGone() }
                    })
                }
            },
        )
        if (scene != null && sourceWidth > 0 && sourceHeight > 0) {
            BoundingBoxOverlay(
                scene = scene,
                sourceSize = android.util.Size(sourceWidth, sourceHeight),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StatusLine(
    state: LiveState,
    frameCount: Int,
    latency: com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram.Snapshot,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val (color, text) = when (state) {
                LiveState.Idle       -> ColorTextSecondary to stringResource(R.string.vision_debug_live_state_idle)
                LiveState.Connecting -> ColorTextSecondary to stringResource(R.string.vision_debug_live_state_connecting)
                LiveState.Streaming  -> ColorSuccess       to stringResource(R.string.vision_debug_live_state_streaming)
                is LiveState.Error   -> ColorDestructive   to state.message
            }
            Text(text = text, style = MaterialTheme.typography.titleSmall, color = color)
            Text(
                text = stringResource(R.string.vision_debug_live_stats_fmt,
                    frameCount, latency.samples, latency.p50, latency.p95),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = ColorTextSecondary,
            )
        }
    }
}
