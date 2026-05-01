package com.example.trafykamerasikotlin.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDestructive
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSuccess
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.VideoAiProcessingViewModel
import com.example.trafykamerasikotlin.ui.viewmodel.VideoAiProcessingViewModel.UiState

@Composable
fun VideoAiProcessingScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoAiProcessingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // One-step flow: pick → state=Picked → immediately startProcessing.
        // Both run on the main thread so by the time startProcessing reads
        // _state.value it sees Picked and proceeds.
        if (uri != null) {
            viewModel.onVideoPicked(uri)
            viewModel.startProcessing()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text  = stringResource(R.string.video_ai_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ColorTextPrimary,
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurface),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text  = stringResource(R.string.video_ai_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextPrimary,
                )
                Text(
                    text  = stringResource(R.string.video_ai_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorTextSecondary,
                )
            }
        }

        val working = state is UiState.Working
        Button(
            onClick = { picker.launch("video/*") },
            enabled = !working,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text(stringResource(R.string.video_ai_pick), color = ColorTextPrimary) }

        StatusCard(state)
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is UiState.Idle -> Text(
                    text  = stringResource(R.string.video_ai_state_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                )
                is UiState.Picked -> {
                    Text(
                        text  = stringResource(R.string.video_ai_state_picked_fmt, state.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorTextPrimary,
                    )
                    Text(
                        text  = stringResource(R.string.video_ai_state_picked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary,
                    )
                }
                is UiState.Working -> {
                    Text(
                        text  = stringResource(R.string.video_ai_state_working_fmt,
                            state.displayName,
                            (state.fractionDone * 100f).toInt(),
                            state.frameIndex,
                            state.totalFrames,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = ColorTextPrimary,
                    )
                    LinearProgressIndicator(
                        progress = { state.fractionDone },
                        color = ColorPrimary,
                        trackColor = ColorBackground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UiState.Done -> {
                    val openFailedMsg = stringResource(R.string.video_ai_open_failed)
                    // The success message itself is the button — tap it to open
                    // the AI-overlay video. Distinct success-green colour so it
                    // doesn't blend with the primary "Pick Video" button above.
                    Button(
                        onClick = {
                            val authority = "${context.packageName}.fileprovider"
                            val uri = FileProvider.getUriForFile(context, authority, state.outputFile)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, openFailedMsg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ColorSuccess),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
                    ) {
                        Text(
                            text  = stringResource(R.string.video_ai_state_done_fmt,
                                state.outputFile.name, state.frameCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorTextPrimary,
                        )
                    }
                }
                is UiState.Failed -> Text(
                    text  = stringResource(R.string.video_ai_state_failed_fmt, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorDestructive,
                )
            }
        }
    }
}
