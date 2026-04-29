package com.example.trafykamerasikotlin.data.shop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Helper for forcing trafy.tr requests onto an internet-capable network
 * (cellular in the typical case) while the app is bound to the dashcam's
 * Wi-Fi hotspot — which has no internet.
 *
 * Today the system default is already cellular (dashcam Wi-Fi never gets
 * `NET_CAPABILITY_VALIDATED`), so unbound requests work in 99% of cases.
 * We bind explicitly anyway so that:
 *   - OEM ROMs that misroute don't break the Shop screen
 *   - Future code that calls `process.bindToNetwork(dashcamNetwork)`
 *     doesn't accidentally pin trafy.tr requests to the dashcam too.
 */
object TrafyInternetRouting {
    private const val TAG = "Trafy.Net"

    /**
     * Finds a Network object with `NET_CAPABILITY_INTERNET`. Prefers the
     * system default (which is internet-validated), falls back to scanning
     * all networks. Returns null only if the device has no usable connection
     * to the outside world (airplane mode, etc.).
     */
    fun internetNetwork(context: Context): Network? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { active ->
            if (cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                return active
            }
        }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.let { caps ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }.also { picked ->
            if (picked == null) Log.w(TAG, "internetNetwork: no internet-capable network found")
        }
    }
}
