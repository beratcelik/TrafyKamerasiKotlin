package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

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
        // Index-based walk is faster but only works on containers that expose
        // a per-frame sample table. HiSilicon dashcams produce MP4s where
        // frame 0 is indexed but no other frame is, so probing only at 0 is
        // a false positive. Probe at index 0 AND at a later frame (~5);
        // index mode is only used when both succeed.
        val canIndex = android.os.Build.VERSION.SDK_INT >= 28 && totalFrames > 0 &&
            run {
                val deepIdx = minOf(5L, totalFrames - 1).coerceAtLeast(0L).toInt()
                val probeA = try { retriever.getFrameAtIndex(0) } catch (t: Throwable) {
                    Log.w(TAG, "index-mode probe[0] threw: ${t.message}")
                    null
                }
                if (probeA == null) {
                    Log.w(TAG, "index-mode probe[0] returned null — using time-based walk")
                    return@run false
                }
                probeA.recycle()
                if (deepIdx == 0) return@run true   // single-frame video
                val probeB = try { retriever.getFrameAtIndex(deepIdx) } catch (t: Throwable) {
                    Log.w(TAG, "index-mode probe[$deepIdx] threw: ${t.message}")
                    null
                }
                if (probeB == null) {
                    Log.w(TAG, "index-mode probe[$deepIdx] returned null — using time-based walk")
                    false
                } else {
                    probeB.recycle()
                    true
                }
            }
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
            // Time-based walk. Advance by microsPerFrame each step. Used
            // both as the API < 28 path and the HiSilicon-style fallback.
            // OPTION_CLOSEST decodes the nearest frame regardless of sync
            // type, so we don't lose temporal resolution to keyframe spacing.
            var idx = 0L
            val endUs = if (totalFrames > 0) totalFrames * microsPerFrame else Long.MAX_VALUE
            var t = 0L
            var consecutiveFailures = 0
            while (t < endUs) {
                val bmp = try {
                    retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (tt: Throwable) {
                    Log.w(TAG, "getFrameAtTime($t) failed: ${tt.message}")
                    null
                }
                if (bmp == null) {
                    // Some containers occasionally return null mid-stream when
                    // we land between sync frames. Try OPTION_CLOSEST_SYNC as
                    // a one-shot retry; if that also fails twice in a row,
                    // assume EOF and stop.
                    val retry = try {
                        retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Throwable) { null }
                    if (retry == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) break
                        t += microsPerFrame
                        continue
                    }
                    consecutiveFailures = 0
                    val argb = if (retry.config == Bitmap.Config.ARGB_8888) retry
                        else retry.copy(Bitmap.Config.ARGB_8888, false).also { retry.recycle() }
                    yield(DecodedFrame(argb, t, idx))
                } else {
                    consecutiveFailures = 0
                    val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                        else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                    yield(DecodedFrame(argb, t, idx))
                }
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
         * [VideoFrameSource]. Tries `MediaMetadataRetriever` first; if it
         * can't decode past frame 0 (HiSilicon dashcams produce MP4s like
         * this), falls back to a `MediaExtractor` + `MediaCodec` source.
         * Throws on decode failure or missing metadata.
         */
        fun open(context: Context, uri: Uri): VideoFrameSource {
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

                // Probe two distinct timestamps to make sure MMR can actually
                // walk the file, not just hit frame 0. Some dashcam MP4s have
                // an indexed first frame and nothing else.
                val mmrUsable = probeMmrUsable(r, microsPerFrame, frameCount)
                if (!mmrUsable) {
                    Log.w(TAG, "MMR can't walk this file; falling back to MediaCodec source")
                    r.release()
                    val file = uriToFile(context, uri)
                        ?: throw IllegalStateException("MediaCodec fallback requires a file:// uri")
                    return MediaCodecVideoSource.open(file)
                }

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

        private fun probeMmrUsable(
            r: MediaMetadataRetriever, microsPerFrame: Long, totalFrames: Long,
        ): Boolean {
            // Try a mid-file timestamp; if MMR can't decode it, neither index
            // nor time-based walking will give us frames. Use OPTION_CLOSEST
            // first, then OPTION_CLOSEST_SYNC as a last resort.
            val midUs = if (totalFrames > 0) (totalFrames / 2) * microsPerFrame
                else 1_000_000L  // 1 sec into the file
            val tries = listOf(
                MediaMetadataRetriever.OPTION_CLOSEST,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            for (mode in tries) {
                val bmp = try { r.getFrameAtTime(midUs, mode) } catch (_: Throwable) { null }
                if (bmp != null) { bmp.recycle(); return true }
            }
            return false
        }

        private fun uriToFile(context: Context, uri: Uri): File? {
            if (uri.scheme == "file") return uri.path?.let(::File)
            // SAF / content uris would need to be copied to a temp file first
            // before MediaExtractor can read them; we don't bother because the
            // MMR path handles those fine in practice.
            return null
        }
    }
}

// ── MediaExtractor + MediaCodec fallback ───────────────────────────────────

