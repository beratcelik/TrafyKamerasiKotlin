package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.capture.AllwinnerCaptureRepository
import com.example.trafykamerasikotlin.data.capture.AllwinnerCaptureResult
import com.example.trafykamerasikotlin.data.media.EeasytechLiveRepository
import com.example.trafykamerasikotlin.data.media.GeneralplusLiveRepository
import com.example.trafykamerasikotlin.data.media.MstarLiveRepository
import com.example.trafykamerasikotlin.data.media.HiDvrMediaRepository
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class LiveUiState {
    object NotConnected : LiveUiState()
    object Preparing    : LiveUiState()
    data class Playing(
        val rtspUrl       : String,
        /** Non-empty only for Easytech multi-camera devices. Each entry is a label
         *  ("Camera 1", "Camera 2", …); index = switchcam value sent to the camera. */
        val cameras        : List<String> = emptyList(),
        val selectedCamera : Int          = 0,
        /** True for GeneralPlus — uses MjpegRtspPlayer instead of IjkPlayer
         *  (IjkPlayer's FFmpeg build lacks the MJPEG decoder). */
        val useMjpeg       : Boolean      = false,
    ) : LiveUiState()
    /**
     * Allwinner V853 live preview. The camera streams MPEG-TS over the
     * proprietary `rtp2p` UDP transport (port 2222 after handshake). We
     * drain UDP into a temp file and hand the player a local `file://` URI
     * once buffered. While [localFileUri] is null we show a buffering state.
     *
     * [camid] = 0 (front) / 1 (back). Switching cameras tears down the
     * current rtp2p session and opens a new one.
     */
    data class AllwinnerLive(
        val camid: Int = 0,
        val localFileUri: String? = null,
        /** True while we still don't have any buffered bytes — UI shows a spinner. */
        val buffering: Boolean = true,
        /** Set when the watchdog gives up (no packets within INITIAL_RX_TIMEOUT_MS). */
        val failed: Boolean = false,
    ) : LiveUiState()
}

/** Transient state of a remote-capture action (Allwinner only). */
sealed class CaptureState {
    object Idle : CaptureState()
    data class Capturing(val kind: CaptureKind) : CaptureState()
    data class Ready(val kind: CaptureKind, val fileNames: List<String>) : CaptureState()
}

