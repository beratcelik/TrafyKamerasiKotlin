package com.example.trafykamerasikotlin.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ShoppingBag
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorDivider
import com.example.trafykamerasikotlin.ui.theme.ColorIconBgBlue
import com.example.trafykamerasikotlin.ui.theme.ColorIconBgGreen
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary

@Composable
fun MoreScreen(
    onShopClick: () -> Unit,
    onCommunityClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
    ) {
        Text(
            text  = stringResource(R.string.more_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ColorTextPrimary
        )
        Spacer(modifier = Modifier.height(20.dp))
        Card(
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(containerColor = ColorSurface),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MoreListItem(
                    icon        = Icons.Filled.ShoppingBag,
                    iconBgColor = ColorIconBgBlue,
                    title       = stringResource(R.string.more_shop_title),
                    description = stringResource(R.string.more_shop_desc),
                    onClick     = onShopClick
                )
                HorizontalDivider(
                    color     = ColorDivider,
                    thickness = 0.5.dp,
                    modifier  = Modifier.padding(start = 68.dp)
                )
                MoreListItem(
                    icon        = Icons.Filled.Groups,
                    iconBgColor = ColorIconBgGreen,
                    title       = stringResource(R.string.more_community_title),
                    description = stringResource(R.string.more_community_desc),
                    onClick     = onCommunityClick
                )
            }
        }
    }
}

@Composable
private fun MoreListItem(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    description: String,
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
                contentDescription = title,
                tint               = ColorPrimary,
                modifier           = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.titleMedium,
                color = ColorTextPrimary
            )
            Text(
                text  = description,
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
