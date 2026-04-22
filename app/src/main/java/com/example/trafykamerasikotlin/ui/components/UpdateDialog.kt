package com.example.trafykamerasikotlin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.platform.LocalConfiguration
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.update.UpdateInfo
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.UpdateUiState

@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Idle -> Unit

        is UpdateUiState.Checking -> CheckingDialog(onDismiss)

        is UpdateUiState.UpToDate -> SimpleDialog(
            title    = stringResource(R.string.update_dialog_up_to_date_title),
            body     = stringResource(R.string.update_dialog_up_to_date_body),
            onDismiss = onDismiss,
        )

        is UpdateUiState.Available -> AvailableDialog(
            info        = state.info,
            onUpdate    = onUpdate,
            onLater     = onDismiss,
        )

        is UpdateUiState.Downloading -> DownloadingDialog(progress = state.progress)

        is UpdateUiState.ReadyToInstall -> SimpleDialog(
            title    = stringResource(R.string.update_dialog_ready_title),
            body     = stringResource(R.string.update_dialog_ready_body),
            confirmText = stringResource(R.string.update_dialog_install_again),
            onConfirm = onUpdate,
            onDismiss = onDismiss,
        )

        is UpdateUiState.Error -> SimpleDialog(
            title    = stringResource(R.string.update_dialog_error_title),
            body     = when (state.kind) {
                UpdateUiState.Error.Kind.Network      -> stringResource(R.string.update_dialog_error_network)
                UpdateUiState.Error.Kind.Download     -> stringResource(R.string.update_dialog_error_download)
                UpdateUiState.Error.Kind.BadSignature -> stringResource(R.string.update_dialog_error_bad_signature)
                UpdateUiState.Error.Kind.BadChecksum  -> stringResource(R.string.update_dialog_error_bad_checksum)
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun CheckingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = ColorSurface,
        title = {
            Text(
                text  = stringResource(R.string.update_dialog_checking_title),
                style = MaterialTheme.typography.titleLarge,
                color = ColorTextPrimary,
            )
        },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorPrimary, modifier = Modifier.size(36.dp))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text  = stringResource(R.string.common_cancel),
                    color = ColorTextSecondary,
                )
            }
        },
    )
}

@Composable
private fun AvailableDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]
    val isTurkish = locale?.language == "tr"
    val notes = when {
        isTurkish && info.releaseNotesTr.isNotBlank() -> info.releaseNotesTr
        info.releaseNotesEn.isNotBlank()              -> info.releaseNotesEn
        else                                          -> ""
    }

    AlertDialog(
        onDismissRequest = { if (!info.mandatory) onLater() },
        containerColor   = ColorSurface,
        title = {
            Text(
                text  = stringResource(R.string.update_dialog_available_title),
                style = MaterialTheme.typography.titleLarge,
                color = ColorTextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text  = stringResource(R.string.update_dialog_version_fmt, info.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ColorTextPrimary,
                )
                if (notes.isNotBlank()) {
                    Text(
                        text  = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorTextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(
                    text  = stringResource(R.string.update_dialog_update_now),
                    color = ColorPrimary,
                )
            }
        },
        dismissButton = if (info.mandatory) {
            null
        } else {
            {
                TextButton(onClick = onLater) {
                    Text(
                        text  = stringResource(R.string.update_dialog_later),
                        color = ColorTextSecondary,
                    )
                }
            }
        },
    )
}

@Composable
private fun DownloadingDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {}, // non-dismissable while downloading
        containerColor   = ColorSurface,
        title = {
            Text(
                text  = stringResource(R.string.update_dialog_downloading_title),
                style = MaterialTheme.typography.titleLarge,
                color = ColorTextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color    = ColorPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text      = "${(progress * 100).toInt()}%",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = ColorTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        color    = ColorPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SimpleDialog(
    title: String,
    body: String,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = ColorSurface,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = ColorTextPrimary)
        },
        text = {
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = ColorTextSecondary)
        },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                TextButton(onClick = onConfirm) {
                    Text(text = confirmText, color = ColorPrimary)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.common_ok), color = ColorPrimary)
                }
            }
        },
        dismissButton = if (confirmText != null && onConfirm != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(
                        text  = stringResource(R.string.common_cancel),
                        color = ColorTextSecondary,
                    )
                }
            }
        } else null,
    )
}

