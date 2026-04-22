package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.update.UpdateInfo
import com.example.trafykamerasikotlin.data.update.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data object UpToDate : UpdateUiState()
    data class Available(val info: UpdateInfo) : UpdateUiState()
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUiState()
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateUiState()
    data class Error(val kind: Kind) : UpdateUiState() {
        enum class Kind {
            Network,       // fetch/download failed
            Download,      // APK download failed
            BadSignature,  // manifest signature invalid or missing — do NOT trust
            BadChecksum,   // downloaded APK's sha256 disagrees with signed manifest
        }
    }
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Trafy.UpdateVM"
    }

    private val repo = UpdateRepository(application)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _showFeedback = MutableStateFlow(false)
    val showFeedback: StateFlow<Boolean> = _showFeedback.asStateFlow()

    /**
     * Silent background check. Surfaces only on success. In particular, a bad
     * signature is logged but does NOT pop a scary dialog on startup — we'll surface
     * it if the user manually checks. This keeps crashes quiet for users on a flaky
     * network or a stale DNS cache, but never silently accepts a bad signature.
     */
    fun autoCheckOnStartup() {
        if (!repo.shouldAutoCheck()) return
        viewModelScope.launch {
            Log.d(TAG, "autoCheck start")
            when (val r = repo.checkForUpdate()) {
                is UpdateRepository.CheckResult.UpdateAvailable -> {
                    _state.value = UpdateUiState.Available(r.info)
                    _showFeedback.value = true
                    repo.markChecked()
                }
                UpdateRepository.CheckResult.UpToDate -> {
                    repo.markChecked()
                }
                UpdateRepository.CheckResult.NetworkError -> {
                    // Don't mark checked — try again next launch.
                    Log.d(TAG, "autoCheck: network error (will retry next launch)")
                }
                UpdateRepository.CheckResult.BadSignature -> {
                    // Don't mark checked — we want the next launch to retry in case
                    // the server was mid-deploy or the file was transiently corrupt.
                    Log.w(TAG, "autoCheck: manifest signature invalid (silent)")
                }
            }
        }
    }

    fun manualCheck() {
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _showFeedback.value = true
            _state.value = when (val r = repo.checkForUpdate()) {
                is UpdateRepository.CheckResult.UpdateAvailable -> {
                    repo.markChecked()
                    UpdateUiState.Available(r.info)
                }
                UpdateRepository.CheckResult.UpToDate -> {
                    repo.markChecked()
                    UpdateUiState.UpToDate
                }
                UpdateRepository.CheckResult.NetworkError ->
                    UpdateUiState.Error(UpdateUiState.Error.Kind.Network)
                UpdateRepository.CheckResult.BadSignature ->
                    UpdateUiState.Error(UpdateUiState.Error.Kind.BadSignature)
            }
        }
    }

    fun downloadAndInstall() {
        val info = when (val s = _state.value) {
            is UpdateUiState.Available -> s.info
            is UpdateUiState.ReadyToInstall -> {
                repo.installApk(s.apk)
                return
            }
            else -> return
        }
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(info, 0f)
            val result = repo.downloadApk(info) { fraction ->
                val safe = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
                _state.value = UpdateUiState.Downloading(info, safe)
            }
            _state.value = when (result) {
                is UpdateRepository.DownloadResult.Ok -> {
                    repo.installApk(result.file)
                    UpdateUiState.ReadyToInstall(info, result.file)
                }
                UpdateRepository.DownloadResult.NetworkError ->
                    UpdateUiState.Error(UpdateUiState.Error.Kind.Download)
                UpdateRepository.DownloadResult.ChecksumMismatch ->
                    UpdateUiState.Error(UpdateUiState.Error.Kind.BadChecksum)
            }
        }
    }

    fun dismiss() {
        _showFeedback.value = false
        val current = _state.value
        if (current !is UpdateUiState.Downloading && current !is UpdateUiState.ReadyToInstall) {
            _state.value = UpdateUiState.Idle
        }
    }

    val installedVersionName: String get() = repo.installedVersionName
}
