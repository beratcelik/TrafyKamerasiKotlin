package com.example.trafykamerasikotlin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TrafyDarkColorScheme = darkColorScheme(
    primary          = ColorPrimary,
    onPrimary        = ColorTextPrimary,
    background       = ColorBackground,
    onBackground     = ColorTextPrimary,
    surface          = ColorSurface,
    onSurface        = ColorTextPrimary,
    surfaceVariant   = ColorSurfaceElevated,
    onSurfaceVariant = ColorTextSecondary,
    secondary        = ColorSuccess,
    onSecondary      = ColorTextPrimary,
    error            = ColorDestructive,
    onError          = ColorTextPrimary,
    outline          = ColorDivider,
)

@Composable
fun TrafyKamerasiKotlinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrafyDarkColorScheme,
        typography  = Typography,
        content     = content
    )
}
