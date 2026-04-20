package com.example.trafykamerasikotlin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.components.TrafyTopBar
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorSurfaceElevated
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary

data class ShopProduct(val name: String, val subtitle: String, val price: String)

private val dashcamProducts = listOf(
    ShopProduct("Trafy Pro 4K", "Front & rear, 4K HDR, GPS", "₺2,499"),
    ShopProduct("Trafy Uno", "Single channel, 1080p, WiFi", "₺1,299"),
    ShopProduct("Trafy Dos", "Dual channel, 2K, Night Vision", "₺1,899"),
)

private val sdCardProducts = listOf(
    ShopProduct("Samsung Pro Endurance 128GB", "High endurance, 4K ready", "₺349"),
    ShopProduct("SanDisk High Endurance 256GB", "Designed for dashcams", "₺549"),
)

@Composable
fun ShopScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
    ) {
        TrafyTopBar(title = stringResource(R.string.shop_title), onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text     = stringResource(R.string.shop_section_dashcams),
                    style    = MaterialTheme.typography.titleMedium,
                    color    = ColorTextSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(dashcamProducts) { product ->
                ProductCard(product = product)
            }
            item {
                Text(
                    text     = stringResource(R.string.shop_section_sd_cards),
                    style    = MaterialTheme.typography.titleMedium,
                    color    = ColorTextSecondary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(sdCardProducts) { product ->
                ProductCard(product = product)
            }
        }
    }
}

@Composable
private fun ProductCard(product: ShopProduct) {
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column {
            // Placeholder image area
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(ColorSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = product.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorTextSecondary
                )
            }
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = ColorTextPrimary
                    )
                    Text(
                        text  = product.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary
                    )
                    Text(
                        text  = product.price,
                        style = MaterialTheme.typography.titleMedium,
                        color = ColorPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Button(
                    onClick  = {},
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text(
                        text  = stringResource(R.string.shop_product_view),
                        color = ColorTextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
