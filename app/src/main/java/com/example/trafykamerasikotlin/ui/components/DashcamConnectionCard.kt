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
import androidx.compose.ui.unit.dp
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
            if (isConnected) {
                ConnectedState(
                    deviceName      = deviceName,
                    onLiveViewClick = onLiveViewClick,
                    onDisconnect    = onDisconnect
                )
            } else {
                DisconnectedState(
                    isConnecting   = isConnecting,
                    onConnectClick = onConnectClick,
                )
            }
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
        contentDescription = "WiFi",
        tint               = ColorTextSecondary,
        modifier           = Modifier.size(52.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text  = "Connect to Dashcam",
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
                text     = "Connect",
                style    = MaterialTheme.typography.titleMedium,
                color    = ColorTextPrimary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    Text(
        text  = if (isConnecting) "Identifying dashcam…" else "Make sure WiFi is connected to your dashcam",
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
        contentDescription = "Connected",
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
        text  = "Connected",
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
            text  = "Open Live View",
            style = MaterialTheme.typography.titleMedium,
            color = ColorTextPrimary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
    TextButton(onClick = onDisconnect) {
        Text(
            text  = "Disconnect",
            style = MaterialTheme.typography.bodyMedium,
            color = ColorDestructive
        )
    }
}
