package com.example.trafykamerasikotlin.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.handshake.DashcamHandshakeManager
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.FailureReason
import com.example.trafykamerasikotlin.data.model.HandshakeResult
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerNetwork
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import com.example.trafykamerasikotlin.data.network.WifiIpProvider
import com.example.trafykamerasikotlin.data.wifi.DashcamWifiManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed class DashcamUiState {
    data object Idle : DashcamUiState()
    data object ScanningWifi : DashcamUiState()
    data class WifiFound(val networks: List<String>) : DashcamUiState()
    data object WifiPermissionRequired : DashcamUiState()
    data object Connecting : DashcamUiState()
    data class Connected(val device: DeviceInfo) : DashcamUiState()
    data class Error(val reason: FailureReason) : DashcamUiState()
}

class DashcamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Trafy.ViewModel"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val DHCP_SETTLE_DELAY_MS = 1_500L
        // Dashcam APs (notably Trafy Uno's CarDV firmware) periodically drop
        // Wi-Fi clients during streaming/downloads. The OS reports this as
        // NetworkCallback.onLost and revokes the bound network. We auto-fire
        // connect() to reattach transparently; cap the rate so a truly-gone
        // dashcam (user drove away) eventually surfaces the error.
        private const val AUTO_RECONNECT_DELAY_MS = 1_500L
        private const val AUTO_RECONNECT_WINDOW_MS = 90_000L
        private const val MAX_AUTO_RECONNECTS_IN_WINDOW = 4
    }

    private val manager = DashcamHandshakeManager(
        wifiIpProvider = WifiIpProvider(application),
    )

    private val wifiManager = DashcamWifiManager(application)

    private val _uiState = MutableStateFlow<DashcamUiState>(DashcamUiState.Idle)
    val uiState: StateFlow<DashcamUiState> = _uiState.asStateFlow()

    /** The Network object obtained after WifiNetworkSpecifier connection (API 29+).
     *  Null on legacy path or when already connected manually. Exposed for LiveViewModel. */
    private val _connectedNetwork = MutableStateFlow<Network?>(null)
    val connectedNetwork: StateFlow<Network?> = _connectedNetwork.asStateFlow()

    init {
        // The app's primary job is to be paired with a dashcam, so kick off the
        // scan/connect flow as soon as the ViewModel is created — no manual
        // "Bağlan" tap required. Runs once per process; explicit disconnect
        // returns to Idle and stays there because init won't fire again.
        Log.i(TAG, "init: auto-triggering connect()")
        connect()
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Main entry point. Runs the full flow:
     *   already-on-dashcam → skip scan
     *   no permission → request permission
     *   scan → auto-connect (1 result) or show picker (multiple results)
     */
    fun connect() {
        val current = _uiState.value
        if (current is DashcamUiState.Connecting || current is DashcamUiState.ScanningWifi) {
            Log.w(TAG, "connect() ignored — already in $current")
            return
        }

        viewModelScope.launch {
            // ── Fast path: already on dashcam WiFi ─────────────────────────────
            val currentSsid = wifiManager.getCurrentDashcamSsid()
            if (currentSsid != null) {
                Log.i(TAG, "Already on dashcam WiFi: $currentSsid — skipping scan")
                // Bind all network clients to the Wi-Fi network explicitly. Without this, raw
                // TCP sockets (e.g. Allwinner) are routed via cellular when mobile data is
                // active, even though the dashcam AP is reachable over Wi-Fi.
                val wifiNetwork = wifiManager.getCurrentWifiNetwork()
                if (wifiNetwork != null) {
                    Log.i(TAG, "Binding network clients to Wi-Fi network $wifiNetwork")
                    DashcamHttpClient.bindToNetwork(wifiNetwork)
                    GeneralplusSession.bindToNetwork(wifiNetwork)
                    AllwinnerNetwork.bindToNetwork(wifiNetwork)
                    _connectedNetwork.update { wifiNetwork }
                } else {
                    Log.w(TAG, "getCurrentWifiNetwork() returned null — proceeding unbound")
                }
                _uiState.update { DashcamUiState.Connecting }
                proceedWithHandshake(network = wifiNetwork)
                return@launch
            }

            // ── Location permission check ───────────────────────────────────────
            val permGranted = ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!permGranted) {
                Log.i(TAG, "Location permission not granted — requesting")
                _uiState.update { DashcamUiState.WifiPermissionRequired }
                return@launch
            }

            // ── Scan ────────────────────────────────────────────────────────────
            _uiState.update { DashcamUiState.ScanningWifi }
            Log.i(TAG, "State → ScanningWifi")

            val found = try {
                withTimeout(SCAN_TIMEOUT_MS) { wifiManager.scanForDashcams() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Scan timed out")
                emptyList()
            }

            when {
                found.isEmpty() -> {
                    Log.w(TAG, "No dashcam SSIDs found")
                    _uiState.update { DashcamUiState.Error(FailureReason.NO_DASHCAM_FOUND) }
                }
                found.size == 1 -> {
                    Log.i(TAG, "Single dashcam found: ${found.first()} — auto-connecting")
                    _uiState.update { DashcamUiState.Connecting }
                    connectToSsid(found.first())
                }
                else -> {
                    Log.i(TAG, "Multiple dashcams found: $found — showing picker")
                    _uiState.update { DashcamUiState.WifiFound(found) }
                }
            }
        }
    }

    /** Called when the user picks a network from the WifiFound list. */
    fun selectWifi(ssid: String) {
        Log.i(TAG, "selectWifi: $ssid")
        viewModelScope.launch {
            _uiState.update { DashcamUiState.Connecting }
            connectToSsid(ssid)
        }
    }

    /**
     * Tells the cam to (re-)start SD recording. Idempotent — if recording is
     * already running, the cam happily ack's and the call is a no-op. Fired
     * by HomeScreen when the user lands on the main page so any mode that
     * paused recording (Live preview's `enterrecorder`, etc.) can't leak a
     * permanently-paused cam.
     *
     * Currently only meaningful for Easytech (Trafy Dos / Tres family) —
     * HiSilicon and GeneralPlus expose their own resume paths that already
     * fire on screen exit.
     */
    fun ensureRecording() {
        val device = (_uiState.value as? DashcamUiState.Connected)?.device ?: return
        if (device.protocol != ChipsetProtocol.EEASYTECH) return
        viewModelScope.launch {
            DashcamHttpClient.probe(
                "http://${device.protocol.deviceIp}/app/setparamvalue?param=rec&value=1"
            )
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        wifiManager.release()
        DashcamHttpClient.bindToNetwork(null)
        GeneralplusSession.bindToNetwork(null)
        AllwinnerNetwork.bindToNetwork(null)
        AllwinnerSessionHolder.clear()
        _connectedNetwork.update { null }
        _uiState.update { DashcamUiState.Idle }
    }

    override fun onCleared() {
        super.onCleared()
        wifiManager.release()
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private suspend fun connectToSsid(ssid: String) {
        Log.i(TAG, "connectToSsid: $ssid")
        when (val result = wifiManager.connectToDashcam(ssid)) {
            is DashcamWifiManager.ConnectResult.Success -> {
                _connectedNetwork.update { result.network }
                result.network?.let {
                    DashcamHttpClient.bindToNetwork(it)
                    GeneralplusSession.bindToNetwork(it)
                    AllwinnerNetwork.bindToNetwork(it)
                }
                proceedWithHandshake(result.network)
            }
            is DashcamWifiManager.ConnectResult.Failure -> {
                Log.e(TAG, "connectToSsid: WiFi connect failed for $ssid")
                _uiState.update { DashcamUiState.Error(FailureReason.WIFI_CONNECT_FAILED) }
            }
        }
    }

    private suspend fun proceedWithHandshake(network: Network?) {
        // Give the dashcam's DHCP server a moment to assign an IP to the phone
        if (network != null) {
            Log.i(TAG, "proceedWithHandshake: waiting ${DHCP_SETTLE_DELAY_MS}ms for DHCP")
            delay(DHCP_SETTLE_DELAY_MS)
        }
        Log.i(TAG, "proceedWithHandshake: calling manager.connect()")
        when (val result = manager.connect()) {
            is HandshakeResult.Success -> {
                // Stamp the connected SSID onto the DeviceInfo so the rest of
                // the app (TrafyModelIdentifier in particular) can identify
                // products whose firmware doesn't expose a model string —
                // Easytech-based cams only advertise a feature bitmask over
                // HTTP, so the SSID prefix is our only distinguishing signal.
                val ssid = wifiManager.getCurrentDashcamSsid()
                val device = result.deviceInfo.copy(ssid = ssid)
                Log.i(TAG, "Handshake SUCCESS: $device")
                _uiState.update { DashcamUiState.Connected(device) }
                wifiManager.startWatchingConnection(network) { onConnectionLost() }
            }
            is HandshakeResult.Failure -> {
                Log.e(TAG, "Handshake FAILURE: ${result.reason}")
                _uiState.update { DashcamUiState.Error(result.reason) }
            }
        }
    }

    /** Sliding window of recent auto-reconnect timestamps (epoch ms). */
    private val autoReconnectTimestamps = mutableListOf<Long>()

    /** Invoked by [DashcamWifiManager] when the dashcam Wi-Fi disappears unexpectedly. */
    private fun onConnectionLost() {
        Log.w(TAG, "onConnectionLost: dashcam Wi-Fi dropped — clearing bindings")
        DashcamHttpClient.bindToNetwork(null)
        GeneralplusSession.bindToNetwork(null)
        AllwinnerNetwork.bindToNetwork(null)
        AllwinnerSessionHolder.clear()
        _connectedNetwork.update { null }

        val now = System.currentTimeMillis()
        autoReconnectTimestamps.removeAll { now - it > AUTO_RECONNECT_WINDOW_MS }
        if (autoReconnectTimestamps.size >= MAX_AUTO_RECONNECTS_IN_WINDOW) {
            Log.w(TAG, "onConnectionLost: ${autoReconnectTimestamps.size} auto-reconnects in last " +
                "${AUTO_RECONNECT_WINDOW_MS / 1000}s — giving up, user must reconnect manually")
            _uiState.update { DashcamUiState.Error(FailureReason.CONNECTION_LOST) }
            return
        }
        autoReconnectTimestamps.add(now)

        Log.i(TAG, "onConnectionLost: auto-reconnecting in ${AUTO_RECONNECT_DELAY_MS}ms " +
            "(attempt ${autoReconnectTimestamps.size}/$MAX_AUTO_RECONNECTS_IN_WINDOW per ${AUTO_RECONNECT_WINDOW_MS / 1000}s)")
        // Show the scan/connect spinner straight away so the user never sees
        // a stale "Connected" / "Error" between drop and recovery.
        _uiState.update { DashcamUiState.ScanningWifi }
        viewModelScope.launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            // connect() guards against re-entry while in ScanningWifi/Connecting,
            // but our state is the freshly-set ScanningWifi which would block
            // it. Roll back to Idle right before firing so the normal flow runs.
            _uiState.update { DashcamUiState.Idle }
            connect()
        }
    }
}
