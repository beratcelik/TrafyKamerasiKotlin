package com.example.trafykamerasikotlin.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.media.EeasytechLiveRepository
import com.example.trafykamerasikotlin.data.media.HiDvrMediaRepository
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    ) : LiveUiState()
}

class LiveViewModel : ViewModel() {

    private companion object {
        const val TAG = "Trafy.LiveVM"
    }

    private val hiDvrRepo = HiDvrMediaRepository()
    private val eeasyRepo = EeasytechLiveRepository()

    private val _uiState = MutableStateFlow<LiveUiState>(LiveUiState.NotConnected)
    val uiState: StateFlow<LiveUiState> = _uiState

    private var loadedDevice: DeviceInfo? = null

    // ── Public API ─────────────────────────────────────────────────────────

    fun startStream(device: DeviceInfo) {
        if (loadedDevice == device && _uiState.value is LiveUiState.Playing) return
        loadedDevice = device
        viewModelScope.launch {
            _uiState.value = LiveUiState.Preparing
            val ip = device.protocol.deviceIp
            if (device.protocol == ChipsetProtocol.EEASYTECH) {
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
            } else {
                Log.i(TAG, "startStream: HiDVR stopping recording for $ip")
                hiDvrRepo.stopRecording(ip)
                val rtspUrl = "rtsp://$ip:554/livestream/0"
                Log.i(TAG, "startStream: HiDVR ready → $rtspUrl")
                _uiState.value = LiveUiState.Playing(rtspUrl = rtspUrl)
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
        Log.d(TAG, "onLeave: for ${device.protocol.deviceIp}")
        viewModelScope.launch {
            if (device.protocol == ChipsetProtocol.EEASYTECH) {
                eeasyRepo.exitLive(device.protocol.deviceIp)
            } else {
                hiDvrRepo.startRecording(device.protocol.deviceIp)
            }
        }
        loadedDevice   = null
        _uiState.value = LiveUiState.NotConnected
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /** Builds camera label list from camnum (empty = single camera, no tab bar shown). */
    private fun cameraLabels(camNum: Int): List<String> = when {
        camNum >= 3 -> listOf("Camera 1", "Camera 2", "Camera 3")
        camNum == 2 -> listOf("Camera 1", "Camera 2")
        else        -> emptyList()
    }
}