/**
 * Frame source backed by Android's lower-level `MediaExtractor` (sample
 * reader) and `MediaCodec` (hardware H.264/H.265 decoder). Used when
 * [Mp4VideoSource] can't walk a file (e.g. HiSilicon dashcam MP4s).
 *
 * Output goes to ByteBuffers (no surface), and we read each decoded frame
 * via [MediaCodec.getOutputImage] which normalises the codec's
 * vendor-specific layout into a standard YUV_420_888 [Image]. Going
 * through `ImageReader` here proved fragile — Qualcomm's
 * `OMX_COLOR_FormatYUV420SemiPlanar` (color-format=2141391878) crashes
 * inside `ImageReader$SurfaceImage.nativeCreatePlanes` on Adreno 618.
 *
 * Per-frame cost is dominated by the YUV→ARGB conversion (~150 ms at
 * 1080p on a Snapdragon 7-class CPU). Acceptable for offline burn-in.
 */
class MediaCodecVideoSource private constructor(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    override val width:          Int,
    override val height:         Int,
    override val microsPerFrame: Long,
    override val totalFrames:    Long,
) : VideoFrameSource {

    override fun frames(): Sequence<DecodedFrame> = sequence {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var frameIndex = 0L

        while (true) {
            // Feed one input sample if the codec has room.
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)
                    if (buf == null) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            // Drain one output sample.
            val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "decoder format: ${decoder.outputFormat}")
                }
                outIdx >= 0 -> {
                    val pts = info.presentationTimeUs
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val hasData = info.size > 0

                    // Copy pixels OUT of the codec's buffer (yuv420ToArgb
                    // produces a standalone Bitmap), close the Image, and
                    // release the output buffer back to the decoder BEFORE
                    // yielding. The consumer (inference + encode) takes
                    // ~500 ms per frame; if we held the buffer through the
                    // yield, the codec's output pool (~5 slots) drained and
                    // it emitted a premature EOS — that's why earlier runs
                    // only produced 5 frames out of 635.
                    var bmp: Bitmap? = null
                    if (hasData) {
                        val image = try { decoder.getOutputImage(outIdx) } catch (t: Throwable) {
                            Log.w(TAG, "getOutputImage($outIdx) failed: ${t.message}")
                            null
                        }
                        if (image != null) {
                            try { bmp = yuv420ToArgb(image) }
                            finally { image.close() }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)

                    if (bmp != null) yield(DecodedFrame(bmp, pts, frameIndex++))
                    if (isEos) break
                }
            }
        }
    }

    override fun close() {
        try { decoder.stop() } catch (_: Throwable) {}
        try { decoder.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "Trafy.MediaCodecSrc"

        fun open(file: File): MediaCodecVideoSource {
            val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrack = i; format = f; break
                }
            }
            if (videoTrack < 0 || format == null) {
                extractor.release()
                throw IllegalStateException("no video track in ${file.name}")
            }
            extractor.selectTrack(videoTrack)

            val width  = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val mime   = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
            else 30
            val microsPerFrame = 1_000_000L / frameRate
            val totalFrames    = if (durationUs > 0L) durationUs / microsPerFrame else 0L
            Log.i(TAG, "open: ${width}x$height mime=$mime durationUs=$durationUs fps=$frameRate")

            // Pin the codec to YUV_420_Flexible. Most decoders honour this
            // and route output through getOutputImage's normalisation path.
            // Without it, Qualcomm decoders default to a vendor semi-planar
            // layout that crashes inside ImageReader's plane creation.
            try {
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
            } catch (_: Throwable) { /* some devices reject this; carry on */ }

            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                // ByteBuffer output (no surface) — getOutputImage(idx) gives
                // us the normalised YUV_420_888 view per frame.
                decoder.configure(format, null, null, 0)
                decoder.start()
            } catch (t: Throwable) {
                decoder.release(); extractor.release(); throw t
            }

            return MediaCodecVideoSource(
                extractor, decoder,
                width, height, microsPerFrame, totalFrames,
            )
        }

        /**
         * YUV_420_888 → ARGB_8888 bitmap (BT.601 limited range).
         * Pure-Kotlin reference; ~150 ms per 1080p frame on Snapdragon 7-class.
         */
        private fun yuv420ToArgb(image: Image): Bitmap {
            val w = image.width
            val h = image.height
            val pixels = IntArray(w * h)

            val planes = image.planes
            val yBuf = planes[0].buffer; val uBuf = planes[1].buffer; val vBuf = planes[2].buffer
            val yRow = planes[0].rowStride
            val uRow = planes[1].rowStride;  val uPx = planes[1].pixelStride
            val vRow = planes[2].rowStride;  val vPx = planes[2].pixelStride

            var dst = 0
            for (y in 0 until h) {
                val yLine = y * yRow
                val uvY   = y shr 1
                val uLine = uvY * uRow
                val vLine = uvY * vRow
                for (x in 0 until w) {
                    val Y = yBuf.get(yLine + x).toInt() and 0xFF
                    val uvX = x shr 1
                    val U = (uBuf.get(uLine + uvX * uPx).toInt() and 0xFF) - 128
                    val V = (vBuf.get(vLine + uvX * vPx).toInt() and 0xFF) - 128
                    // BT.601 fixed-point (1024-scaled) — avoids float math.
                    var r = Y + ((1436 * V) shr 10)
                    var g = Y - ((733  * V + 352 * U) shr 10)
                    var b = Y + ((1815 * U) shr 10)
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    pixels[dst++] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
