package tr.trafy.kamera.data.report

import android.content.Context
import android.util.Log
import tr.trafy.kamera.data.settings.CrashReportingPreferences

/**
 * Uncaught-exception hook installed by `TrafyApplication`. Captures the
 * crash to the disk queue and then — **always**, via `finally` — chains to
 * the previous default handler so the OS crash flow (crash dialog, process
 * kill, restart semantics) stays exactly as it was.
 *
 * Deliberately does NOT upload here: the process is dying and the phone is
 * probably on the dashcam's no-internet hotspot anyway. The queued file is
 * flushed on the next launch by [ReportRepository.flushPending].
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (CrashReportingPreferences.get(context)) {
                val fingerprint = DiagnosticsCollector.computeFingerprint(throwable)
                val logs = DiagnosticsCollector.captureLogcat()
                val payload = ReportPayload.crash(context, throwable, fingerprint, logs)
                ReportQueue.enqueueCrash(context, payload, fingerprint)
                Log.i(TAG, "crash queued (fingerprint=${fingerprint.take(12)}…)")
            }
        } catch (t: Throwable) {
            // The reporter must never mask the original crash.
            Log.w(TAG, "crash capture failed: ${t.message}")
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "Trafy.CrashHandler"
    }
}