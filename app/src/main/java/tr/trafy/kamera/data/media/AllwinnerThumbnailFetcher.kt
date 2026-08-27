package tr.trafy.kamera.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import tr.trafy.kamera.data.allwinner.AllwinnerRtp2pClient
import tr.trafy.kamera.data.allwinner.AllwinnerSessionHolder
import tr.trafy.kamera.data.allwinner.MpegTsMuxer
import tr.trafy.kamera.data.allwinner.splitAdtsFrames
import tr.trafy.kamera.data.allwinner.splitAnnexBIntoAccessUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Coil [Fetcher] that grabs the first frame of an Allwinner V853 (Trafy Dos
 * Internet) recording and caches it as a local JPEG. Used because the cam
 * exposes no thumbnail RPC — confirmed by reverse-engineering CloudSpirit's
 * `libcloudspirit_native.so`, which contains `MediaProcess::feedThumbnail`
 * and "Retrive Thumbnail Only, no outputfile" log lines: the OEM does
 * exactly this client-side extraction.
 *
 * Trigger: callers set `MediaFile.thumbnailUrl` to
 * `allwinner-thumb://<deviceIp>/<filename>`; the [Factory] matches the scheme.
 *
 * Pipeline per file:
 *   1. Open an rtp2p file-playback session (`time:0`, camid parsed from name).
 *   2. Drain KCP packets, split each into AUs, mux to a temp `.ts` via
 *      [MpegTsMuxer] until ~[MIN_BUFFER_BYTES] is on disk.
 *   3. Close the session, run [MediaMetadataRetriever] on the temp `.ts`.
 *   4. Save the bitmap as JPEG, delete the temp `.ts`.
 *
 * Concurrency: the cam serves only one rtp2p session at a time, and a
 * thumbnail session blocks the user's recording on the cam side. We
 * serialise via a 1-permit semaphore process-wide and bound each
 * extraction with [EXTRACT_TIMEOUT_MS].
 */
class AllwinnerThumbnailFetcher(
    private val uri: Uri,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val deviceIp = uri.host ?: error("missing host in $uri")
        val filename = uri.path?.removePrefix("/") ?: error("missing path in $uri")
        val cacheKey = sha1(filename)
        val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val cached = File(cacheDir, "$cacheKey.jpg")

        if (!cached.exists() || cached.length() == 0L) {
            extractSemaphore.withPermit {
                extractThumbnail(deviceIp, filename, cached)
            }
        }

        if (cached.length() == 0L) {
            throw RuntimeException("Thumbnail extraction failed for $filename")
        }
        return SourceResult(
            source     = ImageSource(file = cached.toOkioPath(), fileSystem = FileSystem.SYSTEM),
            mimeType   = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun extractThumbnail(deviceIp: String, filename: String, dest: File) {
        val tmpTs = File.createTempFile("allwinner_thumb_", ".ts", context.cacheDir)
        try {
            withTimeout(EXTRACT_TIMEOUT_MS) {
                streamFirstChunk(deviceIp, filename, tmpTs)
                if (tmpTs.length() < MIN_DECODE_BYTES) {
                    Log.w(TAG, "$filename: only ${tmpTs.length()}B buffered, not enough to decode")
                    return@withTimeout
                }
                withContext(Dispatchers.IO) {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(tmpTs.absolutePath)
                        val bmp = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: error("getFrameAtTime returned null")
                        dest.outputStream().use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 80, it)
                        }
                        bmp.recycle()
                    } finally {
                        mmr.release()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "extract timeout (${EXTRACT_TIMEOUT_MS}ms): $filename")
        } catch (e: Exception) {
            Log.w(TAG, "extract failed: $filename — ${e.message}")
        } finally {
            tmpTs.delete()
        }
        if (dest.length() == 0L) {
            dest.delete()
        } else {
            Log.d(TAG, "extracted: $filename → ${dest.length()} B")
        }
    }

    private suspend fun streamFirstChunk(deviceIp: String, filename: String, tsFile: File) {
        val session = AllwinnerSessionHolder.requireAlive(deviceIp) ?: run {
            Log.w(TAG, "no Allwinner session for $deviceIp")
            return
        }
        val camid = AllwinnerMediaRepository.cameraIdFromName(filename)
        val client = AllwinnerRtp2pClient.open(session, camid, filename, 0L) ?: run {
            Log.w(TAG, "rtp2p open failed for $filename")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                BufferedOutputStream(FileOutputStream(tsFile)).use { tsOut ->
                    val muxer = MpegTsMuxer(tsOut)
                    var bytesIn = 0L
                    client.packets()
                        .transformWhile { sample ->
                            emit(sample)
                            bytesIn += sample.data.size
                            bytesIn < MIN_BUFFER_BYTES
                        }
                        .collect { sample ->
                            when (sample) {
                                is AllwinnerRtp2pClient.Sample.Video -> {
                                    val aus = splitAnnexBIntoAccessUnits(sample.data)
                                    for (au in aus) muxer.writeFrame(au)
                                }
                                is AllwinnerRtp2pClient.Sample.Audio -> {
                                    val frames = splitAdtsFrames(sample.data)
                                    for (f in frames) muxer.writeAdtsFrame(f)
                                }
                            }
                            muxer.flush()
                        }
                }
            }
        } finally {
            // NonCancellable inside close() guarantees rtp2p stop reaches the
            // cam even if our parent scope is being torn down. Without it the
            // next thumbnail request would race the orphan session and ICMP.
            client.close()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    class Factory(
        private val context: Context,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SCHEME) return null
            return AllwinnerThumbnailFetcher(data, context)
        }
    }

    companion object {
        const val SCHEME = "allwinner-thumb"
        private const val TAG = "Trafy.AllwinnerThumb"
        private const val CACHE_DIR = "allwinner_thumbs"

        /**
         * Hard upper bound per file. The cam dribbles data slowly during the
         * first KCP exchange (~3 s before the first useful frame batch), so
         * we leave generous headroom — exceeding it just means this file
         * stays unthumbnailed, not a crash.
         */
        private const val EXTRACT_TIMEOUT_MS = 12_000L

        /**
         * How many bytes to mux before stopping the rtp2p session. The cam's
         * early-flush threshold is 128 KB per video burst, so 256 KB covers
         * two bursts and reliably gives MMR enough TS context to find a
         * sync point and decode the first frame. Going lower races MMR to
         * "no track found"-style failures.
         */
        private const val MIN_BUFFER_BYTES = 256 * 1024

        /** Below this we don't even ask MMR — guaranteed to fail. */
        private const val MIN_DECODE_BYTES = 32 * 1024

        /**
         * One-at-a-time gate: the cam serves only one rtp2p session, and
         * each thumbnail session pauses recording on its side. Coil queues
         * additional Fetcher invocations behind this permit.
         */
        private val extractSemaphore = Semaphore(permits = 1)

        fun urlFor(deviceIp: String, filename: String): String =
            "$SCHEME://$deviceIp/$filename"
    }
}
