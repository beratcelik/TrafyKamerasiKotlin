package com.example.trafykamerasikotlin.ui.components

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
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.theme.ColorDestructive
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSuccess
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary

@Composable
fun DashcamConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    isScanning: Boolean = false,
    availableNetworks: List<String> = emptyList(),
    onNetworkSelected: (String) -> Unit = {},
    deviceName: String = "Trafy Dos",
    onConnectClick: () -> Unit,
    onLiveViewClick: () -> Unit,
    onDisconnect: () -> Unit,
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
                    isConnecting   = isConnecting,
                    onConnectClick = onConnectClick,
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
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
    onConnectClick: () -> Unit,
) {
    Icon(
        imageVector        = Icons.Filled.Wifi,
        contentDescription = stringResource(R.string.connection_wifi_cd),
        tint               = ColorTextSecondary,
        modifier           = Modifier.size(52.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text  = stringResource(R.string.connection_disconnected_title),
        style = MaterialTheme.typography.titleLarge,
        color = ColorTextPrimary
    )
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
                text     = stringResource(R.string.connection_button_connect),
                style    = MaterialTheme.typography.titleMedium,
                color    = ColorTextPrimary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    Text(
        text  = stringResource(
            if (isConnecting) R.string.connection_connecting_hint
            else R.string.connection_tap_hint
        ),
        style = MaterialTheme.typography.bodySmall,
        color = ColorTextSecondary
    )
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
