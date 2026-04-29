package com.example.trafykamerasikotlin.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.shop.TrafyProduct
import com.example.trafykamerasikotlin.data.shop.rememberTrafyImageLoader
import com.example.trafykamerasikotlin.ui.components.TrafyTopBar
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorSurface
import com.example.trafykamerasikotlin.ui.theme.ColorSurfaceElevated
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary
import com.example.trafykamerasikotlin.ui.viewmodel.ShopUiState
import com.example.trafykamerasikotlin.ui.viewmodel.ShopViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ShopScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShopViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Coil ImageLoader bound to an internet-capable network so product
    // images load via cellular even while the app is on the dashcam's
    // no-internet Wi-Fi hotspot.
    val imageLoader = rememberTrafyImageLoader()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground)
    ) {
        TrafyTopBar(title = stringResource(R.string.shop_title), onBack = onBack)

        when (val s = state) {
            is ShopUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ColorPrimary)
                }
            }
            is ShopUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text  = stringResource(R.string.shop_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorTextSecondary,
                        )
                        TextButton(onClick = { viewModel.reload() }) {
                            Text(
                                text  = stringResource(R.string.common_retry),
                                color = ColorPrimary,
                            )
                        }
                    }
                }
            }
            is ShopUiState.Loaded -> {
                LazyColumn(
                    contentPadding      = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.products, key = { it.slug }) { product ->
                        ProductCard(
                            product     = product,
                            imageLoader = imageLoader,
                            onPurchase  = {
                                // In stock → checkout flow; out of stock → pre-order
                                // form. Both URLs share the same `?product=<slug>` shape.
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(product.purchaseUrl))
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: TrafyProduct,
    imageLoader: ImageLoader,
    onPurchase: () -> Unit,
) {
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = ColorSurface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier  = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Product image. Coil's default ImageLoader handles memory + disk
            // caching automatically — repeat visits load from disk, not network.
            // Falls back to a labelled placeholder when imageUrl is null
            // (i.e. the API response doesn't yet expose images).
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(ColorSurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (product.imageUrl != null) {
                    // Fit (not Crop) — ürün görseli tamamen görünsün, kenarları
                    // kırpılıp zoom-in hissi vermesin. Kart yüksekliğini biraz
                    // artırıp Fit'in olası letterbox boşluklarını da temiz tutuyoruz.
                    AsyncImage(
                        model              = ImageRequest.Builder(LocalContext.current)
                            .data(product.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = product.name,
                        contentScale       = ContentScale.Fit,
                        imageLoader        = imageLoader,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(12.dp),
                    )
                } else {
                    Text(
                        text  = product.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary,
                    )
                }
            }
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment     = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = ColorTextPrimary,
                    )
                    if (!product.description.isNullOrBlank()) {
                        Text(
                            text     = product.description,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = ColorTextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text     = formatPriceTl(product.priceTl),
                        style    = MaterialTheme.typography.titleMedium,
                        color    = ColorPrimary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (!product.inStock) {
                        Text(
                            text     = stringResource(R.string.shop_out_of_stock),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = ColorTextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Button(
                    onClick  = onPurchase,
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(
                        text  = stringResource(
                            if (product.inStock) R.string.shop_action_buy
                            else                 R.string.shop_action_preorder
                        ),
                        color = ColorTextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun formatPriceTl(priceTl: Int): String {
    val nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR"))
    return "₺${nf.format(priceTl)}"
}
