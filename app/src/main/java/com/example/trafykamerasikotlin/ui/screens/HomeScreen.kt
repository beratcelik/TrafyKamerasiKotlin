package com.example.trafykamerasikotlin.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trafykamerasikotlin.ui.viewmodel.DashcamUiState
import com.example.trafykamerasikotlin.ui.viewmodel.DashcamViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.ui.components.DashcamConnectionCard
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary

@Composable
fun HomeScreen(
    onNavigateToLive: () -> Unit = {},
    onNavigateToShop: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DashcamViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Permission launcher — triggered when ViewModel reaches WifiPermissionRequired state
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.connect()
        // If denied, uiState stays at WifiPermissionRequired; Connect button re-enables
        // so the user can tap again and trigger the launcher again.
    }

    // Auto-trigger permission request when the ViewModel signals it's needed
    LaunchedEffect(uiState) {
        if (uiState is DashcamUiState.WifiPermissionRequired) {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val isConnected     = uiState is DashcamUiState.Connected
    val isConnecting    = uiState is DashcamUiState.Connecting
    val isScanning      = uiState is DashcamUiState.ScanningWifi
    val availableNets   = (uiState as? DashcamUiState.WifiFound)?.networks ?: emptyList()
    val deviceName      = (uiState as? DashcamUiState.Connected)?.device?.protocol?.displayName
        ?: "Dashcam"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text  = "Trafy Kamerası",
                style = MaterialTheme.typography.displayMedium,
                color = ColorTextPrimary
            )
            Text(
                text  = "Your Journey, Protected",
                style = MaterialTheme.typography.bodyLarge,
                color = ColorTextSecondary
            )
        }

        // Connection card
        DashcamConnectionCard(
            isConnected       = isConnected,
            isConnecting      = isConnecting,
            isScanning        = isScanning,
            availableNetworks = availableNets,
            onNetworkSelected = viewModel::selectWifi,
            deviceName        = deviceName,
            onConnectClick    = viewModel::connect,
            onLiveViewClick   = onNavigateToLive,
            onDisconnect      = viewModel::disconnect,
            modifier          = Modifier.padding(vertical = 8.dp)
        )

        // Shortcut row
        HomeShortcutRow(
            onShopClick      = onNavigateToShop,
            onCommunityClick = onNavigateToCommunity,
            onSupportClick   = {}
        )
    }
}

@Composable
private fun HomeShortcutRow(
    onShopClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onSupportClick: () -> Unit
) {
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        HomeShortcutItem(
            icon    = Icons.Filled.ShoppingBag,
            label   = "Shop",
            onClick = onShopClick
        )
        HomeShortcutItem(
            icon    = Icons.Filled.Groups,
            label   = "Community",
            onClick = onCommunityClick
        )
        HomeShortcutItem(
            icon    = Icons.Filled.SupportAgent,
            label   = "Support",
            onClick = onSupportClick
        )
    }
}

@Composable
private fun HomeShortcutItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier            = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier         = Modifier
                .size(56.dp)
                .background(ColorSurface, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = ColorPrimary,
                modifier           = Modifier.size(24.dp)
            )
        }
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = ColorTextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}
