package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.media.EeasytechMediaRepository
import com.example.trafykamerasikotlin.data.media.GeneralplusMediaRepository
import com.example.trafykamerasikotlin.data.media.HiDvrMediaRepository
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Live progress snapshot for a single file download. */
data class DownloadState(
    val pct          : Int,    // 0–100
    val receivedMb   : Float,
    val totalMb      : Float,
    val speedMbPerSec: Float,
)

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
    private val gpRepo    = GeneralplusMediaRepository()

    private val _uiState = MutableStateFlow<MediaUiState>(MediaUiState.NotConnected)
    val uiState: StateFlow<MediaUiState> = _uiState

    // Currently downloading file names (to show progress in UI)
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading

    // Live DownloadState per file name; only present while a GP download is active.
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadState>> = _downloadProgress

    // Stores running download jobs so they can be cancelled by file name.
    private val downloadJobs  = mutableMapOf<String, Job>()
    // Last (bytes, timeMs) sample per file for rolling speed calculation.
    private val speedSamples  = mutableMapOf<String, Pair<Long, Long>>()

    /**
     * Emits the RTSP URL once a GeneralPlus file is ready to play.
     * MediaScreen observes this and opens the URL in the system video player.
     * Reset to null after consumption.
     */
    private val _playbackUri = MutableStateFlow<String?>(null)
    val playbackUri: StateFlow<String?> = _playbackUri

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
            try {
                val (videos, photos) = if (device.protocol == ChipsetProtocol.GENERALPLUS) {
                    // GeneralPlus: single session fetches both videos and photos
                    val thumbDir = File(getApplication<Application>().cacheDir, "gp_thumbs")
                    gpRepo.fetchFiles(thumbDir, device.protocol.deviceIp)
                        ?: throw Exception("Could not load media files")
                } else {
                    enterPlayback(device)
                    Pair(fetchVideos(device), fetchPhotos(device))
                }
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

    /** Cancels an in-progress download and deletes the partial file. */
    fun cancelDownload(fileName: String) {
        downloadJobs[fileName]?.cancel()
    }

    /**
     * Downloads a file from the camera.
     * For GeneralPlus: streams via GPSOCKET GetRawData with live progress + speed.
     * For all others: streams via HTTP (indeterminate progress).
     * Partial files are deleted on cancellation or failure.
     */
    fun download(file: MediaFile) {
        if (_downloading.value.contains(file.name)) return   // already in progress
        val device  = loadedDevice ?: return
        val dir     = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(dir, file.name)

        val job = viewModelScope.launch {
            _downloading.value = _downloading.value + file.name
            Log.i(TAG, "download: start ${file.name}")
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    if (device.protocol == ChipsetProtocol.GENERALPLUS) {
                        val fileIndex = GeneralplusMediaRepository.fileIndexOf(file.path)
                        if (fileIndex < 0) throw Exception("Invalid GP file path: ${file.path}")
                        _downloadProgress.value = _downloadProgress.value +
                                (file.name to DownloadState(0, 0f, 0f, 0f))
                        val ok = gpRepo.downloadFile(
                            device.protocol.deviceIp, fileIndex, outFile
                        ) { received, total ->
                            val now  = System.currentTimeMillis()
                            val prev = speedSamples[file.name]
                            val speed = if (prev != null && now > prev.second) {
                                val dtSec = (now - prev.second) / 1000f
                                ((received - prev.first) / dtSec) / (1024f * 1024f)
                            } else 0f
                            speedSamples[file.name] = Pair(received, now)
                            _downloadProgress.value = _downloadProgress.value + (file.name to
                                DownloadState(
                                    pct           = ((received * 100L) / total).toInt().coerceIn(0, 100),
                                    receivedMb    = received / (1024f * 1024f),
                                    totalMb       = total    / (1024f * 1024f),
                                    speedMbPerSec = speed,
                                ))
                        }
                        if (!ok) throw Exception("GetRawData returned no data")
                    } else {
                        val body = DashcamHttpClient.openStream(file.httpUrl)
                            ?: throw Exception("Failed to open stream")
                        body.use { responseBody ->
                            val input  = responseBody.byteStream()
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            outFile.outputStream().use { output ->
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    ensureActive()
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
                completed = true
                Log.i(TAG, "download: saved to ${outFile.absolutePath}")
                MediaScannerConnection.scanFile(
                    getApplication(), arrayOf(outFile.absolutePath), null, null
                )
            } catch (e: CancellationException) {
                Log.i(TAG, "download cancelled: ${file.name}")
                throw e                          // re-throw so the coroutine cancels properly
            } catch (e: Exception) {
                Log.e(TAG, "download failed: ${e.message}")
            } finally {
                _downloading.value      = _downloading.value - file.name
                _downloadProgress.value = _downloadProgress.value - file.name
                speedSamples.remove(file.name)
                downloadJobs.remove(file.name)
                if (!completed && outFile.exists()) outFile.delete()
            }
        }
        downloadJobs[file.name] = job
    }

    /**
     * Prepares the GeneralPlus camera to play back a specific file over RTSP,
     * then emits [GeneralplusMediaRepository.RTSP_URL] to [playbackUri].
     * No-op for non-GeneralPlus devices (those open httpUrl directly in the UI).
     */
    fun playFile(file: MediaFile) {
        val device = loadedDevice ?: return
        if (device.protocol != ChipsetProtocol.GENERALPLUS) return
        val fileIndex = GeneralplusMediaRepository.fileIndexOf(file.path)
        if (fileIndex < 0) return
        viewModelScope.launch {
            val ok = gpRepo.preparePlayback(device.protocol.deviceIp, fileIndex)
            if (ok) {
                _playbackUri.value = GeneralplusMediaRepository.RTSP_URL
                Log.i(TAG, "playFile: ready — emitting RTSP URL")
            } else {
                Log.e(TAG, "playFile: preparePlayback failed")
            }
        }
    }

    /** Clears [playbackUri] after the caller has consumed and opened the URL. */
    fun clearPlaybackUri() { _playbackUri.value = null }

    fun onLeave() {
        val device = loadedDevice ?: return
        Log.d(TAG, "onLeave: for ${device.protocol.deviceIp}")
        viewModelScope.launch { leavePlayback(device) }
    }

    // ── Repository dispatch ────────────────────────────────────────────────

    private suspend fun enterPlayback(device: DeviceInfo) {
        when (device.protocol) {
            ChipsetProtocol.GENERALPLUS -> {
                /* fetchFiles handles mode switching within its own session */
            }
            ChipsetProtocol.EEASYTECH -> eeasyRepo.enterPlayback(device.protocol.deviceIp)
            else                      -> hiDvrRepo.stopRecording(device.protocol.deviceIp)
        }
    }

    private suspend fun leavePlayback(device: DeviceInfo) {
        when (device.protocol) {
            ChipsetProtocol.GENERALPLUS -> gpRepo.exitPlayback(device.protocol.deviceIp)
            ChipsetProtocol.EEASYTECH   -> eeasyRepo.exitPlayback(device.protocol.deviceIp)
            else                        -> hiDvrRepo.startRecording(device.protocol.deviceIp)
        }
    }

    private suspend fun fetchVideos(device: DeviceInfo): List<MediaFile> =
        when (device.protocol) {
            ChipsetProtocol.EEASYTECH -> eeasyRepo.fetchVideos(device.protocol.deviceIp)
            else                      -> hiDvrRepo.fetchVideos(device.protocol.deviceIp)
        }

    private suspend fun fetchPhotos(device: DeviceInfo): List<MediaFile> =
        when (device.protocol) {
            ChipsetProtocol.EEASYTECH -> eeasyRepo.fetchPhotos(device.protocol.deviceIp)
            else                      -> hiDvrRepo.fetchPhotos(device.protocol.deviceIp)
        }

    private suspend fun deleteFile(device: DeviceInfo, file: MediaFile): Boolean =
        when (device.protocol) {
            ChipsetProtocol.GENERALPLUS -> {
                val fileIndex = GeneralplusMediaRepository.fileIndexOf(file.path)
                if (fileIndex < 0) false
                else gpRepo.deleteFile(device.protocol.deviceIp, fileIndex)
            }
            ChipsetProtocol.EEASYTECH -> eeasyRepo.deleteFile(device.protocol.deviceIp, file)
            else                      -> hiDvrRepo.deleteFile(device.protocol.deviceIp, file)
        }
}
