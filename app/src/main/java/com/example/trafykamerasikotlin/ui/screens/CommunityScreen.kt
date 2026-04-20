package com.example.trafykamerasikotlin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.components.TrafyTopBar
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorIconBgBlue
import com.example.trafykamerasikotlin.ui.theme.ColorIconBgOrange
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary

@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
    ) {
        TrafyTopBar(title = stringResource(R.string.community_title), onBack = onBack)

        Column(
            modifier            = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text  = stringResource(R.string.community_body),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary
            )
            Card(
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = ColorSurface),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SocialLinkRow(
                        icon        = Icons.Filled.CameraAlt,
                        iconBgColor = ColorIconBgOrange,
                        platform    = "Instagram",
                        handle      = "@trafykamerasi",
                        onClick     = { uriHandler.openUri("https://instagram.com/trafykamerasi") }
                    )
                    HorizontalDivider(
                        color     = ColorDivider,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(start = 68.dp)
                    )
                    SocialLinkRow(
                        icon        = Icons.AutoMirrored.Filled.Send,
                        iconBgColor = ColorIconBgBlue,
                        platform    = "Telegram",
                        handle      = "trafy_kulubu",
                        onClick     = { uriHandler.openUri("https://t.me/trafy_kulubu") }
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialLinkRow(
    icon: ImageVector,
    iconBgColor: Color,
    platform: String,
    handle: String,
    onClick: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier         = Modifier
                .size(44.dp)
                .background(iconBgColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = platform,
                tint               = ColorPrimary,
                modifier           = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = platform,
                style = MaterialTheme.typography.titleMedium,
                color = ColorTextPrimary
            )
            Text(
                text  = handle,
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = ColorTextSecondary,
            modifier           = Modifier.size(20.dp)
        )
    }
}
