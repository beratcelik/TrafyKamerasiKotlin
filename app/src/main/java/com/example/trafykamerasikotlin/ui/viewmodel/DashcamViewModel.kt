package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.handshake.DashcamHandshakeManager
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.FailureReason
import com.example.trafykamerasikotlin.data.model.HandshakeResult
import com.example.trafykamerasikotlin.data.network.WifiIpProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class DashcamUiState {
    data object Idle : DashcamUiState()
    data object Connecting : DashcamUiState()
    data class Connected(val device: DeviceInfo) : DashcamUiState()
    data class Error(val reason: FailureReason) : DashcamUiState()
}

class DashcamViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Trafy.ViewModel"
    }

    private val manager = DashcamHandshakeManager(
        wifiIpProvider = WifiIpProvider(application),
    )

    private val _uiState = MutableStateFlow<DashcamUiState>(DashcamUiState.Idle)
    val uiState: StateFlow<DashcamUiState> = _uiState.asStateFlow()

    fun connect() {
        Log.i(TAG, "connect() called, current state=${_uiState.value}")
        if (_uiState.value is DashcamUiState.Connecting) {
            Log.w(TAG, "Already connecting — ignoring duplicate connect()")
            return
        }
        _uiState.update { DashcamUiState.Connecting }
        Log.i(TAG, "State → Connecting")
        viewModelScope.launch {
            Log.i(TAG, "Coroutine started, calling manager.connect()")
            when (val result = manager.connect()) {
                is HandshakeResult.Success -> {
                    Log.i(TAG, "Result: Success — device=${result.deviceInfo}")
                    _uiState.update { DashcamUiState.Connected(result.deviceInfo) }
                }
                is HandshakeResult.Failure -> {
                    Log.e(TAG, "Result: Failure — reason=${result.reason}")
                    _uiState.update { DashcamUiState.Error(result.reason) }
                }
            }
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        _uiState.update { DashcamUiState.Idle }
    }
}
