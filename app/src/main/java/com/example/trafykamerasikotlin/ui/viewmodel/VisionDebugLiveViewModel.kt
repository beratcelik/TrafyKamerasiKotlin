package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.frame.MjpegFrameSource
import com.example.trafykamerasikotlin.data.vision.pipeline.LiveVisionPipeline
import com.example.trafykamerasikotlin.data.vision.util.LatencyHistogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Debug-screen ViewModel for the live MJPEG path. Owns an [MjpegFrameSource]
 * (the debug screen draws its own SurfaceView; the user-facing [LiveScreen]
 * uses its existing player instead) and a [LiveVisionPipeline] shared with
 * production LiveScreen overlay.
 */
class VisionDebugLiveViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val pipeline = LiveVisionPipeline(context = app).also { it.start() }

    val scene:          StateFlow<TrackedScene?>            = pipeline.scene
    val latency:        StateFlow<LatencyHistogram.Snapshot>  = pipeline.latency
    val frameCounter:   StateFlow<Int>                      = pipeline.submittedCount

    private val _sourceFrameSize = MutableStateFlow<Size?>(null)
    val sourceFrameSize: StateFlow<Size?> = _sourceFrameSize.asStateFlow()

    private var pendingUrl: String? = null
    private var pendingNetwork: Network? = null
    private var surface: Surface? = null
    private var source: MjpegFrameSource? = null
    private var collectorJob: Job? = null

    fun setSurface(s: Surface) {
        Log.d(TAG, "setSurface: $s")
        surface = s
        maybeStart()
    }

    fun clearSurface() {
        Log.d(TAG, "clearSurface")
        surface = null
        stopStream()
    }

    fun connect(rtspUrl: String, network: Network?) {
        Log.i(TAG, "connect: url=$rtspUrl network=$network surface=$surface")
        pendingUrl = rtspUrl
        pendingNetwork = network
        _state.value = LiveState.Connecting
        maybeStart()
    }

    fun disconnect() {
        stopStream()
        pendingUrl = null
        _state.value = LiveState.Idle
    }

    private fun maybeStart() {
        if (source != null) return
        val url  = pendingUrl ?: return
        val surf = surface    ?: return
        val network: Network? = pendingNetwork ?: activeDashcamNetwork()
        Log.i(TAG, "maybeStart: starting MjpegFrameSource url=$url network=$network")
        val src = MjpegFrameSource(
            rtspUrl = url,
            network = network,
            displaySurface = surf,
            onError = { msg ->
                Log.e(TAG, "stream error: $msg")
                _state.value = LiveState.Error(msg)
            },
        )
        src.start()
        source = src
        _state.value = LiveState.Streaming

        collectorJob = viewModelScope.launch(Dispatchers.Default) {
            src.frames.collect { frame ->
                val size = Size(frame.bitmap.width, frame.bitmap.height)
                if (_sourceFrameSize.value != size) _sourceFrameSize.value = size
                pipeline.submit(frame.bitmap, frame.timestampNanos)
            }
        }
    }

    private fun activeDashcamNetwork(): Network? {
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.activeNetwork
    }

    private fun stopStream() {
        source?.stop()
        source = null
        collectorJob?.cancel(); collectorJob = null
        pipeline.reset()
    }

    override fun onCleared() {
        stopStream()
        pipeline.release()
        super.onCleared()
    }

    companion object { private const val TAG = "Trafy.VisionLiveVM" }
}

sealed class LiveState {
    object Idle : LiveState()
    object Connecting : LiveState()
    object Streaming  : LiveState()
    data class Error(val message: String) : LiveState()
}
