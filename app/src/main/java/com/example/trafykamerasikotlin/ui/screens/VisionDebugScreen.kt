package com.example.trafykamerasikotlin.ui.screens

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.BuildConfig
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.vision.ncnn.LibLoadState
import com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDestructive
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSuccess
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.VisionDebugState
import com.example.trafykamerasikotlin.ui.viewmodel.VisionDebugViewModel

@Composable
fun VisionDebugScreen(
    modifier: Modifier = Modifier,
    viewModel: VisionDebugViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val useGpu by viewModel.useGpu.collectAsStateWithLifecycle()
    val scene by viewModel.scene.collectAsStateWithLifecycle()
    val bitmap by viewModel.currentBitmap.collectAsStateWithLifecycle()
    val latency by viewModel.latency.collectAsStateWithLifecycle()

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.runOnPickedVideo(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text  = stringResource(R.string.vision_debug_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ColorTextPrimary,
        )

        BackendStatusCard(viewModel.backendState)

        FrameWithOverlay(bitmap, scene, Modifier.fillMaxWidth().aspectRatio(16f / 9f))

        ActionsRow(
            onPickVideo = { pickVideo.launch("video/*") },
            onBenchmark = { viewModel.runBenchmark() },
            enabled = viewModel.backendState is LibLoadState.Loaded,
        )

        VulkanToggle(useGpu, onCheckedChange = viewModel::setUseGpu)

        LatencyCard(latency)

        telemetry?.let { ModelTelemetryCard(it) }

        StateFooter(state)

        DeviceInfoFooter()
    }
}

@Composable
private fun BackendStatusCard(libState: LibLoadState) {
    val (tint, bodyKey, bodyArg) = when (libState) {
        LibLoadState.Loaded -> Triple(ColorSuccess, R.string.vision_debug_backend_ok, "")
        LibLoadState.NotBundled -> Triple(ColorDestructive, R.string.vision_debug_backend_not_bundled, "")
        is LibLoadState.LoadFailed -> Triple(ColorDestructive, R.string.vision_debug_backend_load_failed_fmt, libState.reason)
        LibLoadState.NotAttempted -> Triple(ColorTextSecondary, R.string.vision_debug_backend_checking, "")
    }
    SectionCard {
        Text(
            text  = stringResource(R.string.vision_debug_backend_header),
            style = MaterialTheme.typography.titleSmall,
            color = ColorTextSecondary,
        )
        Text(
            text  = if (bodyArg.isEmpty()) stringResource(bodyKey) else stringResource(bodyKey, bodyArg),
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}

@Composable
private fun FrameWithOverlay(
    bitmap: Bitmap?,
    scene: com.example.trafykamerasikotlin.data.vision.TrackedScene?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(ColorSurface, RoundedCornerShape(12.dp))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (scene != null) {
                BoundingBoxOverlay(
                    scene       = scene,
                    sourceSize  = android.util.Size(bitmap.width, bitmap.height),
                    modifier    = Modifier.fillMaxSize(),
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text  = stringResource(R.string.vision_debug_pick_video_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ActionsRow(onPickVideo: () -> Unit, onBenchmark: () -> Unit, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onPickVideo,
            enabled = enabled,
            shape   = RoundedCornerShape(12.dp),
            colors  = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text(stringResource(R.string.vision_debug_pick_video), color = ColorTextPrimary) }
        OutlinedButton(
            onClick = onBenchmark,
            enabled = enabled,
            shape   = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text(stringResource(R.string.vision_debug_benchmark), color = ColorTextPrimary) }
    }
}

@Composable
private fun VulkanToggle(useGpu: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SectionCard {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.vision_debug_vulkan_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = ColorTextPrimary,
                )
                Text(
                    text = stringResource(R.string.vision_debug_vulkan_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorTextSecondary,
                )
            }
            Switch(
                checked = useGpu,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ColorTextPrimary,
                    checkedTrackColor = ColorPrimary,
                ),
            )
        }
    }
}

@Composable
private fun LatencyCard(snap: com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram.Snapshot) {
    SectionCard {
        Text(
            text  = stringResource(R.string.vision_debug_latency_header),
            style = MaterialTheme.typography.titleSmall,
            color = ColorTextSecondary,
        )
        if (snap.samples == 0) {
            Text(
                text  = stringResource(R.string.vision_debug_latency_no_samples),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary,
            )
        } else {
            val line = stringResource(
                R.string.vision_debug_latency_fmt,
                snap.samples, snap.p50, snap.p95, snap.avg, snap.min, snap.max,
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = ColorTextPrimary,
            )
        }
    }
}

@Composable
private fun ModelTelemetryCard(t: com.example.trafykamerasikotlin.data.vision.ModelTelemetry) {
    SectionCard {
        Text(
            text  = stringResource(R.string.vision_debug_model_header),
            style = MaterialTheme.typography.titleSmall,
            color = ColorTextSecondary,
        )
        val sizeMb = t.totalSizeBytes / (1024.0 * 1024.0)
        Text(
            text = stringResource(R.string.vision_debug_model_summary_fmt, sizeMb, t.loadTimeMillis,
                if (t.vulkan) "Vulkan" else "CPU"),
            style = MaterialTheme.typography.bodyMedium,
            color = ColorTextPrimary,
        )
        Text(
            text = "param  ${t.paramSha256Prefix}…  •  bin  ${t.binSha256Prefix}…",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = ColorTextSecondary,
        )
    }
}

@Composable
private fun StateFooter(state: VisionDebugState) {
    val (color, text) = when (state) {
        VisionDebugState.Idle             -> ColorTextSecondary to stringResource(R.string.vision_debug_state_idle)
        VisionDebugState.Decoding         -> ColorTextSecondary to stringResource(R.string.vision_debug_state_decoding)
        VisionDebugState.LoadingModel     -> ColorTextSecondary to stringResource(R.string.vision_debug_state_loading)
        VisionDebugState.Inferring        -> ColorTextSecondary to stringResource(R.string.vision_debug_state_inferring)
        VisionDebugState.Benchmarking     -> ColorTextSecondary to stringResource(R.string.vision_debug_state_benchmarking)
        is VisionDebugState.FrameResult   ->
            ColorSuccess to stringResource(R.string.vision_debug_state_frame_fmt, state.numDetections, state.latencyMs)
        is VisionDebugState.BenchmarkDone ->
            ColorSuccess to stringResource(R.string.vision_debug_state_benchmark_fmt,
                state.coldStartMs, state.snapshot.p50, state.snapshot.p95)
        is VisionDebugState.BackendMissing ->
            ColorDestructive to stringResource(R.string.vision_debug_state_backend_missing)
        is VisionDebugState.Error         ->
            ColorDestructive to state.message
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun DeviceInfoFooter() {
    Text(
        text = "ABI: ${Build.SUPPORTED_ABIS.joinToString(",")} • " +
               "NCNN bundled: ${BuildConfig.NCNN_PREBUILT_BUNDLED}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = ColorTextSecondary,
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}
