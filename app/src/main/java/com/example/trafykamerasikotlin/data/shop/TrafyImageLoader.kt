package com.example.trafykamerasikotlin.data.shop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import okhttp3.OkHttpClient

/**
 * Compose helper that returns a Coil [ImageLoader] whose underlying OkHttp
 * client is pinned to an internet-capable network (cellular in the typical
 * case). Without this, image fetches would inherit the process's default
 * routing — which mostly works, but breaks on OEMs that misroute or once
 * we ever bind the process to the dashcam's no-internet hotspot.
 *
 * Coil's default disk + memory caches are kept (we only override the
 * network call factory). So images are still cached across app launches
 * and don't burn cellular data on repeat visits.
 */
@Composable
fun rememberTrafyImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        val builder = ImageLoader.Builder(context.applicationContext)
        TrafyInternetRouting.internetNetwork(context)?.let { network ->
            val ok = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .build()
            builder.okHttpClient(ok)
        }
        builder.build()
    }
}
