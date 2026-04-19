package com.example.trafykamerasikotlin.data.wifi

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Handles WiFi scanning and connection to dashcam hotspots.
 *
 * - [scanForDashcams]: Scans visible networks and returns SSIDs matching known dashcam patterns.
 * - [connectToDashcam]: Connects to a specific SSID using the default dashcam password.
 *   On API 29+: WifiNetworkSpecifier + ConnectivityManager.requestNetwork (app-scoped, keeps
 *   mobile data active for internet traffic).
 *   On API 24-28: Legacy WifiConfiguration API (system-level connection).
 * - [getCurrentDashcamSsid]: Checks if the phone is already connected to a dashcam WiFi (no scan).
 * - [release]: Unregisters any active NetworkCallback (call on disconnect).
 */
class DashcamWifiManager(private val application: Application) {

    companion object {
        private const val TAG = "Trafy.WifiMgr"

        /** Case-insensitive substrings used to identify dashcam SSIDs. */
        private val DASHCAM_KEYWORDS = listOf("cardv", "dvr", "a19")

        /** Default WPA2 passphrase for all Trafy dashcam hotspots. */
        private const val DEFAULT_PASSWORD = "12345678"

        /** Timeout for API 29+ requestNetwork call (milliseconds). */
        private const val CONNECT_TIMEOUT_MS = 30_000
    }

    sealed class ConnectResult {
        /** [network] is non-null on API 29+ (WifiNetworkSpecifier path), null on legacy path. */
        data class Success(val network: Network?) : ConnectResult()
        data object Failure : ConnectResult()
    }

    @Volatile private var activeCallback: ConnectivityManager.NetworkCallback? = null

    /** Callback registered via [startWatchingConnection] to detect Wi-Fi loss after connect. */
    @Volatile private var lossWatcher: ConnectivityManager.NetworkCallback? = null

    /** Listener invoked exactly once when the watched Wi-Fi network goes away. */
    @Volatile private var connectionLostListener: (() -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the [Network] object for the currently-active WiFi connection, or null if not
     * connected to WiFi. Used by the "already connected" fast path so sockets can be explicitly
     * bound to the dashcam Wi-Fi network (required when cellular is also active — without binding
     * Android routes raw TCP via cellular instead of the local dashcam AP).
     */
    @Suppress("DEPRECATION")
    fun getCurrentWifiNetwork(): Network? {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Returns the SSID of the currently-connected WiFi if it matches a dashcam keyword,
     * or null otherwise. Does NOT trigger a scan and does NOT require location permission.
     */
    @Suppress("DEPRECATION")
    fun getCurrentDashcamSsid(): String? {
        val wm = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return null
        val raw = info.ssid ?: return null
        // Android wraps SSIDs in double-quotes: "\"SSID\""
        val ssid = raw.removePrefix("\"").removeSuffix("\"")
        return if (isDashcamSsid(ssid)) ssid else null
    }

    /**
     * Scans for available WiFi networks and returns only those whose SSID contains
     * a known dashcam keyword (case-insensitive).
     *
     * Requires ACCESS_FINE_LOCATION on API 26+ — caller must verify permission first.
     * Returns an empty list on scan failure or no matching networks.
     */
    suspend fun scanForDashcams(): List<String> = withContext(Dispatchers.IO) {
        val wm = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.i(TAG, "scanForDashcams: starting scan")

        val results = awaitScanResults(wm)
        val dashcamSsids = results
            .mapNotNull { it.SSID?.takeIf { s -> s.isNotEmpty() } }
            .filter { isDashcamSsid(it) }
            .distinct()

        Log.i(TAG, "scanForDashcams: found ${dashcamSsids.size} dashcam SSID(s): $dashcamSsids")
        dashcamSsids
    }

    /**
     * Connects to [ssid] using the default dashcam password.
     *
     * On API 29+: uses WifiNetworkSpecifier + requestNetwork. Android shows a one-tap system
     * confirmation dialog. Returns [ConnectResult.Success] with the bound [Network] object,
     * which must be used to route dashcam HTTP traffic.
     *
     * On API 24-28: uses legacy WifiConfiguration. Returns [ConnectResult.Success] with
     * network = null (system manages routing automatically).
     */
    suspend fun connectToDashcam(ssid: String): ConnectResult {
        Log.i(TAG, "connectToDashcam: ssid=$ssid")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectApi29(ssid)
        } else {
            connectLegacy(ssid)
        }
    }

    /**
     * Releases any active ConnectivityManager.NetworkCallback registered during connection
     * AND any passive loss watcher started via [startWatchingConnection].
     * Must be called when the user disconnects. No-op on API 24-28 for the requestNetwork callback.
     */
    fun release() {
        stopWatchingConnection()
        val cb = activeCallback ?: return
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
            Log.i(TAG, "release: NetworkCallback unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "release: callback was already unregistered")
        }
        activeCallback = null
    }

    /**
     * Starts observing the Wi-Fi network for loss. Call after a successful handshake.
     * Fires [listener] exactly once when:
     *   - the bound [boundNetwork] (API 29+ specifier path) reports onLost, OR
     *   - the system Wi-Fi transport reports onLost (legacy / already-connected fast path).
     *
     * Subsequent loss events are ignored until [startWatchingConnection] is called again.
     * Calling this again replaces any previously-registered watcher.
     */
    fun startWatchingConnection(boundNetwork: Network?, listener: () -> Unit) {
        stopWatchingConnection()
        connectionLostListener = listener

        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (boundNetwork != null && network != boundNetwork) {
                    // Some other Wi-Fi went away — ignore.
                    return
                }
                Log.w(TAG, "startWatchingConnection: onLost network=$network — dashcam Wi-Fi dropped")
                val l = connectionLostListener
                connectionLostListener = null  // single-shot
                l?.invoke()
            }
        }

