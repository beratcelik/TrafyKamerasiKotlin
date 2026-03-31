package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.media.HiDvrMediaRepository
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

    private val repo = HiDvrMediaRepository()

    private val _uiState = MutableStateFlow<MediaUiState>(MediaUiState.NotConnected)
    val uiState: StateFlow<MediaUiState> = _uiState

    // Currently downloading file names (to show progress in UI)
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading

    private var loadedIp: String? = null
    private var loadJob: Job?     = null

    // ── Public API ─────────────────────────────────────────────────────────

    fun load(deviceIp: String) {
        if (deviceIp == loadedIp && _uiState.value is MediaUiState.Loaded) {
            Log.d(TAG, "load: already loaded for $deviceIp, stopping recording only")
            viewModelScope.launch { repo.stopRecording(deviceIp) }
            return
        }
        loadedIp = deviceIp
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.i(TAG, "load: fetching for $deviceIp")
            _uiState.value = MediaUiState.Loading
            repo.stopRecording(deviceIp)
            try {
                val videos = repo.fetchVideos(deviceIp)
                val photos = repo.fetchPhotos(deviceIp)
                Log.i(TAG, "load: ${videos.size} videos, ${photos.size} photos")
                _uiState.value = MediaUiState.Loaded(videos, photos)
            } catch (e: Exception) {
                Log.e(TAG, "load failed: ${e.message}")
                _uiState.value = MediaUiState.Error("Failed to load media: ${e.message}")
            }
        }
    }

    fun reload() {
        val ip = loadedIp ?: return
        loadedIp = null
        load(ip)
    }

    fun delete(file: MediaFile) {
        val ip = loadedIp ?: return
        viewModelScope.launch {
            val ok = repo.deleteFile(ip, file)
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
        val ip = loadedIp ?: return
        Log.d(TAG, "onLeave: restarting recording for $ip")
        viewModelScope.launch { repo.startRecording(ip) }
    }
}
