package tr.trafy.kamera.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tr.trafy.kamera.R
import tr.trafy.kamera.data.model.FailureReason
import tr.trafy.kamera.ui.theme.ColorDestructive
import tr.trafy.kamera.ui.theme.ColorPrimary
import tr.trafy.kamera.ui.theme.ColorSuccess
import tr.trafy.kamera.ui.theme.ColorSurface
import tr.trafy.kamera.ui.theme.ColorTextPrimary
import tr.trafy.kamera.ui.theme.ColorTextSecondary

@Composable
fun DashcamConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    isScanning: Boolean = false,
    availableNetworks: List<String> = emptyList(),
    onNetworkSelected: (String) -> Unit = {},
    deviceName: String = "Trafy Dos",
    errorReason: FailureReason? = null,
    onConnectClick: () -> Unit,
    onLiveViewClick: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenLocationSettings: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenWifiSettings: () -> Unit = {},
    onOpenWifiPanel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                isConnected -> ConnectedState(
                    deviceName      = deviceName,
                    onLiveViewClick = onLiveViewClick,
                    onDisconnect    = onDisconnect
                )
                availableNetworks.isNotEmpty() -> NetworkSelectionState(
                    networks         = availableNetworks,
                    onNetworkSelected = onNetworkSelected
                )
                isScanning -> ScanningState()
                else -> DisconnectedState(
                    isConnecting           = isConnecting,
                    errorReason            = errorReason,
                    onConnectClick         = onConnectClick,
                    onOpenLocationSettings = onOpenLocationSettings,
                    onOpenAppSettings      = onOpenAppSettings,
                    onOpenWifiSettings     = onOpenWifiSettings,
                    onOpenWifiPanel        = onOpenWifiPanel,
                )
            }
        }
    }
}

