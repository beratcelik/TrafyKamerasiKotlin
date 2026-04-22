package com.example.trafykamerasikotlin.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.trafykamerasikotlin.ui.screens.CommunityScreen
import com.example.trafykamerasikotlin.ui.screens.RawSettingsScreen
import com.example.trafykamerasikotlin.ui.screens.HomeScreen
import com.example.trafykamerasikotlin.ui.screens.LiveScreen
import com.example.trafykamerasikotlin.ui.screens.MediaScreen
import com.example.trafykamerasikotlin.ui.screens.MoreScreen
import com.example.trafykamerasikotlin.ui.screens.SettingsScreen
import com.example.trafykamerasikotlin.ui.screens.ShopScreen
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorNavBar
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurfaceElevated
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.components.UpdateDialog
import com.example.trafykamerasikotlin.ui.viewmodel.DashcamUiState
import com.example.trafykamerasikotlin.ui.viewmodel.DashcamViewModel
import com.example.trafykamerasikotlin.ui.viewmodel.LiveViewModel
import com.example.trafykamerasikotlin.ui.viewmodel.MediaViewModel
import com.example.trafykamerasikotlin.ui.viewmodel.UpdateViewModel

private const val ROUTE_SHOP         = "shop"
private const val ROUTE_COMMUNITY    = "community"
private const val ROUTE_RAW_SETTINGS = "raw_settings"

private val bottomNavRoutes = BottomNavItem.all.map { it.route }.toSet()

@Composable
fun AppNavigation() {
    // Activity-scoped: shared across screens so they see the same connection state
    val dashcamViewModel: DashcamViewModel = viewModel()
    val mediaViewModel: MediaViewModel     = viewModel()
    val liveViewModel: LiveViewModel       = viewModel()
    val updateViewModel: UpdateViewModel   = viewModel()
    val uiState by dashcamViewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevice = (uiState as? DashcamUiState.Connected)?.device
    val connectedNetwork by dashcamViewModel.connectedNetwork.collectAsStateWithLifecycle()
    val updateState    by updateViewModel.state.collectAsStateWithLifecycle()
    val showUpdateDialog by updateViewModel.showFeedback.collectAsStateWithLifecycle()

    // Silent check at app launch; surfaces a dialog only when an update is actually available.
    LaunchedEffect(Unit) {
        updateViewModel.autoCheckOnStartup()
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // Fullscreen video chrome: on the Live tab (always) and on the Media tab
    // while a playback overlay has forced the activity into landscape, collapse
    // the bottom nav so the video owns the whole screen. Tilting back to
    // portrait restores everything; the overlays also handle system back.
    val isLandscape   = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val onLiveRoute   = currentDestination?.route == BottomNavItem.Live.route
    val onMediaRoute  = currentDestination?.route == BottomNavItem.Media.route
    val fullscreenTab = onLiveRoute || onMediaRoute
    val showBottomNav = currentDestination?.route in bottomNavRoutes && !(isLandscape && fullscreenTab)

    Scaffold(
        containerColor = ColorBackground,
        bottomBar = {
            if (showBottomNav) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color     = ColorDivider,
                        thickness = 0.5.dp
                    )
                    NavigationBar(
                        containerColor = ColorNavBar,
                        tonalElevation = 0.dp
                    ) {
                        BottomNavItem.all.forEach { item ->
                            val selected =
                                currentDestination?.hierarchy?.any { it.route == item.route } == true
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick  = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                },
                                icon     = {
                                    Icon(
                                        imageVector        = item.icon,
                                        contentDescription = label
                                    )
                                },
                                label    = {
                                    Text(
                                        text  = label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors   = NavigationBarItemDefaults.colors(
                                    selectedIconColor   = ColorPrimary,
                                    selectedTextColor   = ColorPrimary,
                                    unselectedIconColor = ColorTextSecondary,
                                    unselectedTextColor = ColorTextSecondary,
                                    indicatorColor      = ColorSurfaceElevated
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = BottomNavItem.Home.route,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavItem.Home.route) {
                HomeScreen(
                    viewModel             = dashcamViewModel,
                    onNavigateToLive      = {
                        navController.navigate(BottomNavItem.Live.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    },
                    onNavigateToShop      = { navController.navigate(ROUTE_SHOP) },
                    onNavigateToCommunity = { navController.navigate(ROUTE_COMMUNITY) }
                )
            }
            composable(BottomNavItem.Live.route) {
                LiveScreen(device = connectedDevice, network = connectedNetwork, viewModel = liveViewModel)
            }
            composable(BottomNavItem.Media.route) {
                MediaScreen(device = connectedDevice, network = connectedNetwork, viewModel = mediaViewModel)
            }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(
                    device          = connectedDevice,
                    onRawDump       = { navController.navigate(ROUTE_RAW_SETTINGS) },
                    onCheckUpdates  = { updateViewModel.manualCheck() },
                    appVersionName  = updateViewModel.installedVersionName,
                )
            }
            composable(ROUTE_RAW_SETTINGS) {
                RawSettingsScreen(device = connectedDevice)
            }
            composable(BottomNavItem.More.route) {
                MoreScreen(
                    onShopClick      = { navController.navigate(ROUTE_SHOP) },
                    onCommunityClick = { navController.navigate(ROUTE_COMMUNITY) }
                )
            }
            composable(ROUTE_SHOP) {
                ShopScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_COMMUNITY) {
                CommunityScreen(onBack = { navController.popBackStack() })
            }
        }

        if (showUpdateDialog) {
            UpdateDialog(
                state     = updateState,
                onUpdate  = { updateViewModel.downloadAndInstall() },
                onDismiss = { updateViewModel.dismiss() },
            )
        }
    }
}
