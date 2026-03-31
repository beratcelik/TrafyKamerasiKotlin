package com.example.trafykamerasikotlin.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.media.HiDvrMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class LiveUiState {
    object NotConnected : LiveUiState()
    object StoppingRecording : LiveUiState()
    data class Playing(val rtspUrl: String) : LiveUiState()
}

class LiveViewModel : ViewModel() {

    private companion object {
        const val TAG = "Trafy.LiveVM"
    }

    private val repo = HiDvrMediaRepository()

    private val _uiState = MutableStateFlow<LiveUiState>(LiveUiState.NotConnected)
    val uiState: StateFlow<LiveUiState> = _uiState

    private var loadedIp: String? = null

    fun startStream(deviceIp: String) {
        if (deviceIp == loadedIp && _uiState.value is LiveUiState.Playing) return
        loadedIp = deviceIp
        viewModelScope.launch {
            Log.i(TAG, "startStream: stopping recording for $deviceIp")
            _uiState.value = LiveUiState.StoppingRecording
            repo.stopRecording(deviceIp)
            val rtspUrl = "rtsp://$deviceIp:554/livestream/0"
            Log.i(TAG, "startStream: ready → $rtspUrl")
            _uiState.value = LiveUiState.Playing(rtspUrl)
        }
    }

    fun onLeave() {
        val ip = loadedIp ?: return
        Log.d(TAG, "onLeave: restarting recording for $ip")
        viewModelScope.launch { repo.startRecording(ip) }
        loadedIp = null
        _uiState.value = LiveUiState.NotConnected
    }
}
