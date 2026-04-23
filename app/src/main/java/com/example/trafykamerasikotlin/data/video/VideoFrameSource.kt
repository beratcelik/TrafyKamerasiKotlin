package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/**
 * A decoded video frame handed to [OfflineVideoProcessor].
 */
data class DecodedFrame(
    val bitmap:             Bitmap,
    val presentationTimeUs: Long,
    val frameIndex:         Long,
)

/**
 * Shared interface over the different ways we can read frames out of a
 * video container. Every source exposes the same 4 bits of metadata + a
 * lazy sequence of [DecodedFrame]s. Implementations own their resources
 * and should be [close]d after iteration.
 *
 * Implementations today:
 *  - [AviMjpegVideoSource] — dashcam AVI/MJPEG, uses our [AviMjpegReader].
 *  - [Mp4VideoSource]      — any format Android's [MediaMetadataRetriever]
 *    can decode (MP4, MOV, WebM, MKV, some AVIs). Slower per-frame but
 *    works on anything the platform can play.
 */
interface VideoFrameSource : AutoCloseable {
    val width:          Int
    val height:         Int
    val microsPerFrame: Long
    val totalFrames:    Long
    fun frames(): Sequence<DecodedFrame>
}

/**
 * AVI + MJPEG source, used for dashcam-downloaded .avi files. Decodes
 * each chunk's JPEG bytes into an ARGB_8888 bitmap as we iterate.
 */
class AviMjpegVideoSource(private val reader: AviMjpegReader) : VideoFrameSource {
    override val width:          Int  get() = reader.width
    override val height:         Int  get() = reader.height
    override val microsPerFrame: Long get() = reader.microsPerFrame
    override val totalFrames:    Long get() = reader.totalFrames

    override fun frames(): Sequence<DecodedFrame> = sequence {
        for (avi in reader.frames()) {
            val bmp = BitmapFactory.decodeByteArray(avi.jpeg, 0, avi.jpeg.size) ?: continue
            val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            yield(DecodedFrame(
                bitmap             = argb,
                presentationTimeUs = avi.presentationTimeUs,
                frameIndex         = avi.frameIndex,
            ))
        }
    }

    override fun close() { reader.close() }
}

/**
 * Generic video source backed by [MediaMetadataRetriever]. Handles any
 * format Android can decode — MP4/H.264 is the typical case; also MOV,
 * WebM, MKV, and some AVIs. Uses `getFrameAtIndex` on API 28+ (fast,
 * stateful) and falls back to `getFrameAtTime` on older devices.
 *
 * Expensive per call (50–200 ms on mid-range) so we recommend this for
 * user-triggered offline processing only, not live/live-esque use cases.
 */
class Mp4VideoSource private constructor(
    private val retriever: MediaMetadataRetriever,
    override val width:          Int,
    override val height:         Int,
    override val microsPerFrame: Long,
    override val totalFrames:    Long,
) : VideoFrameSource {

    override fun frames(): Sequence<DecodedFrame> = sequence {
        // getFrameAtIndex exists from API 28. Below that we walk by time.
        val canIndex = android.os.Build.VERSION.SDK_INT >= 28 && totalFrames > 0
        if (canIndex) {
            for (i in 0 until totalFrames) {
                val bmp = try { retriever.getFrameAtIndex(i.toInt()) } catch (t: Throwable) {
                    Log.w(TAG, "getFrameAtIndex($i) failed: ${t.message}")
                    null
                } ?: continue
                val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                    else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                yield(DecodedFrame(
                    bitmap             = argb,
                    presentationTimeUs = i * microsPerFrame,
                    frameIndex         = i,
                ))
            }
        } else {
            // Time-based walk. Advance by microsPerFrame each step.
            var idx = 0L
            val endUs = if (totalFrames > 0) totalFrames * microsPerFrame else Long.MAX_VALUE
            var t = 0L
            while (t < endUs) {
                val bmp = try {
                    retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (tt: Throwable) {
                    Log.w(TAG, "getFrameAtTime($t) failed: ${tt.message}")
                    null
                } ?: break
                val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                    else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                yield(DecodedFrame(argb, t, idx))
                idx++
                t += microsPerFrame
            }
        }
    }

    override fun close() { try { retriever.release() } catch (_: Throwable) {} }

    companion object {
        private const val TAG = "Trafy.Mp4Video"

        /**
         * Open any video via SAF Uri. Reads metadata and returns a configured
         * [Mp4VideoSource]. Throws on decode failure or missing metadata.
         */
        fun open(context: Context, uri: Uri): Mp4VideoSource {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                require(w != null && h != null) { "video track has no width/height metadata" }

                val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frameCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                val fpsAvg = if (durationMs > 0L && frameCount > 0L) {
                    (frameCount * 1000.0 / durationMs).toInt().coerceIn(1, 120)
                } else 30
                val microsPerFrame = 1_000_000L / fpsAvg
                Log.i(TAG, "open: ${w}x${h} durationMs=$durationMs frames=$frameCount fps≈$fpsAvg")
                return Mp4VideoSource(
                    retriever      = r,
                    width          = w,
                    height         = h,
                    microsPerFrame = microsPerFrame,
                    totalFrames    = frameCount,
                )
            } catch (t: Throwable) {
                r.release()
                throw t
            }
        }
    }
}
