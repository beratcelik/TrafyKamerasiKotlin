package com.example.trafykamerasikotlin.data.overlay

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Disk-backed store of per-video overlay sidecars, used by the in-app
 * playback overlays as a deterministic, zero-inference replacement for the
 * live `LiveVisionPipeline` re-inference loop.
 *
 * Layout: one NDJSON file per video at
 *   `context.filesDir/overlay_sidecars/<videoBasename>.ndjson`
 * Each line is a [SidecarEntry] serialised via [SidecarEntry.toNdjson]. The
 * writer opens fresh each scan and the scanner buffers entries in memory,
 * writing them all once after plate-text finalisation (a streaming append
 * would expose half-finalised text — the post-scan rewrite is what makes
 * the "plate locked from frame 0" promise hold). A crash mid-scan leaves
 * the file empty or absent; the reader treats both as "no sidecar."
 *
 * Read API: callers load a sidecar once on player setup via [load], then
 * call [Reader.sceneAt] every position-poll tick. `sceneAt` is O(log n)
 * binary search — same shape as
 * [com.example.trafykamerasikotlin.data.sensors.GpsLogStore]'s lookup.
 */
object OverlaySidecarStore {

    private const val TAG = "Trafy.OverlaySidecar"
    private const val DIR_NAME = "overlay_sidecars"

    /** Path the writer / reader / existence check all agree on. */
    fun sidecarFileFor(context: Context, videoBasename: String): File {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        return File(dir, "$videoBasename.ndjson")
    }

    fun exists(context: Context, videoBasename: String): Boolean =
        sidecarFileFor(context, videoBasename).let { it.exists() && it.length() > 0L }

    /**
     * Returns a [Reader] backed by an in-memory sorted array, or null when
     * the sidecar doesn't exist / is empty / contains only malformed lines.
     */
    suspend fun load(context: Context, videoBasename: String): Reader? = withContext(Dispatchers.IO) {
        val file = sidecarFileFor(context, videoBasename)
        if (!file.exists() || file.length() == 0L) return@withContext null
        val entries = ArrayList<SidecarEntry>(256)
        try {
            BufferedReader(FileReader(file)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val entry = SidecarEntry.fromNdjson(line) ?: continue
                    entries += entry
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load failed on ${file.name}: ${t.message}")
            return@withContext null
        }
        if (entries.isEmpty()) return@withContext null
        entries.sortBy { it.presentationTimeUs }
        Log.i(TAG, "loaded ${entries.size} entries from ${file.name}")
        Reader(entries)
    }

    fun deleteFor(context: Context, videoBasename: String) {
        runCatching { sidecarFileFor(context, videoBasename).delete() }
    }

    /**
     * Writer wrapper around the sidecar file. Always opens fresh
     * (truncating any prior content), so it's caller-safe to overwrite a
     * stale sidecar without an explicit delete step. Caller drives
     * [append] per finalised entry; [close] flushes.
     */
    class Writer(private val file: File) : AutoCloseable {
        private val writer: BufferedWriter = run {
            file.parentFile?.mkdirs()
            BufferedWriter(FileWriter(file, /* append = */ false))
        }

        fun append(entry: SidecarEntry) {
            writer.write(entry.toNdjson())
            writer.newLine()
        }

        override fun close() {
            runCatching { writer.flush() }
            runCatching { writer.close() }
        }
    }

    fun openWriter(context: Context, videoBasename: String): Writer =
        Writer(sidecarFileFor(context, videoBasename))

    /**
     * In-memory lookup. Public so the playback overlay can hold a reference
     * for the lifetime of one player session.
     */
    class Reader internal constructor(internal val entries: List<SidecarEntry>) {

        /** Time of the last entry (for "fall back to last" sentinel logic). */
        val lastPresentationTimeUs: Long
            get() = if (entries.isEmpty()) 0L else entries.last().presentationTimeUs

        /**
         * Closest entry at or before [tEpochUs] — the renderer wants the
         * detections from the most-recently completed inference frame so
         * boxes don't jitter back-and-forth as the playhead bounces between
         * frames. Inclusive upper bound, falls forward to the next entry only
         * when nothing came before. Returns null when entries is empty (the
         * caller guarantees that doesn't happen, but be defensive).
         */
        fun sceneAt(tPresentationUs: Long, maxLookbackUs: Long = DEFAULT_MAX_LOOKBACK_US): TrackedScene? {
            if (entries.isEmpty()) return null
            // Linear interpolation isn't needed — boxes are stale-OK between
            // inference samples, the renderer already paints them on every
            // composition. We want "current or most-recent".
            val idx = lowerBound(tPresentationUs)
            val candidate = when {
                idx < entries.size && entries[idx].presentationTimeUs == tPresentationUs -> entries[idx]
                idx > 0 -> entries[idx - 1]
                else    -> entries[0]
            }
            // If the playhead is much past the last sample (the scan ran short),
            // hide the overlay to avoid showing locked plates over content that
            // wasn't actually scanned.
            val gap = tPresentationUs - candidate.presentationTimeUs
            if (gap > maxLookbackUs) return null
            return candidate.toTrackedScene()
        }

        /** Standard binary search returning the insertion point. */
        private fun lowerBound(target: Long): Int {
            var lo = 0
            var hi = entries.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (entries[mid].presentationTimeUs < target) lo = mid + 1 else hi = mid
            }
            return lo
        }

        companion object {
            /**
             * Samples spaced more than this far from the playhead are
             * treated as stale and the overlay hides. The scanner emits
             * at ~10 Hz inference cadence (1-in-3 sampling at 30 fps) and
             * skips frames with no detections at all, so a healthy quiet
             * stretch can have ~half-second gaps; 1 s gives that room
             * while still nulling out cleanly past an early-aborted scan.
             */
            const val DEFAULT_MAX_LOOKBACK_US: Long = 1_000_000L
        }
    }
}
