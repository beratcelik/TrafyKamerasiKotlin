package tr.trafy.kamera.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
import tr.trafy.kamera.ui.viewmodel.DashcamUiState
import tr.trafy.kamera.ui.viewmodel.DashcamViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tr.trafy.kamera.R
import tr.trafy.kamera.ui.components.DashcamConnectionCard
import tr.trafy.kamera.ui.theme.ColorBackground
import tr.trafy.kamera.ui.theme.ColorPrimary
import tr.trafy.kamera.ui.theme.ColorSurface
import tr.trafy.kamera.ui.theme.ColorTextPrimary
import tr.trafy.kamera.ui.theme.ColorTextSecondary

@Composable
fun HomeScreen(
    onNavigateToLive: () -> Unit = {},
    onNavigateToShop: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DashcamViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission launcher — triggered when ViewModel reaches WifiPermissionRequired state.
    // The ViewModel re-checks what was actually granted and either resumes connecting or
    // shows the denial with a link to the app's permission settings.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.onScanPermissionResult()
    }

    // Auto-trigger permission request when the ViewModel signals it's needed. Each request
    // is a distinct state value, so tapping Connect again re-launches the prompt.
    LaunchedEffect(uiState) {
        if (uiState is DashcamUiState.WifiPermissionRequired) {
            permLauncher.launch(viewModel.scanPermissions())
        }
    }

    // Belt-and-suspenders: every time the user lands on the Home tab with a
    // connected cam, tell it to (re-)start recording. Live's `enterrecorder`
    // pauses the encoder; if the user crashes out of Live or the exit path
    // gets skipped for any reason, this ensures the cam is back to recording
    // by the time the user is on the home screen.
    LaunchedEffect(uiState) {
        if (uiState is DashcamUiState.Connected) viewModel.ensureRecording()
    }

    val isConnected     = uiState is DashcamUiState.Connected
    val isConnecting    = uiState is DashcamUiState.Connecting
    val isScanning      = uiState is DashcamUiState.ScanningWifi
    val availableNets   = (uiState as? DashcamUiState.WifiFound)?.networks ?: emptyList()
    val errorReason     = (uiState as? DashcamUiState.Error)?.reason
    val defaultDeviceName = stringResource(R.string.home_default_device_name)
    // Resolve the friendly Trafy product name from the device's `model`
    // string (returned by getdeviceattr.cgi). Falls back to the chipset's
    // generic name (e.g. "HiSilicon DVR") for unmapped Trafy SKUs — keeps
    // unknown future hardware connectable while still showing a name.
    val connectedDevice = (uiState as? DashcamUiState.Connected)?.device
    val deviceName = connectedDevice?.let {
        tr.trafy.kamera.data.model.TrafyModelIdentifier.displayName(
            device   = it,
            fallback = it.protocol.displayName,
        )
    } ?: defaultDeviceName

    // Opens a system settings screen from the connection error card. Launched in our
    // task so Back returns here, where DashcamViewModel.onAppResumed retries.
    val openSettings: (Intent) -> Unit = { intent ->
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

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
                text  = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = ColorTextPrimary
            )
            Text(
                text  = stringResource(R.string.home_tagline),
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
            errorReason       = errorReason,
            onConnectClick    = viewModel::connect,
            onLiveViewClick   = onNavigateToLive,
            onDisconnect      = viewModel::disconnect,
            onOpenWifiPanel   = {
                openSettings(
                    Intent(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_WIFI
                        } else {
                            Settings.ACTION_WIFI_SETTINGS
                        }
                    )
                )
            },
            onOpenLocationSettings = {
                openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            onOpenAppSettings = {
                openSettings(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
            onOpenWifiSettings = {
                openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
            },
            modifier          = Modifier.padding(vertical = 8.dp)
        )

        // Shortcut row
        HomeShortcutRow(
            onShopClick      = onNavigateToShop,
            onCommunityClick = onNavigateToCommunity,
            onSupportClick   = {
                // wa.me link opens the WhatsApp app directly when installed,
                // and falls back to the WhatsApp web/install page otherwise.
                // Number is in international format with no '+' or spaces.
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/905554005342")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.support_whatsapp_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
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
            label   = stringResource(R.string.home_shortcut_shop),
            onClick = onShopClick
        )
        HomeShortcutItem(
            icon    = Icons.Filled.Groups,
            label   = stringResource(R.string.home_shortcut_community),
            onClick = onCommunityClick
        )
        HomeShortcutItem(
            icon    = Icons.Filled.SupportAgent,
            label   = stringResource(R.string.home_shortcut_support),
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
