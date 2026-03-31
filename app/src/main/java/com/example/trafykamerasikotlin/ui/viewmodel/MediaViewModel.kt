package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.media.EeasytechMediaRepository
import com.example.trafykamerasikotlin.data.media.HiDvrMediaRepository
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class MediaUiState {
    object NotConnected : MediaUiState()
    object Loading      : MediaUiState()
    data class Loaded(
        val videos: List<MediaFile>,
        val photos: List<MediaFile>,
    ) : MediaUiState()
    data class Error(val message: String) : MediaUiState()
}

class MediaViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "Trafy.MediaVM"
    }

    private val hiDvrRepo = HiDvrMediaRepository()
    private val eeasyRepo = EeasytechMediaRepository()

    private val _uiState = MutableStateFlow<MediaUiState>(MediaUiState.NotConnected)
    val uiState: StateFlow<MediaUiState> = _uiState

    // Currently downloading file names (to show progress in UI)
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading

    private var loadedDevice: DeviceInfo? = null
    private var loadJob: Job?             = null

    // ── Public API ─────────────────────────────────────────────────────────

    fun load(device: DeviceInfo) {
        val ip = device.protocol.deviceIp
        if (loadedDevice == device && _uiState.value is MediaUiState.Loaded) {
            Log.d(TAG, "load: already loaded for $ip, re-entering playback only")
            viewModelScope.launch { enterPlayback(device) }
            return
        }
        loadedDevice = device
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.i(TAG, "load: fetching for $ip")
            _uiState.value = MediaUiState.Loading
            enterPlayback(device)
            try {
                val videos = fetchVideos(device)
                val photos = fetchPhotos(device)
                Log.i(TAG, "load: ${videos.size} videos, ${photos.size} photos")
                _uiState.value = MediaUiState.Loaded(videos, photos)
            } catch (e: Exception) {
                Log.e(TAG, "load failed: ${e.message}")
                _uiState.value = MediaUiState.Error("Failed to load media: ${e.message}")
            }
        }
    }

    fun reload() {
        val device = loadedDevice ?: return
        loadedDevice = null
        load(device)
    }

    fun delete(file: MediaFile) {
        val device = loadedDevice ?: return
        viewModelScope.launch {
            val ok = deleteFile(device, file)
            if (ok) {
                val current = _uiState.value as? MediaUiState.Loaded ?: return@launch
                _uiState.value = current.copy(
                    videos = current.videos.filter { it != file },
                    photos = current.photos.filter { it != file },
                )
            }
        }
    }

    /**
     * Downloads a file from the camera using our own HTTP client (bypasses
     * DownloadManager which fails on WiFi networks without internet validation).
     */
    fun download(file: MediaFile) {
        if (_downloading.value.contains(file.name)) return   // already in progress
        viewModelScope.launch {
            _downloading.value = _downloading.value + file.name
            Log.i(TAG, "download: start ${file.name}")
            try {
                withContext(Dispatchers.IO) {
                    val body = DashcamHttpClient.openStream(file.httpUrl)
                        ?: throw Exception("Failed to open stream")
                    val dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    dir.mkdirs()
                    val outFile = File(dir, file.name)
                    body.use { it.byteStream().copyTo(outFile.outputStream()) }
                    Log.i(TAG, "download: saved to ${outFile.absolutePath}")
                    // Make it visible in Files / Gallery
                    MediaScannerConnection.scanFile(
                        getApplication(), arrayOf(outFile.absolutePath), null, null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "download failed: ${e.message}")
            } finally {
                _downloading.value = _downloading.value - file.name
            }
        }
    }

    fun onLeave() {
        val device = loadedDevice ?: return
        Log.d(TAG, "onLeave: for ${device.protocol.deviceIp}")
        viewModelScope.launch { leavePlayback(device) }
    }

    // ── Repository dispatch ────────────────────────────────────────────────

    private suspend fun enterPlayback(device: DeviceInfo) {
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            eeasyRepo.enterPlayback(device.protocol.deviceIp)
        } else {
            hiDvrRepo.stopRecording(device.protocol.deviceIp)
        }
    }

    private suspend fun leavePlayback(device: DeviceInfo) {
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            eeasyRepo.exitPlayback(device.protocol.deviceIp)
        } else {
            hiDvrRepo.startRecording(device.protocol.deviceIp)
        }
    }

    private suspend fun fetchVideos(device: DeviceInfo): List<MediaFile> =
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            eeasyRepo.fetchVideos(device.protocol.deviceIp)
        } else {
            hiDvrRepo.fetchVideos(device.protocol.deviceIp)
        }

    private suspend fun fetchPhotos(device: DeviceInfo): List<MediaFile> =
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            eeasyRepo.fetchPhotos(device.protocol.deviceIp)
        } else {
            hiDvrRepo.fetchPhotos(device.protocol.deviceIp)
        }

    private suspend fun deleteFile(device: DeviceInfo, file: MediaFile): Boolean =
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            eeasyRepo.deleteFile(device.protocol.deviceIp, file)
        } else {
            hiDvrRepo.deleteFile(device.protocol.deviceIp, file)
        }
}
