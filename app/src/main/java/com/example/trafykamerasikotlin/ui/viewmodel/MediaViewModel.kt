package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerRtp2pClient
import com.example.trafykamerasikotlin.data.media.AllwinnerMediaRepository
import com.example.trafykamerasikotlin.data.media.AllwinnerSdInfo
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        // Non-null only for chipsets that report SD-card capacity (Allwinner).
        val sdInfo: AllwinnerSdInfo? = null,
    ) : MediaUiState()
    data class Error(val message: String) : MediaUiState()
}

class MediaViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "Trafy.MediaVM"

        const val MSG_PLAYBACK_FAILED = "Video oynatılamadı"
        const val MSG_DOWNLOAD_FAILED = "Video indirilemedi"
        const val MSG_BUSY_PLAYBACK   = "Önce oynatmayı kapatın"

        // Emit the Allwinner playback URI once we have ~512 KB or 2 s of buffered data.
        const val BUFFERED_EMIT_BYTES     = 512L * 1024L
        const val BUFFERED_EMIT_DELAY_MS  = 2_000L
        const val EOF_IDLE_MS             = 3_000L
        // If the device doesn't send a single UDP packet within this window, give up —
        // the firmware is refusing to stream (same behaviour the OEM app hits).
        const val INITIAL_RX_TIMEOUT_MS   = 6_000L
    }

    private val hiDvrRepo     = HiDvrMediaRepository()
    private val eeasyRepo     = EeasytechMediaRepository()
    private val gpRepo        = GeneralplusMediaRepository()
    private val allwinnerRepo = AllwinnerMediaRepository()

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

    /** True while the camera is being prepared for file playback (loading spinner). */
    private val _isPreparingPlayback = MutableStateFlow(false)
    val isPreparingPlayback: StateFlow<Boolean> = _isPreparingPlayback

    /**
     * Emits the temp-file URI for Allwinner RTP2P playback once enough data has
     * been buffered to start decoding. Cleared when playback ends.
     */
    private val _allwinnerPlaybackUri = MutableStateFlow<String?>(null)
    val allwinnerPlaybackUri: StateFlow<String?> = _allwinnerPlaybackUri

    /** Transient user-facing messages (Turkish) shown via Snackbar in MediaScreen. */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // Allwinner playback stream lifecycle — at most one active at a time since the
    // device's RTP2P channel is single-stream (see PCAP; all observed playback was
    // serialised). Download uses the same channel, so we reject one while the other
    // is active.
    private var allwinnerStreamJob: Job? = null
    @Volatile private var allwinnerStreamActive: Boolean = false

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
                val sdInfo = if (device.protocol == ChipsetProtocol.ALLWINNER_V853) {
                    allwinnerRepo.fetchSdInfo(device.protocol.deviceIp)
                } else null
                Log.i(TAG, "load: ${videos.size} videos, ${photos.size} photos, sdInfo=$sdInfo")
                _uiState.value = MediaUiState.Loaded(videos, photos, sdInfo)
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
        if (device.protocol == ChipsetProtocol.ALLWINNER_V853) {
            downloadAllwinner(file)
            return
        }
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
            _isPreparingPlayback.value = true
            try {
                val ok = gpRepo.preparePlayback(device.protocol.deviceIp, fileIndex)
                if (ok) {
                    _playbackUri.value = GeneralplusMediaRepository.RTSP_URL
                    Log.i(TAG, "playFile: ready — emitting RTSP URL")
                } else {
                    Log.e(TAG, "playFile: preparePlayback failed")
                }
            } finally {
                _isPreparingPlayback.value = false
            }
        }
    }

    /** Clears [playbackUri] and tells the camera to stop file playback. */
    fun clearPlaybackUri() {
        _playbackUri.value = null
        val device = loadedDevice ?: return
        if (device.protocol == ChipsetProtocol.GENERALPLUS) {
            viewModelScope.launch { gpRepo.exitPlayback(device.protocol.deviceIp) }
        }
    }

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
            ChipsetProtocol.EEASYTECH      -> eeasyRepo.enterPlayback(device.protocol.deviceIp)
            ChipsetProtocol.ALLWINNER_V853 -> { /* session stays live; device records concurrently */ }
            else                           -> hiDvrRepo.stopRecording(device.protocol.deviceIp)
        }
    }

    private suspend fun leavePlayback(device: DeviceInfo) {
        when (device.protocol) {
            ChipsetProtocol.GENERALPLUS    -> gpRepo.exitPlayback(device.protocol.deviceIp)
            ChipsetProtocol.EEASYTECH      -> eeasyRepo.exitPlayback(device.protocol.deviceIp)
            ChipsetProtocol.ALLWINNER_V853 -> { /* no-op; see enterPlayback */ }
            else                           -> hiDvrRepo.startRecording(device.protocol.deviceIp)
        }
    }

    private suspend fun fetchVideos(device: DeviceInfo): List<MediaFile> =
        when (device.protocol) {
            ChipsetProtocol.EEASYTECH      -> eeasyRepo.fetchVideos(device.protocol.deviceIp)
            ChipsetProtocol.ALLWINNER_V853 -> allwinnerRepo.fetchVideos(device.protocol.deviceIp)
            else                           -> hiDvrRepo.fetchVideos(device.protocol.deviceIp)
        }

    private suspend fun fetchPhotos(device: DeviceInfo): List<MediaFile> =
        when (device.protocol) {
            ChipsetProtocol.EEASYTECH      -> eeasyRepo.fetchPhotos(device.protocol.deviceIp)
            ChipsetProtocol.ALLWINNER_V853 -> allwinnerRepo.fetchPhotos(device.protocol.deviceIp)
            else                           -> hiDvrRepo.fetchPhotos(device.protocol.deviceIp)
        }

    private suspend fun deleteFile(device: DeviceInfo, file: MediaFile): Boolean =
        when (device.protocol) {
            ChipsetProtocol.GENERALPLUS -> {
                val fileIndex = GeneralplusMediaRepository.fileIndexOf(file.path)
                if (fileIndex < 0) false
                else gpRepo.deleteFile(device.protocol.deviceIp, fileIndex)
            }
            ChipsetProtocol.EEASYTECH      -> eeasyRepo.deleteFile(device.protocol.deviceIp, file)
            // Allwinner firmware has no delete protocol — the OEM app's UI lacks a
            // delete button too. MediaScreen hides the delete option entirely, so
            // this branch is defensive only.
            ChipsetProtocol.ALLWINNER_V853 -> false
            else                           -> hiDvrRepo.deleteFile(device.protocol.deviceIp, file)
        }

    // ── Allwinner RTP2P playback ──────────────────────────────────────────
    //
    // Recorded files on the Allwinner V853 are not accessible via HTTP or any file
    // copy protocol — the only transport exposed by the firmware is RTP2P, the same
    // UDP session used for live preview. We treat it as a blocking stream: open,
    // drain UDP payloads into a temp .ts file, hand IjkPlayer the growing file once
    // it has enough bytes to start decoding.
    //
    // The underlying payload is almost certainly a re-encoded/proxied version of
    // the original .ts (the OEM app itself is unreliable here — it "gets stuck").
    // This is therefore best-effort: we try to give the user *something* playable.

    /** Starts RTP2P streaming of [file] to a temp cache file, emits its URI once buffered. */
    fun startAllwinnerStream(file: MediaFile) {
        val device = loadedDevice ?: return
        if (device.protocol != ChipsetProtocol.ALLWINNER_V853) return
        if (allwinnerStreamActive) {
            viewModelScope.launch { _userMessages.emit(MSG_BUSY_PLAYBACK) }
            return
        }
        allwinnerStreamActive = true
        val ctx = getApplication<Application>()
        val tempFile = File(ctx.cacheDir, "allwinner_play.ts").also {
            if (it.exists()) it.delete()
        }
        _allwinnerPlaybackUri.value = null

        allwinnerStreamJob = viewModelScope.launch {
            try {
                val ok = streamAllwinnerFile(
                    deviceIp = device.protocol.deviceIp,
                    file     = file,
                    outFile  = tempFile,
                    onBuffered = { uri -> _allwinnerPlaybackUri.value = uri },
                    progress = null,
                )
                if (!ok && _allwinnerPlaybackUri.value == null) {
                    _userMessages.emit(MSG_PLAYBACK_FAILED)
                }
            } finally {
                // Reset the single-stream guard so the user can retry. stopAllwinnerStream
                // also sets this false, but only runs if the user dismisses the overlay —
                // which they can't do when the stream produces no data (no overlay appears).
                allwinnerStreamActive = false
            }
        }
    }

    /** Stops the active Allwinner playback stream and clears the temp file + URI. */
    fun stopAllwinnerStream() {
        allwinnerStreamJob?.cancel()
        allwinnerStreamJob = null
        _allwinnerPlaybackUri.value = null
        allwinnerStreamActive = false
        val tempFile = File(getApplication<Application>().cacheDir, "allwinner_play.ts")
        if (tempFile.exists()) tempFile.delete()
    }

    private fun downloadAllwinner(file: MediaFile) {
        val device = loadedDevice ?: return
        if (allwinnerStreamActive) {
            viewModelScope.launch { _userMessages.emit(MSG_BUSY_PLAYBACK) }
            return
        }
        allwinnerStreamActive = true
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(dir, "Allwinner_${file.name}")

        val job = viewModelScope.launch {
            _downloading.value = _downloading.value + file.name
            Log.i(TAG, "downloadAllwinner: start ${file.name} → ${outFile.absolutePath}")
            _downloadProgress.value = _downloadProgress.value +
                (file.name to DownloadState(0, 0f, 0f, 0f))
            var completed = false
            try {
                withContext(Dispatchers.IO) { dir.mkdirs() }
                val totalBytes = file.sizeBytes ?: 0L
                val ok = streamAllwinnerFile(
                    deviceIp = device.protocol.deviceIp,
                    file     = file,
                    outFile  = outFile,
                    onBuffered = null,
                    progress = { received ->
                        val now = System.currentTimeMillis()
                        val prev = speedSamples[file.name]
                        val speed = if (prev != null && now > prev.second) {
                            val dtSec = (now - prev.second) / 1000f
                            ((received - prev.first) / dtSec) / (1024f * 1024f)
                        } else 0f
                        speedSamples[file.name] = Pair(received, now)
                        val pct = if (totalBytes > 0L) {
                            ((received * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else 0
                        _downloadProgress.value = _downloadProgress.value + (file.name to
                            DownloadState(
                                pct           = pct,
                                receivedMb    = received / (1024f * 1024f),
                                totalMb       = if (totalBytes > 0L) totalBytes / (1024f * 1024f) else 0f,
                                speedMbPerSec = speed,
                            ))
                    },
                )
                if (!ok) throw Exception("RTP2P produced no data")
                completed = true
                Log.i(TAG, "downloadAllwinner: saved ${outFile.length()} bytes to ${outFile.absolutePath}")
                MediaScannerConnection.scanFile(
                    getApplication(), arrayOf(outFile.absolutePath), null, null
                )
            } catch (e: CancellationException) {
                Log.i(TAG, "downloadAllwinner cancelled: ${file.name}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "downloadAllwinner failed: ${e.message}")
                _userMessages.emit(MSG_DOWNLOAD_FAILED)
            } finally {
                _downloading.value      = _downloading.value - file.name
                _downloadProgress.value = _downloadProgress.value - file.name
                speedSamples.remove(file.name)
                downloadJobs.remove(file.name)
                allwinnerStreamActive = false
                if (!completed && outFile.exists()) outFile.delete()
            }
        }
        downloadJobs[file.name] = job
    }

    /**
     * Shared RTP2P-to-file pipeline used by both playback and download. Returns true
     * iff at least one UDP packet was received and written. Closes the RTP2P client
     * in all paths. Honors coroutine cancellation by closing the client immediately.
     *
     * EOF heuristic: 3 s of RX inactivity after ≥1 packet OR `max(60 s, duration × 4)`
     * absolute ceiling (duration parsed from filename). Neither is confirmed against
     * the real device; they're pragmatic defaults matching the observed OEM behaviour
     * ("gets stuck" instead of cleanly ending).
     */
    private suspend fun streamAllwinnerFile(
        deviceIp: String,
        file: MediaFile,
        outFile: File,
        onBuffered: ((String) -> Unit)?,
        progress: ((Long) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        val session = allwinnerRepo.session(deviceIp)
        if (session == null) {
            Log.w(TAG, "streamAllwinnerFile: no Allwinner session available")
            return@withContext false
        }
        val camid = AllwinnerMediaRepository.cameraIdFromName(file.name)
        val epoch = parseEpochFromFileName(file.name)
        val durationSec = AllwinnerMediaRepository.parseDurationSecondsFromName(file.name)
        // If the device never sends the first packet within INITIAL_RX_TIMEOUT_MS,
        // the session is a dud — same firmware bug the OEM app hits. Fail fast so
        // the user gets immediate feedback instead of a 4-minute timeout.
        val hardCeilingMs = maxOf(60_000L, durationSec * 4_000L)
        Log.i(TAG, "streamAllwinnerFile: ${file.name} camid=$camid epoch=$epoch " +
                "durationSec=$durationSec ceiling=${hardCeilingMs}ms " +
                "initialRxTimeout=${INITIAL_RX_TIMEOUT_MS}ms")

        val client = AllwinnerRtp2pClient.open(session, camid, file.name, epoch)
            ?: return@withContext false

        val receivedBytes = AtomicLong(0)
        val packetsSeen = AtomicInteger(0)
        var bufferedEmitted = false
        val started = System.currentTimeMillis()
        val lastRxTime = AtomicLong(started)
        val raf = RandomAccessFile(outFile, "rw").apply { setLength(0) }
        try {
            coroutineScope {
                // Watchdog: cancel this scope if (a) no packet within INITIAL_RX_TIMEOUT_MS,
                // (b) no packet for EOF_IDLE_MS after the first one, or (c) the hard ceiling
                // is hit. The collect loop can't self-terminate when nothing arrives because
                // its checks run only on packet reception.
                val watchdog = launch {
                    while (isActive) {
                        delay(500L)
                        val elapsed = System.currentTimeMillis() - started
                        val idle    = System.currentTimeMillis() - lastRxTime.get()
                        val n = packetsSeen.get()
                        if (n == 0 && elapsed >= INITIAL_RX_TIMEOUT_MS) {
                            Log.w(TAG, "watchdog: no packets in ${elapsed}ms — device refused to stream")
                            break
                        }
                        if (elapsed >= hardCeilingMs) {
                            Log.i(TAG, "watchdog: hard ceiling ${elapsed}ms, $n packets")
                            break
                        }
                        if (n > 0 && idle >= EOF_IDLE_MS) {
                            Log.i(TAG, "watchdog: idle ${idle}ms after $n packets, assuming EOF")
                            break
                        }
                    }
                    // Close the socket so the blocked receive() returns and collect exits.
                    try { client.close() } catch (_: Exception) {}
                }

                try {
                    client.packets().collect { payload ->
                        raf.write(payload)
                        val total = receivedBytes.addAndGet(payload.size.toLong())
                        val n = packetsSeen.incrementAndGet()
                        lastRxTime.set(System.currentTimeMillis())
                        if (n <= 3) {
                            Log.d(TAG, "rx packet #$n len=${payload.size}" +
                                " firstBytes=${payload.take(16).joinToString("") { "%02x".format(it) }}")
                        }
                        progress?.invoke(total)

                        if (!bufferedEmitted && onBuffered != null &&
                            (total >= BUFFERED_EMIT_BYTES ||
                             System.currentTimeMillis() - started >= BUFFERED_EMIT_DELAY_MS)
                        ) {
                            bufferedEmitted = true
                            onBuffered("file://${outFile.absolutePath}")
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "streamAllwinnerFile: collect failed: ${e.message}")
        } finally {
            try { raf.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
        val finalCount = packetsSeen.get()
        Log.i(TAG, "streamAllwinnerFile: done, $finalCount packets, ${receivedBytes.get()} bytes")
        if (finalCount == 0) {
            Log.w(TAG, "streamAllwinnerFile: device refused to stream (0 packets received)")
        }
        finalCount > 0
    }

    private fun parseEpochFromFileName(name: String): Long {
        if (name.length < 15) return 0L
        val first = name[0]
        if (first != 'F' && first != 'B' && first != 'f' && first != 'b') return 0L
        val stamp = name.substring(1, 15)
        if (!stamp.all { it.isDigit() }) return 0L
        return try {
            val y  = stamp.substring(0, 4).toInt()
            val mo = stamp.substring(4, 6).toInt()
            val d  = stamp.substring(6, 8).toInt()
            val h  = stamp.substring(8, 10).toInt()
            val mi = stamp.substring(10, 12).toInt()
            val s  = stamp.substring(12, 14).toInt()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, s)
            cal.timeInMillis / 1000L
        } catch (_: Exception) { 0L }
    }

}
