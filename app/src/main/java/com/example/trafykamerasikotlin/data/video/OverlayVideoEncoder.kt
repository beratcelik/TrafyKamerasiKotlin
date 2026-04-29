package com.example.trafykamerasikotlin.data.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * H.264 encoder + MP4 muxer. Composition is now handled by
 * [GlOverlayPipeline] via the encoder's input [Surface] — this class no
 * longer touches EGL or Bitmaps. Caller drives:
 *
 *   1. Construct the encoder. Read [inputSurface], hand it to a
 *      `GlOverlayPipeline` as its window-surface target.
 *   2. After each `eglSwapBuffers` from the pipeline, call [drain] to pump
 *      encoded buffers into the muxer.
 *   3. At end-of-stream, call [finish] — drains the encoder and closes the
 *      muxer.
 */
class OverlayVideoEncoder(
    private val outputFile: File,
    private val width:      Int,
    private val height:     Int,
    private val frameRate:  Int = 30,
    /**
     * Bitrate = 0.10 bits-per-pixel-per-second, clamped at 8 Mbps. Lower than
     * the 0.15 "quality" default but plenty for dashcam footage. The 8 Mbps
     * cap is a hard limit — the Adreno 618 hardware encoder starts stalling
     * above that, presenting as `eglSwapBuffers failed` because the input
     * surface's buffer queue can't drain fast enough. Without the clamp,
     * full-resolution dashcam footage (2560×1440) would compute to ~11 Mbps
     * and trip the stall.
     */
    bitrateBps: Int = ((width.toLong() * height * frameRate * 0.10f).toInt())
        .coerceAtMost(8_000_000),
    /** Keyframe interval in seconds. 2 s matches phone-encoded video defaults. */
    keyFrameIntervalSec: Int = 2,
) {

    private val encoder: MediaCodec
    /** Hand this to [GlOverlayPipeline] as its EGL window surface target. */
    val inputSurface: Surface

    private val muxer: MediaMuxer
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.i(TAG, "configured: ${width}x${height}@${frameRate} bitrate=$bitrateBps → ${outputFile.name}")
    }

    /** Pump encoded buffers to the muxer. Call after each presented frame. */
    fun drain(endOfStream: Boolean = false) {
        if (endOfStream) encoder.signalEndOfInputStream()
        while (true) {
            val outIx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // Keep polling until we see BUFFER_FLAG_END_OF_STREAM.
                }
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "encoder format changed twice" }
                    val newFormat = encoder.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                    Log.i(TAG, "muxer started, trackIndex=$videoTrackIndex")
                }
                outIx >= 0 -> {
                    val buf: ByteBuffer = encoder.getOutputBuffer(outIx)
                        ?: error("null output buffer at $outIx")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, buf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "EOS received from encoder")
                        return
                    }
                }
                else -> Log.w(TAG, "unexpected dequeue result: $outIx")
            }
        }
    }

    /** End the stream + close the muxer. Idempotent — safe to call once on success or in finally. */
    fun finish() {
        try { drain(endOfStream = true) } catch (t: Throwable) { Log.w(TAG, "drain on finish failed", t) }
        try { encoder.stop()    } catch (_: Throwable) {}
        try { encoder.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
        if (muxerStarted) {
            try { muxer.stop() } catch (_: Throwable) {}
        }
        try { muxer.release() } catch (_: Throwable) {}
        Log.i(TAG, "finished → ${outputFile.absolutePath}")
    }

    companion object { private const val TAG = "Trafy.OverlayEnc" }
}
