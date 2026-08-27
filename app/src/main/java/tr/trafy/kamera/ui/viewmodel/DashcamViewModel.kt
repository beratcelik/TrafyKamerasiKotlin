package tr.trafy.kamera.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.withLock
import tr.trafy.kamera.data.handshake.DashcamHandshakeManager
import tr.trafy.kamera.data.media.EeasytechCleanupLock
import tr.trafy.kamera.data.model.ChipsetProtocol
import tr.trafy.kamera.data.model.DeviceInfo
import tr.trafy.kamera.data.model.FailureReason
import tr.trafy.kamera.data.model.HandshakeResult
import tr.trafy.kamera.data.allwinner.AllwinnerNetwork
import tr.trafy.kamera.data.allwinner.AllwinnerSessionHolder
import tr.trafy.kamera.data.generalplus.GeneralplusSession
import tr.trafy.kamera.data.network.DashcamHttpClient
import tr.trafy.kamera.data.settings.LastConnectedDevicePreferences
import tr.trafy.kamera.data.network.WifiIpProvider
import tr.trafy.kamera.data.wifi.DashcamWifiManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

        // Easytech firmware (HI3516CV610-B-FV-CARRECORDER class, e.g. Trafy
        // Tres Pro) has TWO timers that drop the Wi-Fi AP:
        //   - HTTP idle (~50s) — the cam's HTTP server stops responding,
        //     then the AP disassociates clients.
        //   - AP watchdog (~5min) — independent of HTTP idle. The cam
        //     voices "wifi hotspot is off" and disables the AP entirely
        //     (SSID disappears from the air, doesn't come back).
        //
        // The OEM Easytech app (PCAP captured 2026-06-17) defeats both by
        // pinging two endpoints every ~5s as a pair:
        //   GET /app/getdeviceattr
        //   GET /app/getparamvalue?param=rec
        // Sending only `getparamvalue` at 20s beat the HTTP idle timer but
        // did NOT prevent the 5-min AP watchdog — observed empirically:
        // cam dropped at 5min 25s of active 20s pings. `getdeviceattr`
        // appears to be the endpoint the AP watchdog actually listens for.
        // We mirror the OEM pattern exactly.
        private const val EASYTECH_KEEPALIVE_INTERVAL_MS = 5_000L

        // MStar / Hime3 (Trafy Tres) sleeps its Wi-Fi AP shortly after the last
        // client request — with no app talking to it the hotspot disappears
        // from scans entirely. The OEM Waycam app keeps it awake by POSTing
        // `Config.cgi?action=set&property=Heartbeat` on a timer
        // (WaycamHeartbeatManager). We mirror that; 5s matches the Easytech tick.
        private const val MSTAR_KEEPALIVE_INTERVAL_MS = 5_000L
    }

    private val manager = DashcamHandshakeManager(
        wifiIpProvider = WifiIpProvider(application),
    )

    private val wifiManager = DashcamWifiManager(application)

    private val _uiState = MutableStateFlow<DashcamUiState>(DashcamUiState.Idle)
    val uiState: StateFlow<DashcamUiState> = _uiState.asStateFlow()

    /**
     * One-shot guard for [ensureRecording]. HomeScreen's
     * `LaunchedEffect(uiState)` re-fires every time HomeScreen re-enters
     * composition — including the brief stack-pop pass that Compose does
     * when the user dismisses the app from a different tab. Without this
     * flag, every such re-entry would push another rec=1 at the cam.
     *
     * Reset whenever the cam connection drops (Wi-Fi loss → onConnectionLost),
     * so the next successful handshake gets one fresh rec=1.
     */
    @Volatile private var ensuredRecordingForCurrentSession: Boolean = false

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
        // One-shot per session: HomeScreen's LaunchedEffect re-fires on
        // every recomposition (including brief stack-pop passes during
        // app dismiss), so without this we'd push rec=1 at the cam over
        // and over for the lifetime of one connection. The exit chains
        // already do their own rec=1 — this is a safety net for the
        // initial post-handshake state, not a continuous heartbeat.
        if (ensuredRecordingForCurrentSession) return
        ensuredRecordingForCurrentSession = true
        viewModelScope.launch {
            // Route through the cleanup lock so a sibling exit chain
            // mid-flight (Live→Settings→Media tab-walk while uiState
            // ticks) doesn't race with us. The dedupe window also covers
            // the case where exitLive's rec=1 just landed — we'll see
            // `ranRecently` and skip cheaply.
            if (EeasytechCleanupLock.ranRecently()) return@launch
            EeasytechCleanupLock.mutex.withLock {
                if (EeasytechCleanupLock.ranRecently()) return@withLock
                DashcamHttpClient.probe(
                    "http://${device.protocol.deviceIp}/app/setparamvalue?param=rec&value=1"
                )
                EeasytechCleanupLock.markCompletion()
            }
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        ensuredRecordingForCurrentSession = false
        stopEasytechKeepalive()
        stopMstarKeepalive()
        wifiManager.release()
        DashcamHttpClient.bindToNetwork(null)
        GeneralplusSession.bindToNetwork(null)
        AllwinnerNetwork.bindToNetwork(null)
        AllwinnerSessionHolder.clear()
        _connectedNetwork.update { null }
        _uiState.update { DashcamUiState.Idle }
    }

    // ── Easytech keep-alive ───────────────────────────────────────────────
    // Single in-flight job; replacing it cancels the prior loop.
    private var easytechKeepaliveJob: Job? = null

    private fun startEasytechKeepalive(deviceIp: String) {
        easytechKeepaliveJob?.cancel()
        Log.i(TAG, "Easytech keepalive: starting (${EASYTECH_KEEPALIVE_INTERVAL_MS / 1000}s tick) → $deviceIp")
        easytechKeepaliveJob = viewModelScope.launch {
            // First tick after a full interval — handshake/recovery/clock-sync
            // already touched the cam, no need to ping again immediately.
            delay(EASYTECH_KEEPALIVE_INTERVAL_MS)
            var failuresInARow = 0
            while (isActive) {
                // OEM pattern: getdeviceattr then getparamvalue?param=rec
                // back-to-back. `getdeviceattr` is the one that appears to
                // pet the AP watchdog. We fire both because the OEM does and
                // diverging on a non-obvious cam-side timer is a footgun.
                val attrBody = runCatching {
                    DashcamHttpClient.get("http://$deviceIp/app/getdeviceattr")
                }.getOrNull()
                val recBody = runCatching {
                    DashcamHttpClient.get("http://$deviceIp/app/getparamvalue?param=rec")
                }.getOrNull()
                if (attrBody == null && recBody == null) {
                    failuresInARow += 1
                    Log.w(TAG, "Easytech keepalive: both pings returned null (failuresInARow=$failuresInARow)")
                } else {
                    if (failuresInARow > 0) {
                        Log.i(TAG, "Easytech keepalive: recovered after $failuresInARow null tick(s)")
                    }
                    failuresInARow = 0
                    // Single quiet line per tick — body shapes are tiny.
                    Log.v(TAG, "Easytech keepalive: ok (rec=${recBody?.take(40)})")
                }
                delay(EASYTECH_KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopEasytechKeepalive() {
        if (easytechKeepaliveJob != null) {
            Log.i(TAG, "Easytech keepalive: stopping")
            easytechKeepaliveJob?.cancel()
            easytechKeepaliveJob = null
        }
    }

    // ── MStar keep-alive ──────────────────────────────────────────────────────
    // Single in-flight job; replacing it cancels the prior loop.
    private var mstarKeepaliveJob: Job? = null

    private fun startMstarKeepalive(deviceIp: String) {
        mstarKeepaliveJob?.cancel()
        Log.i(TAG, "MStar keepalive: starting (${MSTAR_KEEPALIVE_INTERVAL_MS / 1000}s tick) → $deviceIp")
        mstarKeepaliveJob = viewModelScope.launch {
            // First tick after a full interval — the handshake just touched the cam.
            delay(MSTAR_KEEPALIVE_INTERVAL_MS)
            var failuresInARow = 0
            while (isActive) {
                // The OEM's WaycamHeartbeatManager pings this exact endpoint to
                // stop the cam sleeping its AP. It's a lightweight set with no
                // value; the cam serves it alongside RTSP/settings traffic.
                val body = runCatching {
                    DashcamHttpClient.get("http://$deviceIp/cgi-bin/Config.cgi?action=set&property=Heartbeat")
                }.getOrNull()
                if (body == null) {
                    failuresInARow += 1
                    Log.w(TAG, "MStar keepalive: heartbeat returned null (failuresInARow=$failuresInARow)")
                } else {
                    if (failuresInARow > 0) Log.i(TAG, "MStar keepalive: recovered after $failuresInARow null tick(s)")
                    failuresInARow = 0
                    Log.v(TAG, "MStar keepalive: ok")
                }
                delay(MSTAR_KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopMstarKeepalive() {
        if (mstarKeepaliveJob != null) {
            Log.i(TAG, "MStar keepalive: stopping")
            mstarKeepaliveJob?.cancel()
            mstarKeepaliveJob = null
        }
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
                // Remember this camera so bug/crash reports can name it later,
                // even after the phone leaves the dashcam hotspot.
                LastConnectedDevicePreferences.save(getApplication(), device)
                wifiManager.startWatchingConnection(network) { onConnectionLost() }

                // Push phone-local clock to the cam RTC. Fire-and-forget —
                // we don't block the UI on the time-sync call. Cams ship
                // with stale RTCs and lose time when powered off without a
                // backup battery, which produces wrong SD-card timestamps.
                // See [CamClockSync] for the per-chipset endpoint table.
                viewModelScope.launch {
                    runCatching {
                        tr.trafy.kamera.data.time.CamClockSync
                            .syncFromPhoneClock(device.protocol)
                    }.onFailure {
                        Log.w(TAG, "cam clock sync threw: ${it.message}")
                    }
                }

                // Easytech-only: if the previous session was force-stopped
                // mid-`exitLive()` (during the mandatory 500ms gap between
                // `exitrecorder` and `rec=1`), the cam is wedged with no
                // recording. A raw `rec=1` from a cold session returns
                // `set fail`, so we re-prime the state machine with an
                // enter/exit/rec=1 sequence. No-op when the cam reports
                // REC=1 already — common case, no recording interruption.
                if (device.protocol ==
                    tr.trafy.kamera.data.model.ChipsetProtocol.EEASYTECH
                ) {
                    viewModelScope.launch {
                        runCatching {
                            tr.trafy.kamera.data.media.EeasytechLiveRepository()
                                .ensureRecordingAfterRelaunch(device.protocol.deviceIp)
                        }.onFailure {
                            Log.w(TAG, "Easytech recovery threw: ${it.message}")
                        }
                    }
                    startEasytechKeepalive(device.protocol.deviceIp)
                }
                // MStar sleeps its AP without a client heartbeat — keep it awake.
                if (device.protocol ==
                    tr.trafy.kamera.data.model.ChipsetProtocol.MSTAR
                ) {
                    startMstarKeepalive(device.protocol.deviceIp)
                }
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
        stopEasytechKeepalive()
        stopMstarKeepalive()
        DashcamHttpClient.bindToNetwork(null)
        GeneralplusSession.bindToNetwork(null)
        AllwinnerNetwork.bindToNetwork(null)
        AllwinnerSessionHolder.clear()
        _connectedNetwork.update { null }
        // New connection ahead — let ensureRecording fire one fresh rec=1
        // when the next handshake succeeds.
        ensuredRecordingForCurrentSession = false

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
