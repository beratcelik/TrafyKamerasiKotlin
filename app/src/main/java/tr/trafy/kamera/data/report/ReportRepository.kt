package tr.trafy.kamera.data.report

import android.content.Context
import android.util.Log
import tr.trafy.kamera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Submits user feedback and flushes the disk queue of pending reports
 * (crashes from previous sessions + feedback that couldn't reach the server).
 *
 * Queue policy (mirrors [ReportHttpClient.Result]): delete the file on
 * 2xx/400/413, keep it and stop the flush on 429/5xx/network error.
 */
class ReportRepository(private val context: Context) {

    companion object {
        private const val TAG = "Trafy.ReportRepo"

        const val REPORTS_URL = "https://trafy.tr/api/app/reports"

        /** At most one flush attempt per this window (per process — resets on relaunch). */
        private const val FLUSH_DEBOUNCE_MS = 15 * 60 * 1000L
        /** Pause between queued uploads so we stay well under the 10/min/IP rate limit. */
        private const val FLUSH_PACING_MS = 400L

        // Flush state is process-wide (companion) so multiple repository
        // instances can never race each other over the same queue files.
        private val flushMutex = Mutex()
        @Volatile private var lastFlushAttemptMs = 0L

        /**
         * Debug-only endpoint override for end-to-end testing against a local
         * server without code edits:
         *   adb reverse tcp:3000 tcp:3000
         *   adb shell setprop debug.trafy.reports_url http://127.0.0.1:3000/api/app/reports
         * Can also be set directly from debug code/tests. Release builds
         * ignore both paths entirely (BuildConfig.DEBUG gate below).
         */
        @Volatile var debugReportsUrlOverride: String? = null
    }

    sealed class SubmitResult {
        data object Sent : SubmitResult()
        data object Queued : SubmitResult()
        data object Error : SubmitResult()
    }

    private val reportsUrl: String
        get() {
            if (BuildConfig.DEBUG) {
                debugReportsUrlOverride?.takeIf { it.isNotBlank() }?.let { return it }
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("getprop", "debug.trafy.reports_url"))
                        .inputStream.bufferedReader().use { it.readText() }.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return REPORTS_URL
        }

    /**
     * User feedback: try to deliver immediately (works when cellular is up
     * even on the dashcam hotspot, thanks to the internet-network binding);
     * on 429/network failure the payload is queued and reported as [SubmitResult.Queued].
     * A permanent server reject (400/413) is surfaced as [SubmitResult.Error]
     * rather than queued — no poison pills on disk.
     */
    suspend fun submitFeedback(
        reportType: String,
        description: String,
        contactEmail: String?,
        includeDiagnostics: Boolean,
    ): SubmitResult = withContext(Dispatchers.IO) {
        val logs = if (includeDiagnostics) DiagnosticsCollector.captureLogcat() else null
        val payload = ReportPayload.feedback(context, reportType, description, contactEmail, logs, includeDiagnostics)
        when (ReportHttpClient.post(context, reportsUrl, payload.toString())) {
            ReportHttpClient.Result.Accepted -> SubmitResult.Sent
            ReportHttpClient.Result.PermanentReject -> {
                Log.w(TAG, "submitFeedback: server rejected payload (not queued)")
                SubmitResult.Error
            }
            ReportHttpClient.Result.RateLimited,
            ReportHttpClient.Result.NetworkError ->
                // Queued only if the file actually hit disk — a failed write
                // must surface as an error, not a false "saved" promise.
                if (ReportQueue.enqueueFeedback(context, payload)) SubmitResult.Queued
                else SubmitResult.Error
        }
    }

    /**
     * Uploads whatever previous sessions left on disk — this is what delivers
     * last session's crash. Safe to call on every launch:
     * - returns immediately when the queue is empty (the common case),
     * - debounced to one attempt per 15 minutes within a process,
     * - Mutex-guarded so concurrent callers can't double-send,
     * - oldest-first, 400ms pacing, stops at the first 429/network error.
     */
    suspend fun flushPending() = withContext(Dispatchers.IO) {
        // Cheap short-circuit before touching debounce state: nothing to send.
        if (ReportQueue.isEmpty(context)) return@withContext
        if (System.currentTimeMillis() - lastFlushAttemptMs < FLUSH_DEBOUNCE_MS) return@withContext
        flushMutex.withLock {
            // Re-check under the lock — a concurrent caller may have just run.
            if (System.currentTimeMillis() - lastFlushAttemptMs < FLUSH_DEBOUNCE_MS) return@withContext
            lastFlushAttemptMs = System.currentTimeMillis()
            val url = reportsUrl
            for (file in ReportQueue.pendingFilesOldestFirst(context)) {
                val body = runCatching { file.readText() }.getOrNull()
                if (body.isNullOrBlank()) {
                    // Unreadable/empty entry — junk, drop it.
                    runCatching { file.delete() }
                    continue
                }
                when (ReportHttpClient.post(context, url, body)) {
                    ReportHttpClient.Result.Accepted,
                    ReportHttpClient.Result.PermanentReject -> {
                        Log.i(TAG, "flushPending: delivered ${file.name}")
                        runCatching { file.delete() }
                    }
                    ReportHttpClient.Result.RateLimited,
                    ReportHttpClient.Result.NetworkError -> {
                        // Keep the file; the next launch (or next debounce
                        // window) retries from here.
                        Log.d(TAG, "flushPending: stopping at ${file.name} (server busy / no internet)")
                        return@withContext
                    }
                }
                delay(FLUSH_PACING_MS)
            }
        }
    }
}