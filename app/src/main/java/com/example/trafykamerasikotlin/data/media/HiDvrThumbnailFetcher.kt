package com.example.trafykamerasikotlin.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest

/**
 * Coil [Fetcher] that extracts the first frame of an HiDVR-family dashcam
 * MP4 and caches it as a local JPEG thumbnail. Used because the firmware
 * on Trafy Uno Pro (and presumably other G3518 boards) does NOT generate
 * `.thm` sidecars — every probe returns HTTP 404. Trafy Uno (GeneralPlus)
 * and other chipsets that do produce thumbnails server-side aren't routed
 * through this path.
 *
 * Trigger: callers set `MediaFile.thumbnailUrl` to a `hidvr-thumb://<ip>/<path>`
 * URI; the [Factory] matches that scheme.
 *
 * Why we don't let MMR talk HTTP directly: G3518 firmware's chunked replies
 * trip MMR's native HTTP path ~80% of the time ("FileSourceWrapper Init
 * returned -1004", "videoFrame is NULL", ~25 s timeout). Instead we pull the
 * first ~4 MB of the MP4 via the network-bound [DashcamHttpClient] and feed
 * MMR a local file path — fast and reliable.
 */
class HiDvrThumbnailFetcher(
    private val uri: Uri,
    private val context: Context,
    private val network: Network?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val httpUrl = "http://${uri.host}${uri.path}"
        val cacheKey = sha1(httpUrl)
        val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val cached = File(cacheDir, "$cacheKey.jpg")

        if (!cached.exists() || cached.length() == 0L) {
            // Snapshot the gate generation at entry. A newer Media load
            // (e.g. user re-entering the tab) bumps the generation; queued
            // extractions check at every step and bail before touching the
            // cam, so the new listing isn't starved by stale thumbnails.
            //
            // We deliberately do NOT hold CamHttpGate.mutex during MMR.
            // MMR's native call ignores coroutine cancellation and can
            // hold a mutex for tens of seconds while listing's HTTP times
            // out the cam-side client registration. Listing has its own
            // -2222 retry, so they coexist via the connection-level
            // semaphore (1 permit) without a global serialisation lock.
            val startGen = CamHttpGate.currentGeneration()
            extractSemaphore.withPermit {
                if (CamHttpGate.currentGeneration() != startGen) {
                    throw RuntimeException("Stale thumbnail request — superseded by a newer Media load")
                }
                extractFrame(httpUrl, cached)
            }
        }

        // Successful extraction → load JPEG from disk. Failed → throw so
        // Coil routes to the AsyncImage `error` slot (video-icon placeholder).
        if (cached.length() == 0L) {
            throw RuntimeException("Thumbnail extraction failed for $httpUrl")
        }
        return SourceResult(
            source     = ImageSource(file = cached.toOkioPath(), fileSystem = FileSystem.SYSTEM),
            mimeType   = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun extractFrame(httpUrl: String, dest: File) {
        // Direct MMR-over-HTTP path. Pin the process to the dashcam network
        // so MMR's native HTTP stack reaches `192.168.0.1`. Wrap in a
        // timeout — without one, MMR can hang for ~30s on a single bad file
        // and starve other extractions in the semaphore queue.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val previous = cm.boundNetworkForProcess
        if (network != null) cm.bindProcessToNetwork(network)
        try {
            withTimeout(MMR_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(httpUrl, hashMapOf<String, String>())
                        val bitmap = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: error("getFrameAtTime returned null")
                        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                        bitmap.recycle()
                    } finally {
                        // mmr.release() can take seconds on G3518 streams; if
                        // the surrounding withTimeout fires during release the
                        // catch below triggers, but the JPG is already saved.
                        // We swallow the exception in the outer catch and keep
                        // the file based on its size.
                        mmr.release()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "extract timeout (${MMR_TIMEOUT_MS}ms): $httpUrl — keeping JPG if any")
        } catch (e: Exception) {
            Log.w(TAG, "extract failed: $httpUrl — ${e.message}")
        } finally {
            cm.bindProcessToNetwork(previous)
        }
        // Single source of truth: either the JPG made it to disk before any
        // exception, in which case we keep it, or it didn't and we clean up.
        if (dest.length() == 0L) {
            dest.delete()
        } else {
            Log.d(TAG, "extracted: $httpUrl → ${dest.length()} B")
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    class Factory(
        private val context: Context,
        private val network: Network?,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SCHEME) return null
            return HiDvrThumbnailFetcher(data, context, network)
        }
    }

    companion object {
        const val SCHEME = "hidvr-thumb"
        private const val TAG = "Trafy.HiDvrThumb"
        private const val CACHE_DIR = "hidvr_thumbs"
        /** Per-extraction hard timeout — bad files can hang MMR for 30+ s. */
        private const val MMR_TIMEOUT_MS = 15_000L

        /**
         * Process-wide cap on concurrent MMR extractions. We keep this at 1 —
         * G3518 can technically handle 2 concurrent streams but with both
         * slots saturated the webserver refuses new connections, breaking
         * any simultaneous control / listing calls.
         */
        private val extractSemaphore = Semaphore(permits = 1)

        /** Builds the custom-scheme URL pointing at the MP4 to thumbnail. */
        fun urlFor(deviceIp: String, path: String): String = "$SCHEME://$deviceIp/$path"
    }
}
