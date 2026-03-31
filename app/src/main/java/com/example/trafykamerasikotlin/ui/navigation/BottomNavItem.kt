package com.example.trafykamerasikotlin.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home     : BottomNavItem("home",     "Home",     Icons.Filled.Home)
    data object Live     : BottomNavItem("live",     "Live",     Icons.Filled.Videocam)
    data object Media    : BottomNavItem("media",    "Media",    Icons.Filled.PermMedia)
    data object Settings : BottomNavItem("settings", "Settings", Icons.Filled.Settings)
    data object More     : BottomNavItem("more",     "More",     Icons.Filled.MoreHoriz)

    companion object {
        val all: List<BottomNavItem> = listOf(Home, Live, Media, Settings, More)
    }
}
