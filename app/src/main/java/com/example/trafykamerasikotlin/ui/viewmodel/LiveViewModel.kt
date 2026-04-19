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
     * Allwinner V853 doesn't expose an RTSP server — its live stream travels over the
     * proprietary RTP2P/KCP transport which isn't implemented yet. Meanwhile, remote
     * capture (take photo / take 6-s event video) works via the relay channel and is
     * the primary Live-tab action for this chipset.
     */
    object AllwinnerCapture : LiveUiState()
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
    private val captureRepo  = AllwinnerCaptureRepository()

    private val _uiState = MutableStateFlow<LiveUiState>(LiveUiState.NotConnected)
    val uiState: StateFlow<LiveUiState> = _uiState

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _captureMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val captureMessages: SharedFlow<String> = _captureMessages.asSharedFlow()

    private var loadedDevice: DeviceInfo? = null

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
        loadedDevice = device
        viewModelScope.launch {
            _uiState.value = LiveUiState.Preparing

            // Bind the process so IjkPlayer's native RTSP sockets route through dashcam WiFi
            connectivityManager.bindProcessToNetwork(network)
            Log.i(TAG, "startStream: process bound to network=$network")

            val ip = device.protocol.deviceIp
            when (device.protocol) {
                ChipsetProtocol.ALLWINNER_V853 -> {
                    // No RTSP path — Allwinner streams over RTP2P/KCP which we haven't
                    // wired for live yet. The Live tab surfaces remote capture instead.
                    Log.i(TAG, "startStream: Allwinner — showing capture-only Live view")
                    _uiState.value = LiveUiState.AllwinnerCapture
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
                    val rtspUrl = EeasytechLiveRepository.RTSP_URL
                    Log.i(TAG, "startStream: Easytech ready → $rtspUrl, cameras=$cameras")
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
                    val rtspUrl = "rtsp://$ip:554/livestream/1"
                    Log.i(TAG, "startStream: HiDVR ready → $rtspUrl")
                    _uiState.value = LiveUiState.Playing(rtspUrl = rtspUrl)
                }
            }
        }
    }

    /**
     * Switches the Easytech live stream to [cameraIndex].
     * No-op for HiDVR or single-camera devices.
     */
    fun switchCamera(cameraIndex: Int) {
        val device  = loadedDevice ?: return
        if (device.protocol != ChipsetProtocol.EEASYTECH) return
        val current = _uiState.value as? LiveUiState.Playing ?: return
        if (cameraIndex == current.selectedCamera) return
        viewModelScope.launch {
            val ok = eeasyRepo.switchCamera(device.protocol.deviceIp, cameraIndex)
            if (ok) {
                Log.i(TAG, "switchCamera: now on index $cameraIndex")
                _uiState.value = current.copy(selectedCamera = cameraIndex)
            }
        }
    }

    fun onLeave() {
        val device = loadedDevice ?: return
        loadedDevice   = null
        _uiState.value = LiveUiState.NotConnected
        _captureState.value = CaptureState.Idle
        Log.d(TAG, "onLeave: for ${device.protocol.deviceIp}")
        viewModelScope.launch {
            val ip = device.protocol.deviceIp
            when (device.protocol) {
                ChipsetProtocol.ALLWINNER_V853 -> { /* nothing to tear down — relay session stays alive */ }
                ChipsetProtocol.GENERALPLUS    -> gpLiveRepo.exitLive()
                ChipsetProtocol.EEASYTECH      -> eeasyRepo.exitLive(ip)
                else -> {
                    hiDvrRepo.unregisterClient(ip, device.clientIp)
                    hiDvrRepo.startRecording(ip)
                }
            }
            // Unbind AFTER exit calls complete so they still route through dashcam WiFi
            connectivityManager.bindProcessToNetwork(null)
            Log.i(TAG, "onLeave: process unbound from dashcam network")
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
