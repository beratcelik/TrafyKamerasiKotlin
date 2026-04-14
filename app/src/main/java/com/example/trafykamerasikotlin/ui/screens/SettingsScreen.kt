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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.model.SettingOption
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDestructive
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.ApnDialogState
import com.example.trafykamerasikotlin.ui.viewmodel.SettingsUiState
import com.example.trafykamerasikotlin.ui.viewmodel.SettingsViewModel
import com.example.trafykamerasikotlin.ui.viewmodel.WifiDialogState

@Composable
fun SettingsScreen(
    device: DeviceInfo?,
    onRawDump: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionFeedback by viewModel.actionFeedback.collectAsStateWithLifecycle()
    val wifiDialog by viewModel.wifiDialog.collectAsStateWithLifecycle()
    val apnDialog by viewModel.apnDialog.collectAsStateWithLifecycle()

    // Trigger load when the screen appears with a connected device
    LaunchedEffect(device) {
        if (device != null) viewModel.load(device)
    }

    // Tell the camera to exit settings mode when leaving the screen
    DisposableEffect(device) {
        onDispose { viewModel.onLeave() }
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
                items          = state.items,
                applying       = false,
                onSelect       = { key, value -> viewModel.apply(key, value) },
                onAction       = { key -> viewModel.triggerAction(key) },
                onWifiSettings = viewModel::openWifiSettings,
                onApnDialog    = viewModel::openApnDialog,
                onRawDump      = onRawDump
            )

            is SettingsUiState.Applying -> SettingsList(
                items          = state.items,
                applying       = true,
                onSelect       = { _, _ -> },
                onAction       = { },
                onWifiSettings = { },
                onApnDialog    = { },
                onRawDump      = onRawDump
            )

            is SettingsUiState.Error -> ErrorContent(
                message   = state.message,
                onRetry   = { if (device != null) viewModel.reload(device) }
            )
        }

        // Action result dialog (shown on top of whatever state the screen is in)
        actionFeedback?.let { msg ->
            AlertDialog(
                onDismissRequest = viewModel::clearActionFeedback,
                containerColor   = ColorSurface,
                title = { Text("Result", style = MaterialTheme.typography.titleLarge, color = ColorTextPrimary) },
                text  = { Text(msg, style = MaterialTheme.typography.bodyMedium, color = ColorTextSecondary) },
                confirmButton = {
                    TextButton(onClick = viewModel::clearActionFeedback) {
                        Text("OK", color = ColorPrimary)
                    }
                }
            )
        }

        // Wi-Fi password dialog
        if (wifiDialog !is WifiDialogState.Hidden) {
            WifiPasswordDialog(
                state     = wifiDialog,
                onSave    = { ssid, pass -> viewModel.saveWifiPassword(ssid, pass) },
                onDismiss = viewModel::dismissWifiDialog
            )
        }

        // APN configuration dialog
        if (apnDialog !is ApnDialogState.Hidden) {
            ApnDialog(
                state     = apnDialog,
                onSave    = { apn, user, pass -> viewModel.saveApn(apn, user, pass) },
                onDismiss = viewModel::dismissApnDialog
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
                text      = "Connect to your Trafy Kamerası on the Home screen first",
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

private val DESTRUCTIVE_KEYS = setOf("format", "reset.cgi?")

@Composable
private fun SettingsList(
    items: List<SettingItem>,
    applying: Boolean,
    onSelect: (key: String, value: String) -> Unit,
    onAction: (key: String) -> Unit,
    onWifiSettings: () -> Unit,
    onApnDialog: () -> Unit,
    onRawDump: () -> Unit,
) {
    var pendingItem by remember { mutableStateOf<SettingItem?>(null) }
    var pendingDestructive by remember { mutableStateOf<SettingItem?>(null) }

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
                    onTap    = { item ->
                        when {
                            item.options.isNotEmpty()       -> pendingItem = item
                            item.key in DESTRUCTIVE_KEYS    -> pendingDestructive = item
                            item.key == "getwifi.cgi?"     -> onWifiSettings()
                            item.key == "allwinner_apn"    -> onApnDialog()
                            else                            -> onAction(item.key)
                        }
                    }
                )
            }
            item {
                TextButton(
                    onClick  = onRawDump,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text  = "Raw Settings Dump",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary
                    )
                }
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

    // Destructive action confirmation dialog
    pendingDestructive?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDestructive = null },
            containerColor   = ColorSurface,
            title = {
                Text(item.title, style = MaterialTheme.typography.titleLarge, color = ColorTextPrimary)
            },
            text = {
                Text(
                    "This action cannot be undone. Are you sure?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(item.key)
                    pendingDestructive = null
                }) {
                    Text("Confirm", color = ColorDestructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructive = null }) {
                    Text("Cancel", color = ColorTextSecondary)
                }
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

