package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.video.Mp4VideoSource
import com.example.trafykamerasikotlin.data.video.OfflineVideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the "pick any video from your phone and produce an AI-overlay
 * copy" debug screen. User flow:
 *   1. Tap "Pick video" → SAF file chooser.
 *   2. We open the picked URI via [Mp4VideoSource], decode every frame via
 *      MediaMetadataRetriever, run the full AI pipeline + re-encode to MP4.
 *   3. Save result into the user's Downloads folder alongside the original,
 *      with an `_ai.mp4` suffix so it sits next to the source without
 *      overwriting anything.
 */
class VideoAiProcessingViewModel(app: Application) : AndroidViewModel(app) {

    sealed class UiState {
        object Idle : UiState()
        data class Picked(val uri: Uri, val displayName: String) : UiState()
        data class Working(val uri: Uri, val displayName: String, val fractionDone: Float,
                           val frameIndex: Long, val totalFrames: Long) : UiState()
        data class Done(val outputFile: File, val frameCount: Long) : UiState()
        data class Failed(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    fun onVideoPicked(uri: Uri) {
        val name = resolveDisplayName(uri) ?: "video.mp4"
        _state.value = UiState.Picked(uri, name)
    }

    fun startProcessing() {
        val current = _state.value
        val uri: Uri
        val displayName: String
        when (current) {
            is UiState.Picked  -> { uri = current.uri; displayName = current.displayName }
            is UiState.Failed  -> return
            is UiState.Working -> return
            else               -> return
        }

        // Output path: Downloads/<basename>_ai.mp4
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base      = displayName.substringBeforeLast('.', displayName).ifBlank { "video" }
        val outFile   = File(downloads, "${base}_ai.mp4")

        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                downloads.mkdirs()
                _state.value = UiState.Working(uri, displayName, 0f, 0L, 0L)

                val proc = OfflineVideoProcessor(context = getApplication())
                val progressJob = launch {
                    proc.state.collect { s ->
                        when (s) {
                            is OfflineVideoProcessor.State.Processing ->
                                _state.value = UiState.Working(uri, displayName, s.fractionDone,
                                    s.frameIndex, s.totalFrames)
                            is OfflineVideoProcessor.State.Done ->
                                _state.value = UiState.Done(s.outputFile, s.frameCount)
                            is OfflineVideoProcessor.State.Failed ->
                                _state.value = UiState.Failed(s.message)
                            else -> {}
                        }
                    }
                }

                // Open the picked video. Mp4VideoSource is backed by
                // MediaMetadataRetriever which handles everything the
                // platform can decode — MP4 (H.264/H.265), MOV, WebM,
                // MKV, and some AVIs. The user may have picked anything.
                val source = try {
                    withContext(Dispatchers.IO) {
                        Mp4VideoSource.open(getApplication(), uri)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Mp4VideoSource.open failed: ${t.message}")
                    _state.value = UiState.Failed("Could not open the selected video: ${t.message ?: ""}")
                    progressJob.cancel()
                    return@launch
                }

                proc.process(source, outFile)
                progressJob.cancel()
                // proc.state emits Done which the collector mapped above.
                // Poke MediaScanner so the file shows in Files/Gallery.
                android.media.MediaScannerConnection.scanFile(
                    getApplication(), arrayOf(outFile.absolutePath), null, null,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
                _state.value = UiState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        _state.value = UiState.Idle
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val cr = getApplication<Application>().contentResolver
        return cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    }

    companion object { private const val TAG = "Trafy.VideoAiVM" }
}