enum class CaptureKind { PHOTO, VIDEO }

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "Trafy.LiveVM"

        const val MSG_PHOTO_SAVED = "Fotoğraf kaydedildi"
        const val MSG_VIDEO_SAVED = "Video kaydedildi"
        const val MSG_FAILED      = "Çekim başarısız"
        const val MSG_BUSY        = "Önceki çekim hâlâ sürüyor"
    }

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val hiDvrRepo    = HiDvrMediaRepository()
    private val eeasyRepo    = EeasytechLiveRepository()
    private val gpLiveRepo   = GeneralplusLiveRepository()
    private val mstarLiveRepo = MstarLiveRepository()
    private val captureRepo  = AllwinnerCaptureRepository()
    private val allwinnerLiveRepo =
        com.example.trafykamerasikotlin.data.media.AllwinnerLiveRepository(application)

    /**
     * Application-scoped supervisor for cam-side cleanup calls (exit live,
     * resume `rec=1`). Survives activity death so the cam doesn't stay
     * stuck on its on-screen "continue in app" banner when the user kills
     * the app from the Live tab. Mirrors the same pattern in
     * [com.example.trafykamerasikotlin.ui.viewmodel.MediaViewModel] and
     * [com.example.trafykamerasikotlin.ui.viewmodel.SettingsViewModel] —
     * fix(easytech) commit 8b16875 added it there but missed the Live VM,
     * so a kill from Live still left Easytech cams paused until the next
     * launch happened to land on Home (where `ensureRecording` retries).
     */
    private val cleanupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private var allwinnerLiveJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow<LiveUiState>(LiveUiState.NotConnected)
    val uiState: StateFlow<LiveUiState> = _uiState

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _captureMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val captureMessages: SharedFlow<String> = _captureMessages.asSharedFlow()

    private var loadedDevice: DeviceInfo? = null
    // Network the current stream was started with — remembered so [onForeground]
    // can re-enter live preview with the same dashcam-bound network after the
    // app returns from the background.
    private var loadedNetwork: Network? = null
    // True between [onBackground] and [onForeground]: the app was backgrounded
    // while the Live tab was on screen, so the stream was torn down (and the
    // cam told to resume recording) and should be re-entered on return. Keeps
    // [onForeground] a no-op on initial composition.
    @Volatile private var pausedForBackground: Boolean = false
    // HiDVR camera channel keys in the order the user sees them in the tab bar;
    // each key maps to a camid via [hiDvrCamid] when we call getcamchnl.cgi.
    private var hiDvrCameraKeys: List<String> = emptyList()

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Starts the RTSP stream for [device].
     *
     * [network] is the dashcam-specific Network obtained via WifiNetworkSpecifier (API 29+).
     * When non-null, the process is bound to this network so that IjkPlayer's native sockets
     * also route through the dashcam WiFi. The bind is released in [onLeave].
     * When null (already-connected or legacy-API path), the system handles routing automatically.
     */
    fun startStream(device: DeviceInfo, network: Network?) {
        if (loadedDevice == device && _uiState.value is LiveUiState.Playing) return
        loadedDevice  = device
        loadedNetwork = network
        viewModelScope.launch {
            _uiState.value = LiveUiState.Preparing

            // Bind the process so IjkPlayer's native RTSP sockets route through dashcam WiFi
            connectivityManager.bindProcessToNetwork(network)
            Log.i(TAG, "startStream: process bound to network=$network")

            val ip = device.protocol.deviceIp
            when (device.protocol) {
                ChipsetProtocol.ALLWINNER_V853 -> {
                    // Live preview goes over rtp2p UDP (port 2222). The repo
                    // streams MPEG-TS into a temp file; we hand its URI to
                    // the player once enough buffered. Always start at front
                    // cam (camid=0); switching is a follow-up.
                    Log.i(TAG, "startStream: Allwinner — opening live rtp2p session")
                    _uiState.value = LiveUiState.AllwinnerLive(camid = 0, buffering = true)
                    startAllwinnerLive(camid = 0)
                }
                ChipsetProtocol.GENERALPLUS -> {
                    Log.i(TAG, "startStream: GeneralPlus entering live")
                    gpLiveRepo.enterLive()
                    val rtspUrl = GeneralplusLiveRepository.RTSP_URL
                    Log.i(TAG, "startStream: GeneralPlus ready → $rtspUrl")
                    _uiState.value = LiveUiState.Playing(
                        rtspUrl  = rtspUrl,
                        useMjpeg = true,
                    )
                }
                ChipsetProtocol.EEASYTECH -> {
                    Log.i(TAG, "startStream: Easytech entering live for $ip")
                    val camNum  = eeasyRepo.enterLive(ip)
                    val cameras = cameraLabels(camNum)
                    // Some Easytech firmware variants spin up the RTSP server
                    // a few hundred ms AFTER /app/enterrecorder ACKs. Without
                    // this gap, FFmpeg's DESCRIBE arrives before the RTSP
                    // server is listening and IjkPlayer reports
                    // "Invalid data found when processing input" → -10000.
                    // 600 ms is empirically enough on the firmware variants
                    // we've seen; HiDVR already does 1 s for a similar reason.
                    delay(600)
                    val rtspUrl = EeasytechLiveRepository.RTSP_URL
                    Log.i(TAG, "startStream: Easytech ready → $rtspUrl, cameras=$cameras")
                    _uiState.value = LiveUiState.Playing(
                        rtspUrl        = rtspUrl,
                        cameras        = cameras,
                        selectedCamera = 0,
                    )
                }
                ChipsetProtocol.MSTAR -> {
                    // MStar streams each sensor over its own RTSP URL
                    // (av4/av5/av6) and keeps recording while it previews — no
                    // stop-recording / register-client handshake (the OEM's are
                    // all no-ops). Just point the player at av4 (front); extra
                    // lenses become switchable tabs.
                    val camNum  = mstarLiveRepo.cameraCount(ip)
                    val cameras = cameraLabels(camNum)
                    val rtspUrl = MstarLiveRepository.rtspUrl(ip, 0)
                    Log.i(TAG, "startStream: MStar ready → $rtspUrl, cameras=$cameras")
                    _uiState.value = LiveUiState.Playing(
                        rtspUrl        = rtspUrl,
                        cameras        = cameras,
                        selectedCamera = 0,
                    )
                }
                else -> {
                    Log.i(TAG, "startStream: HiDVR stopping recording for $ip")
                    hiDvrRepo.stopRecording(ip)
                    delay(1_000)   // give camera time to release encoder before RTSP
                    hiDvrRepo.registerClient(ip, device.clientIp)
                    val cameraKeys = hiDvrRepo.discoverCameras(ip)
                    hiDvrRepo.selectLiveCamera(ip, hiDvrCamid(cameraKeys.first()))
                    // Only surface the camera tab bar when there's something to switch to.
                    val cameras = if (cameraKeys.size > 1) cameraKeys.map { hiDvrCameraLabel(it) } else emptyList()
                    val rtspUrl = "rtsp://$ip:554/livestream/1"
                    Log.i(TAG, "startStream: HiDVR ready → $rtspUrl, cameras=$cameras")
                    _uiState.value = LiveUiState.Playing(
                        rtspUrl        = rtspUrl,
                        cameras        = cameras,
                        selectedCamera = 0,
                    )
                    hiDvrCameraKeys = cameraKeys
                }
            }
        }
    }

    /**
     * Switches the live stream to the lens at [cameraIndex].
     * Easytech switches via a dedicated CGI; HiDVR re-routes the /livestream/1
     * output via getcamchnl.cgi. Other chipsets are no-ops.
     */
    fun switchCamera(cameraIndex: Int) {
        val device  = loadedDevice ?: return
        val current = _uiState.value as? LiveUiState.Playing ?: return
        if (cameraIndex == current.selectedCamera) return
        val ip = device.protocol.deviceIp
        viewModelScope.launch {
            // MStar has no camera-switch CGI: each lens is a distinct RTSP URL
            // (av4/av5/av6), so switching is just re-pointing the player. The
            // IjkPlayer is keyed on rtspUrl, so the new URL restarts it.
            if (device.protocol == ChipsetProtocol.MSTAR) {
                Log.i(TAG, "switchCamera: MStar → index $cameraIndex")
                _uiState.value = current.copy(
                    selectedCamera = cameraIndex,
                    rtspUrl        = MstarLiveRepository.rtspUrl(ip, cameraIndex),
                )
                return@launch
            }
            val ok = when (device.protocol) {
                ChipsetProtocol.EEASYTECH -> eeasyRepo.switchCamera(ip, cameraIndex)
                ChipsetProtocol.GENERALPLUS, ChipsetProtocol.ALLWINNER_V853 -> false
                else -> {
                    val key = hiDvrCameraKeys.getOrNull(cameraIndex) ?: return@launch
                    hiDvrRepo.selectLiveCamera(ip, hiDvrCamid(key))
                }
            }
            if (ok) {
                Log.i(TAG, "switchCamera: now on index $cameraIndex")
                _uiState.value = current.copy(selectedCamera = cameraIndex)
            }
        }
    }

    /** Maps a HiDVR camera-channel key to the camid expected by getcamchnl.cgi. */
    private fun hiDvrCamid(key: String): Int = when (key) {
        "Front"  -> 0
        "Back"   -> 1
        "Inside" -> 2
        else     -> 0
    }

    /** Localized label for a HiDVR camera channel — reuses the Media tab strings. */
    private fun hiDvrCameraLabel(key: String): String = getApplication<Application>().getString(
        when (key) {
            "Back"   -> com.example.trafykamerasikotlin.R.string.media_tab_back
            "Inside" -> com.example.trafykamerasikotlin.R.string.media_tab_inside
            else     -> com.example.trafykamerasikotlin.R.string.media_tab_front
        }
    )

    /**
     * Called from [LiveScreen]'s `onDispose` when the user navigates away from
     * the Live tab in-app. Resumes recording, releases the stream, and forgets
     * the device entirely — the user chose to leave, so no auto-restart.
     */
    fun onLeave() {
        val device = loadedDevice ?: return
        loadedDevice        = null
        loadedNetwork       = null
        pausedForBackground = false
        _uiState.value      = LiveUiState.NotConnected
        _captureState.value = CaptureState.Idle
        Log.d(TAG, "onLeave: for ${device.protocol.deviceIp}")
        teardownLive(device)
    }

    /**
     * App went to background while the Live tab was on screen (Home button,
     * app switcher, or about to be killed). Compose `onDispose` does NOT fire
     * in this case, so without this hook the cam stays paused — entering live
     * stopped the encoder (`enterrecorder` / `stopRecording` / `enterLive`) and
     * only [onLeave]/[onForeground] resume it. Running here (on ON_STOP, while
     * the process is still alive) also lets `exitLive`'s 500ms-gated `rec=1`
     * complete before any kill. [loadedDevice]/[loadedNetwork] are kept so
     * [onForeground] can re-enter the stream on return.
     */
    fun onBackground() {
        val device = loadedDevice ?: return
        if (pausedForBackground) return
        pausedForBackground = true
        Log.i(TAG, "onBackground: leaving live so cam can record — ${device.protocol.deviceIp}")
        _uiState.value      = LiveUiState.NotConnected
        _captureState.value = CaptureState.Idle
        teardownLive(device)
    }

    /**
     * App returned to the foreground on the Live tab. Re-enters the stream only
     * if we'd actually been backgrounded. No-op on initial composition — that
     * case is handled by [LiveScreen]'s `LaunchedEffect(device)` which already
     * calls [startStream], and a duplicate call would race it.
     */
    fun onForeground() {
        if (!pausedForBackground) return
        pausedForBackground = false
        val device  = loadedDevice ?: return
        val network = loadedNetwork
        Log.i(TAG, "onForeground: re-entering live — ${device.protocol.deviceIp}")
        loadedDevice = null   // clear the no-op guard so startStream re-runs
        startStream(device, network)
    }

    /**
     * Resumes SD recording on the cam (chipset-specific) and releases the live
     * stream + process network binding. Shared by [onLeave] (tab navigation)
     * and [onBackground] (app backgrounded). The recording resume MUST run —
     * for Easytech it's `exitrecorder` → 500ms → `rec=1`; skipping it leaves
     * the cam permanently paused.
     */
    private fun teardownLive(device: DeviceInfo) {
        // Cancel any active Allwinner live stream synchronously so its
        // resources are released before we even return from this method.
        allwinnerLiveJob?.cancel()
        allwinnerLiveJob = null
        allwinnerLiveRepo.stop()
        // Run cleanup on the process-scoped supervisor (NOT viewModelScope)
        // so the cam's exitrecorder → 500ms → rec=1 call survives an
        // activity kill from the Live tab. Bounded by a generous timeout
        // so a wedged cam can't hold the supervisor forever.
        cleanupScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                val ip = device.protocol.deviceIp
                when (device.protocol) {
                    ChipsetProtocol.ALLWINNER_V853 -> { /* live stream already cancelled above; relay session stays alive */ }
                    ChipsetProtocol.GENERALPLUS    -> gpLiveRepo.exitLive()
                    ChipsetProtocol.EEASYTECH      -> eeasyRepo.exitLive(ip)
                    // MStar kept recording throughout preview and needs no
                    // client (un)registration — just drop the RTSP stream.
                    ChipsetProtocol.MSTAR          -> { /* no-op: nothing to resume/unregister */ }
                    else -> {
                        hiDvrRepo.unregisterClient(ip, device.clientIp)
                        hiDvrRepo.startRecording(ip)
                    }
                }
                // Unbind AFTER exit calls complete so they still route through dashcam WiFi
                connectivityManager.bindProcessToNetwork(null)
                Log.i(TAG, "teardownLive: process unbound from dashcam network")
            }
        }
    }

    // ── Allwinner live preview ──────────────────────────────────────────────

    /**
     * Switch the live preview to a different camera. Tears down the current
     * rtp2p session and opens a new one for [camid] (0 = front, 1 = back).
     */
    fun switchAllwinnerCamera(camid: Int) {
        val device = loadedDevice ?: return
        if (device.protocol != ChipsetProtocol.ALLWINNER_V853) return
        if ((_uiState.value as? LiveUiState.AllwinnerLive)?.camid == camid) return
        allwinnerLiveJob?.cancel()
        allwinnerLiveRepo.stop()
        _uiState.value = LiveUiState.AllwinnerLive(camid = camid, buffering = true)
        startAllwinnerLive(camid)
    }

    private fun startAllwinnerLive(camid: Int) {
        val device = loadedDevice ?: return
        val ip = device.protocol.deviceIp
        allwinnerLiveJob?.cancel()
        allwinnerLiveJob = viewModelScope.launch {
            val ok = allwinnerLiveRepo.stream(
                deviceIp = ip,
                camid = camid,
                onBuffered = { uri ->
                    val cur = _uiState.value
                    if (cur is LiveUiState.AllwinnerLive && cur.camid == camid) {
                        _uiState.value = cur.copy(localFileUri = uri, buffering = false)
                    }
                },
            )
            if (!ok) {
                val cur = _uiState.value
                if (cur is LiveUiState.AllwinnerLive && cur.camid == camid) {
                    _uiState.value = cur.copy(buffering = false, failed = true)
                }
            }
        }
    }

    // ── Allwinner remote capture ────────────────────────────────────────────

    /** Tells the A19 dashcam to snap a still photo. Emits capture state + a user message. */
    fun capturePhoto() = runCapture(CaptureKind.PHOTO)

    /** Tells the A19 dashcam to record a ~6-s event video. */
    fun captureVideo() = runCapture(CaptureKind.VIDEO)

    private fun runCapture(kind: CaptureKind) {
        val device = loadedDevice ?: return
        if (device.protocol != ChipsetProtocol.ALLWINNER_V853) return
        if (_captureState.value is CaptureState.Capturing) {
            viewModelScope.launch { _captureMessages.emit(MSG_BUSY) }
            return
        }
        _captureState.value = CaptureState.Capturing(kind)
        viewModelScope.launch {
            val ip = device.protocol.deviceIp
            val result: AllwinnerCaptureResult = when (kind) {
                CaptureKind.PHOTO -> captureRepo.capturePhoto(ip)
                CaptureKind.VIDEO -> captureRepo.captureVideo(ip, durationSec = 6)
            }
            if (result.ok) {
                Log.i(TAG, "capture ${kind.name} OK via '${result.command}' files=${result.files}")
                _captureState.value = CaptureState.Ready(kind, result.files)
                _captureMessages.emit(
                    when (kind) {
                        CaptureKind.PHOTO -> MSG_PHOTO_SAVED
                        CaptureKind.VIDEO -> MSG_VIDEO_SAVED
                    }
                )
                // Auto-return to Idle after a short delay so the capture buttons re-enable.
                delay(2_500)
                if (_captureState.value is CaptureState.Ready) {
                    _captureState.value = CaptureState.Idle
                }
            } else {
                Log.w(TAG, "capture ${kind.name} FAILED (ret=${result.ret}) — tried ${result.command}")
                _captureState.value = CaptureState.Idle
                _captureMessages.emit(MSG_FAILED)
            }
        }
    }


    // ── Private helpers ────────────────────────────────────────────────────

    /** Builds camera label list from camnum (empty = single camera, no tab bar shown). */
    private fun cameraLabels(camNum: Int): List<String> = when {
        camNum >= 3 -> listOf("Camera 1", "Camera 2", "Camera 3")
        camNum == 2 -> listOf("Camera 1", "Camera 2")
        else        -> emptyList()
    }
}