// Column-compatible itemsIndexed (LazyColumn's version can't be used inside a Card/Column)
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
    val isInfo   = item.key.endsWith("__info") || item.key.endsWith("__ro")
    val isAction = !isInfo && item.options.isEmpty()
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .then(if (isInfo) Modifier else Modifier.clickable(enabled = enabled, onClick = onTap))
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
        if (isInfo) {
            val value = item.currentValueLabel.ifEmpty { item.currentValue }
            Text(
                text  = value,
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary
            )
            if (item.key == "imei__info") {
                val clipboard = LocalClipboardManager.current
                IconButton(
                    onClick  = { clipboard.setText(AnnotatedString(value)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Filled.ContentCopy,
                        contentDescription = "Kopyala",
                        tint               = ColorTextSecondary,
                        modifier           = Modifier.size(16.dp)
                    )
                }
            }
        } else if (isAction) {
            Icon(
                imageVector        = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint               = ColorTextSecondary,
                modifier           = Modifier.size(20.dp)
            )
        } else {
            Text(
                text  = item.currentValueLabel.ifEmpty { item.currentValue },
                style = MaterialTheme.typography.bodyMedium,
                color = ColorPrimary
            )
        }
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

// ── Wi-Fi password dialog ──────────────────────────────────────────────────

@Composable
private fun WifiPasswordDialog(
    state: WifiDialogState,
    onSave: (ssid: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isLoading = state is WifiDialogState.Loading || state is WifiDialogState.Saving
    val isError   = state is WifiDialogState.Error

    // Only re-initialize from camera data when the Loaded state first arrives.
    // Using the ssid as the key means the field won't reset while the user types.
    val loadedState = state as? WifiDialogState.Loaded
    var password     by remember(loadedState?.ssid) {
        mutableStateOf(loadedState?.password ?: "")
    }
    var showPassword by remember { mutableStateOf(false) }

    val ssid = loadedState?.ssid ?: ""

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor   = ColorSurface,
        title = {
            Text("Wi-Fi Settings", style = MaterialTheme.typography.titleLarge, color = ColorTextPrimary)
        },
        text = {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ColorPrimary, modifier = Modifier.size(32.dp))
                    }
                }
                isError -> {
                    Text(
                        "Could not load Wi-Fi settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorTextSecondary
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value         = ssid,
                            onValueChange = {},
                            readOnly      = true,
                            label         = { Text("Network Name (SSID)", color = ColorTextSecondary) },
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = ColorPrimary,
                                unfocusedBorderColor = ColorTextSecondary,
                                focusedTextColor     = ColorTextPrimary,
                                unfocusedTextColor   = ColorTextPrimary,
                                disabledTextColor    = ColorTextSecondary,
                            )
                        )
                        OutlinedTextField(
                            value         = password,
                            onValueChange = { password = it },
                            label         = { Text("Password", color = ColorTextSecondary) },
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showPassword) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            trailingIcon  = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Filled.VisibilityOff
                                                      else Icons.Filled.Visibility,
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                        tint = ColorTextSecondary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = ColorPrimary,
                                unfocusedBorderColor = ColorTextSecondary,
                                focusedTextColor     = ColorTextPrimary,
                                unfocusedTextColor   = ColorTextPrimary,
                            )
                        )
                        Text(
                            "Minimum 8 characters. You'll need to reconnect after changing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColorTextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isError) {
                TextButton(
                    onClick  = { if (!isLoading) onSave(ssid, password) },
                    enabled  = !isLoading && password.length >= 8
                ) {
                    Text("Save", color = ColorPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!isLoading) onDismiss() }) {
                Text("Cancel", color = ColorTextSecondary)
            }
        }
    )
}

// ── APN configuration dialog ───────────────────────────────────────────────

@Composable
private fun ApnDialog(
    state: ApnDialogState,
    onSave: (apn: String, user: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isSaving = state is ApnDialogState.Saving
    val isError  = state is ApnDialogState.Error

    val loadedState = state as? ApnDialogState.Loaded
    var apn      by remember(loadedState) { mutableStateOf(loadedState?.apn ?: "") }
    var user     by remember(loadedState) { mutableStateOf(loadedState?.user ?: "") }
    var password by remember(loadedState) { mutableStateOf(loadedState?.password ?: "") }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor   = ColorSurface,
        title = {
            Text("APN Yapılandırması", style = MaterialTheme.typography.titleLarge, color = ColorTextPrimary)
        },
        text = {
            when {
                isSaving -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ColorPrimary, modifier = Modifier.size(32.dp))
                    }
                }
                isError -> {
                    Text(
                        "APN kaydedilemedi. Bağlantıyı kontrol edin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorTextSecondary
                    )
                }
                else -> {
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = ColorPrimary,
                        unfocusedBorderColor = ColorTextSecondary,
                        focusedTextColor     = ColorTextPrimary,
                        unfocusedTextColor   = ColorTextPrimary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value         = apn,
                            onValueChange = { apn = it },
                            label         = { Text("APN", color = ColorTextSecondary) },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = fieldColors,
                        )
                        OutlinedTextField(
                            value         = user,
                            onValueChange = { user = it },
                            label         = { Text("Kullanıcı Adı", color = ColorTextSecondary) },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = fieldColors,
                        )
                        OutlinedTextField(
                            value         = password,
                            onValueChange = { password = it },
                            label         = { Text("Şifre", color = ColorTextSecondary) },
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = fieldColors,
                        )
                        Text(
                            "APN boş bırakılabilir. Değişiklikler cihazı yeniden başlatabilir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColorTextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isError) {
                TextButton(
                    onClick  = { if (!isSaving) onSave(apn, user, password) },
                    enabled  = !isSaving
                ) {
                    Text("Kaydet", color = ColorPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!isSaving) onDismiss() }) {
                Text("İptal", color = ColorTextSecondary)
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
