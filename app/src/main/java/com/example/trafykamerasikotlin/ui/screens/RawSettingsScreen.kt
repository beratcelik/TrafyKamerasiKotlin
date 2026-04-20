package com.example.trafykamerasikotlin.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.RawEntry
import com.example.trafykamerasikotlin.ui.viewmodel.RawSettingsState
import com.example.trafykamerasikotlin.ui.viewmodel.RawSettingsViewModel

@Composable
fun RawSettingsScreen(
    device: DeviceInfo?,
    modifier: Modifier = Modifier,
    viewModel: RawSettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(device) {
        if (device != null) viewModel.load(device)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Header row
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text  = stringResource(R.string.raw_title),
                style = MaterialTheme.typography.headlineMedium,
                color = ColorTextPrimary
            )
            if (state is RawSettingsState.Success) {
                Button(
                    onClick = { copyAll(context, (state as RawSettingsState.Success).entries) },
                    shape   = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text  = stringResource(R.string.raw_copy_all),
                        color = ColorTextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        when (val s = state) {
            is RawSettingsState.Idle, is RawSettingsState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = ColorPrimary)
                        Text(
                            text  = stringResource(R.string.raw_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorTextSecondary
                        )
                    }
                }
            }

            is RawSettingsState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.BugReport,
                            contentDescription = null,
                            tint               = ColorTextSecondary,
                            modifier           = Modifier.size(52.dp)
                        )
                        Text(
                            text  = stringResource(R.string.raw_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorTextSecondary
                        )
                        if (device != null) {
                            Button(
                                onClick = { viewModel.load(device) },
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
            }

            is RawSettingsState.Success -> {
                LazyColumn(
                    contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(s.entries) { entry ->
                        RawEntryCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun RawEntryCard(entry: RawEntry) {
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text  = entry.label,
                style = MaterialTheme.typography.titleMedium,
                color = ColorTextPrimary
            )
            Text(
                text  = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = ColorPrimary,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text       = entry.body,
                style      = MaterialTheme.typography.bodySmall,
                color      = ColorTextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun copyAll(context: Context, entries: List<RawEntry>) {
    val text = entries.joinToString("\n\n---\n\n") { entry ->
        "# ${entry.label}\n# ${entry.url}\n\n${entry.body}"
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Raw Settings Dump", text))

    // Print to logcat so it's easy to inspect in Android Studio
    entries.forEach { entry ->
        android.util.Log.i("Trafy.RawDump", "=== ${entry.label} ===")
        android.util.Log.i("Trafy.RawDump", "URL: ${entry.url}")
        // Log in chunks of 3000 chars — logcat truncates long lines
        val body = entry.body
        var offset = 0
        while (offset < body.length) {
            val end = minOf(offset + 3000, body.length)
            android.util.Log.i("Trafy.RawDump", body.substring(offset, end))
            offset = end
        }
    }
}
