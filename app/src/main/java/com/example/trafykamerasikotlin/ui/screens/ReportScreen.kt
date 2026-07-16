package com.example.trafykamerasikotlin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.components.TrafyTopBar
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.FeedbackType
import com.example.trafykamerasikotlin.ui.viewmodel.ReportUiState
import com.example.trafykamerasikotlin.ui.viewmodel.ReportViewModel

/**
 * "Yardım & Geri Bildirim" — user-facing bug report / suggestion form.
 * Submissions go to trafy.tr; when the phone has no internet (typical on
 * the dashcam hotspot without cellular) the report is queued to disk and
 * delivered on a later launch, which the Queued dialog explains.
 */
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var selectedType by rememberSaveable { mutableStateOf(FeedbackType.Bug) }
    var description by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var includeDiagnostics by rememberSaveable { mutableStateOf(true) }

    val sending = state is ReportUiState.Sending
    // Validate up front: the server 400s a malformed contactEmail, and a
    // queued offline report would then be dropped as a permanent reject.
    val emailValid = email.isBlank() ||
        android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor   = ColorPrimary,
        unfocusedBorderColor = ColorTextSecondary,
        focusedTextColor     = ColorTextPrimary,
        unfocusedTextColor   = ColorTextPrimary,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
    ) {
        TrafyTopBar(title = stringResource(R.string.report_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Report type
            Column {
                Text(
                    text     = stringResource(R.string.report_type_label),
                    style    = MaterialTheme.typography.labelLarge,
                    color    = ColorTextSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                // Plain scrollable Row instead of FlowRow: the BOM's foundation-layout
                // FlowRow signature mismatches at runtime (NoSuchMethodError on device).
                Row(
                    modifier              = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FeedbackType.entries.forEach { type ->
                        FilterChip(
                            selected = type == selectedType,
                            onClick  = { selectedType = type },
                            enabled  = !sending,
                            label    = { Text(stringResource(type.labelRes)) },
                            colors   = FilterChipDefaults.filterChipColors(
                                containerColor         = ColorSurface,
                                labelColor             = ColorTextSecondary,
                                selectedContainerColor = ColorPrimary,
                                selectedLabelColor     = ColorTextPrimary,
                            ),
                        )
                    }
                }
            }

            // Description (required)
            OutlinedTextField(
                value         = description,
                // Hard cap matches the server's 4000-char validator so long
                // text is stopped at input time, not silently truncated later.
                onValueChange = { description = it.take(4000) },
                enabled       = !sending,
                label         = {
                    Text(
                        text  = stringResource(R.string.report_description_label),
                        color = ColorTextSecondary,
                    )
                },
                placeholder   = {
                    Text(
                        text  = stringResource(R.string.report_description_placeholder),
                        color = ColorTextSecondary,
                    )
                },
                minLines      = 5,
                modifier      = Modifier.fillMaxWidth(),
                colors        = fieldColors,
            )

            // Contact email (optional)
            OutlinedTextField(
                value           = email,
                onValueChange   = { email = it },
                enabled         = !sending,
                singleLine      = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                isError         = !emailValid,
                label           = {
                    Text(
                        text  = stringResource(R.string.report_email_label),
                        color = ColorTextSecondary,
                    )
                },
                supportingText  = {
                    Text(
                        text  = stringResource(
                            if (emailValid) R.string.report_email_hint
                            else R.string.report_email_invalid
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (emailValid) ColorTextSecondary
                                else MaterialTheme.colorScheme.error,
                    )
                },
                modifier        = Modifier.fillMaxWidth(),
                colors          = fieldColors,
            )

            // Diagnostics opt-out — the caption spells out exactly what is
            // attached (KVKK transparency) and that location is never sent.
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked         = includeDiagnostics,
                    onCheckedChange = { includeDiagnostics = it },
                    enabled         = !sending,
                    colors          = CheckboxDefaults.colors(
                        checkedColor   = ColorPrimary,
                        uncheckedColor = ColorTextSecondary,
                        checkmarkColor = ColorTextPrimary,
                    ),
                )
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text  = stringResource(R.string.report_include_diagnostics),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ColorTextPrimary,
                    )
                    Text(
                        text     = stringResource(R.string.report_diagnostics_caption),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = ColorTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick  = { viewModel.submit(selectedType, description, email, includeDiagnostics) },
                enabled  = description.isNotBlank() && emailValid && !sending,
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text  = stringResource(
                        if (sending) R.string.report_sending else R.string.report_submit
                    ),
                    color = ColorTextPrimary,
                )
            }
        }
    }

    // Result dialogs — Sent/Queued pop the screen on dismiss, Error keeps
    // the filled-in form so the user can retry.
    when (state) {
        ReportUiState.Sent -> ReportResultDialog(
            titleRes  = R.string.report_sent_title,
            bodyRes   = R.string.report_sent_body,
            onDismiss = {
                viewModel.reset()
                onBack()
            },
        )
        ReportUiState.Queued -> ReportResultDialog(
            titleRes  = R.string.report_queued_title,
            bodyRes   = R.string.report_queued_body,
            onDismiss = {
                viewModel.reset()
                onBack()
            },
        )
        ReportUiState.Error -> ReportResultDialog(
            titleRes  = R.string.report_error_title,
            bodyRes   = R.string.report_error_body,
            onDismiss = viewModel::reset,
        )
        else -> Unit
    }
}

@Composable
private fun ReportResultDialog(
    titleRes: Int,
    bodyRes: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = ColorSurface,
        title = {
            Text(
                text  = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = ColorTextPrimary,
            )
        },
        text = {
            Text(
                text  = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text  = stringResource(R.string.common_ok),
                    color = ColorPrimary,
                )
            }
        },
    )
}