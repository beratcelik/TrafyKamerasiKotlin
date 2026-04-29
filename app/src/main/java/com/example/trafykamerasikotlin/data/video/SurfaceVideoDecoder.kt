package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * MediaExtractor + MediaCodec configured to render decoded frames into a
 * caller-provided [Surface]. The decoder writes directly to a GPU texture
 * — there's no CPU YUV→ARGB conversion in the hot path.
 *
 * Two-step lifecycle so the caller can construct the encoder + GL pipeline
 * with the right dimensions before binding a surface to the decoder:
 *   1. `open(file|uri)` — opens MediaExtractor, reads track metadata, picks
 *      the video track. Reports `width`/`height`/`frameRate`/`totalFrames`.
 *   2. `start(surface)` — creates and configures the MediaCodec decoder
 *      pointed at [surface], then `start()`s it. After this, [pump] can run.
 */
class SurfaceVideoDecoder private constructor(
    private val extractor: MediaExtractor,
    private val format:    MediaFormat,
    val width:       Int,
    val height:      Int,
    val frameRate:   Int,
    val durationUs:  Long,
    val totalFrames: Long,
) {

    private var decoder: MediaCodec? = null

    /**
     * Create the decoder, configure it to render to [outputSurface], and
     * start it. Must be called once before [pump].
     */
    fun start(outputSurface: Surface) {
        check(decoder == null) { "decoder already started" }
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val d = MediaCodec.createDecoderByType(mime)
        try {
            d.configure(format, outputSurface, null, 0)
            d.start()
        } catch (t: Throwable) {
            d.release(); throw t
        }
        decoder = d
    }

    /**
     * Drive the decoder until end-of-stream. For each rendered frame,
     * [onFrameRendered] is called with the frame's presentation time
     * (microseconds) and zero-based index. Return false from the callback
     * to abort early.
     */
    fun pump(onFrameRendered: (presentationTimeUs: Long, frameIndex: Long) -> Boolean) {
        val dec = checkNotNull(decoder) { "start() not called" }
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var frameIndex = 0L

        while (true) {
            // Feed input.
            if (!inputDone) {
                val inIdx = dec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)
                    if (buf == null) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            dec.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            // Drain output.
            val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "decoder format: ${dec.outputFormat}")
                }
                outIdx >= 0 -> {
                    val pts    = info.presentationTimeUs
                    val isEos  = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val render = info.size > 0
                    // releaseOutputBuffer(idx, true) renders to the attached
                    // Surface — that's what hands the frame to the GL pipeline.
                    dec.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        val keepGoing = onFrameRendered(pts, frameIndex)
                        frameIndex++
                        if (!keepGoing) return
                    }
                    if (isEos) return
                }
                else -> Log.w(TAG, "unexpected dequeue: $outIdx")
            }
        }
    }

    fun close() {
        try { decoder?.stop()    } catch (_: Throwable) {}
        try { decoder?.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
        decoder = null
    }

    companion object {
        private const val TAG = "Trafy.SurfaceDec"

        fun open(file: File): SurfaceVideoDecoder {
            val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            return openFromExtractor(extractor, "file=${file.name}")
        }

        fun open(context: Context, uri: Uri): SurfaceVideoDecoder {
            val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            return openFromExtractor(extractor, "uri=$uri")
        }

        private fun openFromExtractor(extractor: MediaExtractor, sourceTag: String): SurfaceVideoDecoder {
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    trackIdx = i; format = f; break
                }
            }
            if (trackIdx < 0 || format == null) {
                extractor.release()
                throw IllegalStateException("no video track ($sourceTag)")
            }
            extractor.selectTrack(trackIdx)

            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
            else 30
            val totalFrames = if (durationUs > 0L) durationUs * frameRate / 1_000_000L else 0L

            Log.i(TAG, "open: ${w}x$h mime=$mime fps=$frameRate totalFrames=$totalFrames ($sourceTag)")
            return SurfaceVideoDecoder(extractor, format, w, h, frameRate, durationUs, totalFrames)
        }
    }
}
