package tr.trafy.kamera.data.report

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Disk queue for reports that couldn't be uploaded yet — the phone is
 * usually on the dashcam's no-internet hotspot, and crash reports must
 * never upload from the dying process anyway. Files live under
 * `filesDir/pending_reports/` (excluded from Auto Backup — log excerpts
 * stay device-local) and are flushed by [ReportRepository.flushPending]
 * on the next launch with internet.
 *
 * Disk hygiene mirrors `GpsLogger`: atomic `.tmp`+rename writes, a
 * [MAX_FILES] cap evicting oldest, and a [RETENTION_DAYS] sweep on startup.
 *
 * Crash-loop protection: ONE file per crash fingerprint
 * (`crash-<fingerprint>.json`) whose `occurrenceCount` is bumped on every
 * re-crash — a boot-loop can never flood the queue or the server.
 */
object ReportQueue {
    private const val TAG = "Trafy.ReportQueue"
    private const val DIR_NAME = "pending_reports"
    private const val MAX_FILES = 25
    /** Files older than this many days are dropped by [sweep]. */
    private const val RETENTION_DAYS = 30

    private fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME)

    /**
     * Queues a crash payload. If a file for the same fingerprint already
     * exists, the fresh payload replaces it (newest stack/logs win) but
     * inherits the existing `clientReportId` — so the server's idempotency
     * check still collapses retried uploads — and its `occurrenceCount` + 1.
     */
    fun enqueueCrash(context: Context, payload: JSONObject, fingerprint: String) {
        val d = dir(context).apply { mkdirs() }
        val file = File(d, "crash-$fingerprint.json")
        if (file.exists()) {
            runCatching {
                val existing = JSONObject(file.readText())
                existing.optString("clientReportId").takeIf { it.isNotBlank() }
                    ?.let { payload.put("clientReportId", it) }
                payload.put("occurrenceCount", existing.optInt("occurrenceCount", 1) + 1)
            }
        }
        writeAtomic(file, payload.toString())
        enforceCap(d)
    }

    /**
     * Queues a user-feedback payload under its own clientReportId.
     * Returns false when the write failed (disk full / IO error) so the UI
     * can show an error instead of a false "saved" promise.
     */
    fun enqueueFeedback(context: Context, payload: JSONObject): Boolean {
        val d = dir(context).apply { mkdirs() }
        val id = payload.optString("clientReportId").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val ok = writeAtomic(File(d, "feedback-$id.json"), payload.toString())
        enforceCap(d)
        return ok
    }

    /** Upload order: oldest first, so a burst of new reports can't starve old ones. */
    fun pendingFilesOldestFirst(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    fun isEmpty(context: Context): Boolean = pendingFilesOldestFirst(context).isEmpty()

    /**
     * Startup hygiene: drops orphaned `.tmp` files (crash mid-write), entries
     * past retention, and anything over the file cap. Cheap — one directory
     * listing.
     */
    fun sweep(context: Context) {
        val d = dir(context)
        if (!d.exists()) return
        val cutoffMs = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        d.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || f.lastModified() < cutoffMs) {
                runCatching { f.delete() }
            }
        }
        enforceCap(d)
    }

    private fun enforceCap(d: File) {
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Atomic write: content lands in `<name>.json.tmp` first, then renames
     * over the target — a crash mid-write leaves at worst an orphan `.tmp`
     * that [sweep] removes, never a truncated `.json` the flusher would POST.
     */
    private fun writeAtomic(target: File, content: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // Some filesystems refuse rename-over-existing; retry after delete.
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "writeAtomic failed for ${target.name}: ${t.message}")
            runCatching { tmp.delete() }
            false
        }
    }
}