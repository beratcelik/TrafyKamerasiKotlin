package com.example.trafykamerasikotlin.data.media

import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import okhttp3.OkHttpClient

/**
 * Compose helper that returns a Coil [ImageLoader] whose underlying OkHttp
 * client is pinned to the dashcam's Wi-Fi network. Used by the Media tab to
 * load remote thumbnails (e.g. HiDVR `.thm` sidecars at `http://192.168.0.1/...`).
 *
 * Why this is required: Coil's global ImageLoader routes through the system
 * default network. While the app is bound to the dashcam Wi-Fi via
 * `WifiNetworkSpecifier`, the system default stays on cellular (the dashcam
 * AP isn't internet-validated), so default-routed requests to `192.168.0.1`
 * never reach the cam — they'd try to go out over LTE and fail.
 *
 * The returned ImageLoader rebuilds whenever [network] changes (e.g. after
 * an auto-reconnect). When [network] is null we fall back to the default
 * loader, which is fine for local-file thumbnails (e.g. GP `.jpg` cache).
 */
@Composable
fun rememberDashcamImageLoader(network: Network?): ImageLoader {
    val context = LocalContext.current
    return remember(network) {
        val builder = ImageLoader.Builder(context.applicationContext)
            .components {
                // Custom scheme: hidvr-thumb://<ip>/<path> → extract first
                // frame of the MP4 client-side. Used for HiDVR-family cams
                // whose firmware doesn't generate `.thm` sidecars.
                add(HiDvrThumbnailFetcher.Factory(context.applicationContext, network))
            }
        if (network != null) {
            val ok = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .build()
            builder.okHttpClient(ok)
        }
        builder.build()
    }
}