        try {
            cm.registerNetworkCallback(request, cb)
            lossWatcher = cb
            Log.i(TAG, "startWatchingConnection: registered (boundNetwork=$boundNetwork)")
        } catch (e: SecurityException) {
            Log.e(TAG, "startWatchingConnection: SecurityException, cannot register watcher", e)
            connectionLostListener = null
        }
    }

    /** Cancels any active loss watcher. Idempotent. */
    fun stopWatchingConnection() {
        connectionLostListener = null
        val cb = lossWatcher ?: return
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
            Log.i(TAG, "stopWatchingConnection: watcher unregistered")
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
        lossWatcher = null
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun isDashcamSsid(ssid: String): Boolean =
        DASHCAM_KEYWORDS.any { keyword -> ssid.contains(keyword, ignoreCase = true) }

    /** Bridges the WiFi scan broadcast into a coroutine. */
    @Suppress("DEPRECATION")
    private suspend fun awaitScanResults(wm: WifiManager): List<android.net.wifi.ScanResult> =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try { application.unregisterReceiver(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(wm.scanResults ?: emptyList())
                }
            }
            application.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )
            cont.invokeOnCancellation {
                try { application.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
            val started = wm.startScan()
            if (!started) {
                // startScan() throttled or unavailable — fall back to cached results
                Log.w(TAG, "awaitScanResults: startScan() returned false, using cached results")
                try { application.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) cont.resume(wm.scanResults ?: emptyList())
            }
        }

    /** API 29+: WifiNetworkSpecifier + ConnectivityManager.requestNetwork. */
    private suspend fun connectApi29(ssid: String): ConnectResult =
        suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(DEFAULT_PASSWORD)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "connectApi29: onAvailable network=$network")
                    activeCallback = this
                    if (cont.isActive) cont.resume(ConnectResult.Success(network))
                }
                override fun onUnavailable() {
                    Log.w(TAG, "connectApi29: onUnavailable (timeout or user dismissed)")
                    if (cont.isActive) cont.resume(ConnectResult.Failure)
                }
            }

            // 4-argument overload with timeout (API 26+): Android calls onUnavailable after timeout
            cm.requestNetwork(request, callback, Handler(Looper.getMainLooper()), CONNECT_TIMEOUT_MS)

            cont.invokeOnCancellation {
                try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            }
        }

    /** API 24-28: Legacy WifiConfiguration. System manages dual connectivity automatically. */
    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String): ConnectResult {
        val wm = application.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val config = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$DEFAULT_PASSWORD\""
            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        val netId = wm.addNetwork(config)
        if (netId == -1) {
            Log.e(TAG, "connectLegacy: addNetwork() returned -1 for ssid=$ssid")
            return ConnectResult.Failure
        }

        wm.disconnect()
        val ok = wm.enableNetwork(netId, true)
        wm.reconnect()

        Log.i(TAG, "connectLegacy: enableNetwork=$ok, netId=$netId")
        return if (ok) ConnectResult.Success(network = null) else ConnectResult.Failure
    }
}
