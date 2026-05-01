package com.example.trafykamerasikotlin.data.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import java.io.File
import java.nio.ByteBuffer

/**
 * H.264 encoder + MP4 muxer that writes one composited frame at a time.
 *
 * The composition = dashcam video frame (MJPEG → Bitmap) with the AI
 * overlay (vehicle boxes, plate boxes, voted plate text) painted on top.
 * We do the composition on a regular Android [Canvas] targeting a staging
 * bitmap, then upload that into a GL texture and draw it to the MediaCodec
 * input surface. Slower than a pure-GL overlay path but a lot less code,
 * and the Canvas API gives us exact parity with the live overlay's
 * [com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay].
 *
 * Thread model: single-threaded. Caller feeds frames from their loop.
 * [finish] drains the encoder and closes the muxer.
 */
class OverlayVideoEncoder(
    private val outputFile: File,
    private val width:      Int,
    private val height:     Int,
    private val frameRate:  Int = 30,
    /**
     * Bitrate = 0.10 bits-per-pixel-per-second. Lower than the 0.15 "quality"
     * default but plenty for dashcam footage (scene content is mostly static
     * road with small moving objects, which H.264 handles efficiently). The
     * Adreno 618 hardware encoder starts stalling above ~8 Mbps on 720p —
     * that stall presents as `eglSwapBuffers failed` because the input
     * surface's buffer queue can't drain fast enough.
     */
    bitrateBps: Int = (width * height * frameRate * 0.10f).toInt(),
    /**
     * Keyframe interval in seconds. Lower = quicker seeking but fatter files
     * and more encoder work. 2 s is the default for phone-encoded video.
     */
    keyFrameIntervalSec: Int = 2,
) {

    private val encoder: MediaCodec
    private val inputSurface: android.view.Surface
    private val egl: InputSurfaceEgl
    private val blitter: FullScreenQuadBlitter

    private val muxer: MediaMuxer
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    private val compositeBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val compositeCanvas: Canvas = Canvas(compositeBitmap)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()

        egl = InputSurfaceEgl(inputSurface)
        egl.makeCurrent()
        blitter = FullScreenQuadBlitter()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.i(TAG, "configured: ${width}x${height}@${frameRate} bitrate=$bitrateBps → ${outputFile.name}")
    }

    /**
     * Encode one frame. `frameBitmap` must match [width] × [height]. `scene`
     * supplies the boxes + labels to paint on top. `presentationTimeUs` is
     * the output timestamp — monotonic strictly increasing per call.
     */
    fun encodeFrame(frameBitmap: Bitmap, scene: TrackedScene?, presentationTimeUs: Long) {
        composite(frameBitmap, scene)
        // Draw to the GL texture and advance the MediaCodec surface.
        blitter.draw(compositeBitmap)
        egl.setPresentationTime(presentationTimeUs * 1000L)  // nanos
        egl.swapBuffers()
        drain(endOfStream = false)
    }

    /** Call once at the end of input. Drains remaining buffers + closes muxer. */
    fun finish() {
        encoder.signalEndOfInputStream()
        drain(endOfStream = true)
        try { encoder.stop() } catch (_: Throwable) {}
        encoder.release()
        egl.release()
        blitter.release()
        try { inputSurface.release() } catch (_: Throwable) {}
        compositeBitmap.recycle()
        if (muxerStarted) {
            try { muxer.stop() } catch (_: Throwable) {}
        }
        try { muxer.release() } catch (_: Throwable) {}
        Log.i(TAG, "finished → ${outputFile.absolutePath}")
    }

    // ── composition ────────────────────────────────────────────────────────

    /** Draw the source frame, then the scene's boxes + labels on top. */
    private fun composite(src: Bitmap, scene: TrackedScene?) {
        compositeCanvas.drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, width, height),
            null,
        )
        if (scene == null) return

        // Scale factor from scene's source-frame coords to output frame coords.
        // scene.sourceFrameSize is the bitmap we ran inference on (= src size).
        val sx = width  / scene.sourceFrameSize.width.toFloat()
        val sy = height / scene.sourceFrameSize.height.toFloat()

        // Vehicles (red). Prefer tracks when we have them — stable labels beat flickering dets.
        val vehicles = scene.tracks ?: scene.detections.map { null to it }
        overlayPaint.color = Color.RED
        textPaint.color = Color.WHITE
        if (scene.tracks != null) {
            scene.tracks!!.forEach { t ->
                val x1 = t.bbox.left   * sx
                val y1 = t.bbox.top    * sy
                val x2 = t.bbox.right  * sx
                val y2 = t.bbox.bottom * sy
                compositeCanvas.drawRect(x1, y1, x2, y2, overlayPaint)
                compositeCanvas.drawText(
                    "${t.cls.name.lowercase()}#${t.trackId}  ${"%.2f".format(t.confidence)}",
                    x1 + 4f,
                    (y1 - 6f).coerceAtLeast(textPaint.textSize),
                    textPaint,
                )
            }
        } else {
            scene.detections.forEach { d ->
                val x1 = d.bbox.left   * sx
                val y1 = d.bbox.top    * sy
                val x2 = d.bbox.right  * sx
                val y2 = d.bbox.bottom * sy
                compositeCanvas.drawRect(x1, y1, x2, y2, overlayPaint)
                compositeCanvas.drawText(
                    "${d.cls.name.lowercase()}  ${"%.2f".format(d.confidence)}",
                    x1 + 4f,
                    (y1 - 6f).coerceAtLeast(textPaint.textSize),
                    textPaint,
                )
            }
        }

        // Plates (yellow). Show voted text where available, else detection score.
        overlayPaint.color = Color.YELLOW
        textPaint.color = Color.YELLOW
        scene.plates?.forEach { p ->
            val x1 = p.bbox.left   * sx
            val y1 = p.bbox.top    * sy
            val x2 = p.bbox.right  * sx
            val y2 = p.bbox.bottom * sy
            compositeCanvas.drawRect(x1, y1, x2, y2, overlayPaint)
            val voted = p.votedText
            val label = if (voted != null && voted.text.isNotEmpty()) {
                "${voted.text}  ✓${voted.votes}"
            } else {
                "plate ${"%.2f".format(p.confidence)}"
            }
            compositeCanvas.drawText(
                label,
                x1 + 2f,
                y2 + textPaint.textSize + 2f,
                textPaint,
            )
        }
        // Use `vehicles` to avoid an unused-var warning on the null-tracks branch.
        vehicles.size
    }

    // ── encode-side bookkeeping ────────────────────────────────────────────

    private fun drain(endOfStream: Boolean) {
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
                    // Skip codec-config buffers; format change already delivered them.
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

    companion object { private const val TAG = "Trafy.OverlayEnc" }
}