@Composable
private fun ScanningState() {
    Icon(
        imageVector        = Icons.Filled.Wifi,
        contentDescription = stringResource(R.string.connection_scanning_cd),
        tint               = ColorTextSecondary,
        modifier           = Modifier.size(52.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text      = stringResource(R.string.connection_scanning_title),
        style     = MaterialTheme.typography.titleLarge,
        color     = ColorTextPrimary,
        textAlign = TextAlign.Center
    )
    CircularProgressIndicator(
        modifier    = Modifier
            .size(28.dp)
            .padding(top = 4.dp),
        strokeWidth = 2.dp,
        color       = ColorPrimary,
    )
    Text(
        text  = stringResource(R.string.connection_scanning_body),
        style = MaterialTheme.typography.bodySmall,
        color = ColorTextSecondary
    )
}

@Composable
private fun NetworkSelectionState(
    networks: List<String>,
    onNetworkSelected: (String) -> Unit,
) {
    Icon(
        imageVector        = Icons.Filled.Wifi,
        contentDescription = stringResource(R.string.connection_wifi_cd),
        tint               = ColorPrimary,
        modifier           = Modifier.size(52.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text  = stringResource(R.string.connection_multi_found_title),
        style = MaterialTheme.typography.titleLarge,
        color = ColorTextPrimary
    )
    Text(
        text  = stringResource(R.string.connection_multi_found_body),
        style = MaterialTheme.typography.bodyMedium,
        color = ColorTextSecondary
    )
    Spacer(modifier = Modifier.height(4.dp))
    networks.forEach { ssid ->
        Button(
            onClick  = { onNetworkSelected(ssid) },
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text     = ssid,
                style    = MaterialTheme.typography.titleMedium,
                color    = ColorTextPrimary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DisconnectedState(
    isConnecting: Boolean,
    errorReason: FailureReason?,
    onConnectClick: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenWifiPanel: () -> Unit,
) {
    // A failed attempt used to fall back to the plain "Connect" card, so users
    // saw the spinner end with no hint why. Say what went wrong and link the
    // settings screen that fixes it; hidden while a new attempt is running.
    val error = errorReason?.takeUnless { isConnecting }
    Icon(
        imageVector        = if (error != null) Icons.Filled.WifiOff else Icons.Filled.Wifi,
        contentDescription = stringResource(R.string.connection_wifi_cd),
        tint               = ColorTextSecondary,
        modifier           = Modifier.size(52.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text  = stringResource(
            if (error != null) R.string.connection_error_title
            else R.string.connection_disconnected_title
        ),
        style = MaterialTheme.typography.titleLarge,
        color = ColorTextPrimary
    )
    if (error != null) {
        Text(
            text      = stringResource(error.messageRes()),
            style     = MaterialTheme.typography.bodyMedium,
            color     = ColorTextSecondary,
            textAlign = TextAlign.Center
        )
    }
    Button(
        onClick  = onConnectClick,
        enabled  = !isConnecting,
        shape    = RoundedCornerShape(14.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        if (isConnecting) {
            CircularProgressIndicator(
                modifier    = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color       = ColorTextPrimary,
            )
        } else {
            Text(
                text     = stringResource(
                    if (error != null) R.string.connection_button_retry
                    else R.string.connection_button_connect
                ),
                style    = MaterialTheme.typography.titleMedium,
                color    = ColorTextPrimary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    if (error == null) {
        Text(
            text  = stringResource(
                if (isConnecting) R.string.connection_connecting_hint
                else R.string.connection_tap_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = ColorTextSecondary
        )
        return
    }
    when (error) {
        FailureReason.WIFI_DISABLED ->
            SettingsLink(R.string.connection_button_wifi_on, onOpenWifiPanel)
        // Scanning is blocked in both cases; joining the cam Wi-Fi manually still
        // works — the subnet fast path picks it up on return.
        FailureReason.LOCATION_SERVICES_OFF -> {
            SettingsLink(R.string.connection_button_location_settings, onOpenLocationSettings)
            SettingsLink(R.string.connection_button_wifi_settings, onOpenWifiSettings)
        }
        FailureReason.WIFI_PERMISSION_DENIED -> {
            SettingsLink(R.string.connection_button_app_settings, onOpenAppSettings)
            SettingsLink(R.string.connection_button_wifi_settings, onOpenWifiSettings)
        }
        FailureReason.NO_DASHCAM_FOUND,
        FailureReason.WIFI_CONNECT_FAILED ->
            SettingsLink(R.string.connection_button_wifi_settings, onOpenWifiSettings)
        else -> Unit
    }
}

@Composable
private fun SettingsLink(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text  = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = ColorPrimary
        )
    }
}

@StringRes
private fun FailureReason.messageRes(): Int = when (this) {
    FailureReason.WIFI_DISABLED          -> R.string.connection_error_wifi_off
    FailureReason.LOCATION_SERVICES_OFF  -> R.string.connection_error_location_off
    FailureReason.WIFI_PERMISSION_DENIED -> R.string.connection_error_permission
    FailureReason.NO_DASHCAM_FOUND       -> R.string.connection_error_no_dashcam
    FailureReason.WIFI_CONNECT_FAILED    -> R.string.connection_error_wifi_connect
    FailureReason.WIFI_NOT_CONNECTED,
    FailureReason.IP_NOT_OBTAINED        -> R.string.connection_error_no_ip
    FailureReason.ALL_PROTOCOLS_FAILED   -> R.string.connection_error_no_response
    FailureReason.CONNECTION_LOST        -> R.string.connection_error_lost
}

@Composable
private fun ConnectedState(
    deviceName: String,
    onLiveViewClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    Icon(
        imageVector        = Icons.Filled.CheckCircle,
        contentDescription = stringResource(R.string.connection_connected_cd),
        tint               = ColorSuccess,
        modifier           = Modifier.size(52.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text  = deviceName,
        style = MaterialTheme.typography.titleLarge,
        color = ColorTextPrimary
    )
    Text(
        text  = stringResource(R.string.connection_connected_label),
        style = MaterialTheme.typography.bodyMedium,
        color = ColorSuccess
    )
    Button(
        onClick  = onLiveViewClick,
        shape    = RoundedCornerShape(14.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Text(
            text  = stringResource(R.string.connection_button_live_view),
            style = MaterialTheme.typography.titleMedium,
            color = ColorTextPrimary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
    TextButton(onClick = onDisconnect) {
        Text(
            text  = stringResource(R.string.connection_button_disconnect),
            style = MaterialTheme.typography.bodyMedium,
            color = ColorDestructive
        )
    }
}
