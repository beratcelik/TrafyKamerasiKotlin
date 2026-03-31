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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.model.SettingOption
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorSurfaceElevated
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.SettingsUiState
import com.example.trafykamerasikotlin.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    deviceIp: String?,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Trigger load when the screen appears with a connected device
    LaunchedEffect(deviceIp) {
        if (deviceIp != null) viewModel.load(deviceIp)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Header
        Text(
            text     = "Settings",
            style    = MaterialTheme.typography.headlineMedium,
            color    = ColorTextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        when (val state = uiState) {
            is SettingsUiState.NotConnected -> NotConnectedContent()

            is SettingsUiState.Loading -> LoadingContent()

            is SettingsUiState.Loaded -> SettingsList(
                items     = state.items,
                applying  = false,
                onSelect  = { key, value -> viewModel.apply(key, value) }
            )

            is SettingsUiState.Applying -> SettingsList(
                items    = state.items,
                applying = true,
                onSelect = { _, _ -> }
            )

            is SettingsUiState.Error -> ErrorContent(
                message   = state.message,
                onRetry   = { if (deviceIp != null) viewModel.reload(deviceIp) }
            )
        }
    }
}

// ── State sub-screens ──────────────────────────────────────────────────────

@Composable
private fun NotConnectedContent() {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector        = Icons.Filled.Settings,
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
private fun LoadingContent() {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = ColorPrimary)
            Text(
                text  = "Loading camera settings…",
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
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
                onClick  = onRetry,
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
            ) {
                Text("Retry", color = ColorTextPrimary)
            }
        }
    }
}

// ── Settings list ──────────────────────────────────────────────────────────

@Composable
private fun SettingsList(
    items: List<SettingItem>,
    applying: Boolean,
    onSelect: (key: String, value: String) -> Unit,
) {
    var pendingItem by remember { mutableStateOf<SettingItem?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Group all items into a single card (matches existing Settings style)
            item {
                Spacer(Modifier.height(4.dp))
            }
            item {
                SettingsCard(
                    items    = items,
                    enabled  = !applying,
                    onTap    = { item -> pendingItem = item }
                )
            }
        }

        // Applying overlay
        if (applying) {
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .background(ColorBackground.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = ColorPrimary)
                    Text(
                        text  = "Applying setting…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorTextPrimary
                    )
                }
            }
        }
    }

    // Option picker dialog
    pendingItem?.let { item ->
        OptionPickerDialog(
            item      = item,
            onDismiss = { pendingItem = null },
            onConfirm = { option ->
                pendingItem = null
                onSelect(item.key, option.value)
            }
        )
    }
}

@Composable
private fun SettingsCard(
    items: List<SettingItem>,
    enabled: Boolean,
    onTap: (SettingItem) -> Unit,
) {
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(items) { index, item ->
                SettingRow(
                    item    = item,
                    enabled = enabled,
                    onTap   = { onTap(item) }
                )
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color     = ColorDivider,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}

// Extension to use itemsIndexed inside a regular Column (not LazyColumn)
@Composable
private fun ColumnScope(
    items: List<SettingItem>,
    block: @Composable (Int, SettingItem) -> Unit
) {
    items.forEachIndexed { index, item -> block(index, item) }
}

// We need a Column-compatible version:
@Composable
private fun itemsIndexed(
    items: List<SettingItem>,
    content: @Composable (Int, SettingItem) -> Unit,
) {
    items.forEachIndexed { index, item -> content(index, item) }
}

@Composable
private fun SettingRow(
    item: SettingItem,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text     = item.title,
            style    = MaterialTheme.typography.bodyLarge,
            color    = if (enabled) ColorTextPrimary else ColorTextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text  = item.currentValueLabel.ifEmpty { item.currentValue },
            style = MaterialTheme.typography.bodyMedium,
            color = ColorPrimary
        )
    }
}

// ── Option picker dialog ───────────────────────────────────────────────────

@Composable
private fun OptionPickerDialog(
    item: SettingItem,
    onDismiss: () -> Unit,
    onConfirm: (SettingOption) -> Unit,
) {
    var selected by remember { mutableStateOf(item.currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = ColorSurface,
        title            = {
            Text(
                text  = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = ColorTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.options.forEach { option ->
                    OptionRow(
                        label      = option.label,
                        isSelected = option.value == selected,
                        onClick    = { selected = option.value }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = item.options.find { it.value == selected }
                    if (chosen != null) onConfirm(chosen)
                }
            ) {
                Text("Apply", color = ColorPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ColorTextSecondary)
            }
        }
    )
}

@Composable
private fun OptionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) ColorPrimary else ColorTextPrimary,
        )
        if (isSelected) {
            Icon(
                imageVector        = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint               = ColorPrimary,
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}
