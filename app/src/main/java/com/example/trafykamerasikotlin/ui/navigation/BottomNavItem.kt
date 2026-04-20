package com.example.trafykamerasikotlin.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.trafykamerasikotlin.R

sealed class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    data object Home     : BottomNavItem("home",     R.string.nav_home,     Icons.Filled.Home)
    data object Live     : BottomNavItem("live",     R.string.nav_live,     Icons.Filled.Videocam)
    data object Media    : BottomNavItem("media",    R.string.nav_media,    Icons.Filled.PermMedia)
    data object Settings : BottomNavItem("settings", R.string.nav_settings, Icons.Filled.Settings)
    data object More     : BottomNavItem("more",     R.string.nav_more,     Icons.Filled.MoreHoriz)

    companion object {
        val all: List<BottomNavItem> = listOf(Home, Live, Media, Settings, More)
    }
}
