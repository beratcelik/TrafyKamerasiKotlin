package com.example.trafykamerasikotlin.data.vision.frame

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.FrameSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Debug-oriented [FrameSource] backed by a picked video file.
 *
 * Chunk 1 only needs a single frame for bounding-box verification, so this
 * implementation decodes the first frame on [start] and emits it once.
 * The real decode pipeline (every frame, for offline MP4 processing) lives
 * in a later chunk. This class is intentionally simple — the interface is
 * the important part, not the implementation.
 */
class FileFrameSource(
    private val context: Context,
    private val uri: Uri,
    private val requestedTimeUs: Long = 0L,
) : FrameSource {

    private val _frames = MutableSharedFlow<Frame>(
        replay            = 1,
        extraBufferCapacity = 1,
        onBufferOverflow  = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<Frame> = _frames.asSharedFlow()

    override fun start() {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(
                requestedTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: run {
                Log.w(TAG, "getFrameAtTime returned null for $uri")
                return
            }
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
            }
            _frames.tryEmit(Frame(bitmap = argb, timestampNanos = System.nanoTime()))
        } catch (t: Throwable) {
            Log.e(TAG, "failed to extract frame from $uri", t)
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    override fun stop() {
        // Nothing to tear down — decode was synchronous and already complete.
    }

    companion object {
        private const val TAG = "Trafy.FileFrameSource"
    }
}
