package tr.trafy.kamera.data.report

import android.os.Process
import java.security.MessageDigest

/**
 * Small diagnostic helpers shared by the crash handler and the feedback form.
 * Everything here must be safe to call from a dying process: bounded output,
 * a hard time guard on the logcat exec, and null (never an exception) on any
 * failure.
 */
object DiagnosticsCollector {
    private const val APP_PACKAGE_PREFIX = "tr.trafy.kamera"
    private const val LOGCAT_MAX_LINES   = 400
    private const val LOGCAT_GUARD_MS    = 2_000L
    private const val FINGERPRINT_FRAMES = 5

    /**
     * Recent log excerpt for the app's own process. `logcat -d --pid` needs
     * no READ_LOGS permission for our own pid on API 24+ (minSdk). Bounded on
     * three axes: 400 lines (`-t`), [ReportPayload.MAX_LOGS_CHARS] characters,
     * and a ~2s wall-clock guard so a wedged logcat can't stall the caller.
     * Never attach GPS logs here — privacy rule.
     */
    fun captureLogcat(): String? = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "--pid=${Process.myPid()}", "-t", "$LOGCAT_MAX_LINES", "-v", "time")
        )
        // StringBuffer (not Builder): the reader thread may still be appending
        // when the join() guard expires and we snapshot the partial output.
        val sb = StringBuffer()
        // Read on a helper thread so the join() below enforces the time guard
        // even if the pipe blocks; whatever was captured by then is returned.
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (sb.length + line.length + 1 > ReportPayload.MAX_LOGS_CHARS) return@useLines
                        sb.append(line).append('\n')
                    }
                }
            } catch (_: Throwable) {
                // Partial output is fine; the guard below decides when to stop.
            }
        }
        reader.start()
        reader.join(LOGCAT_GUARD_MS)
        runCatching { process.destroy() }
        sb.toString().takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /**
     * Stable crash-group identity:
     * `sha256("android|" + rootExceptionClass + "|" + top-5 app-package frames as "class.method")`.
     * Deliberately **no line numbers** — they churn on every release and would
     * re-trigger "new crash" admin emails for the same underlying bug.
     */
    fun computeFingerprint(throwable: Throwable): String {
        val root = rootCause(throwable)
        val appFrames = root.stackTrace
            .filter { it.className.startsWith(APP_PACKAGE_PREFIX) }
            .take(FINGERPRINT_FRAMES)
        // Framework-only crashes (no app frames) fall back to the top of the
        // raw trace — otherwise every such crash of one exception class would
        // collapse into a single group and overwrite each other in the queue.
        val frames = appFrames.ifEmpty { root.stackTrace.take(3) }
            .joinToString("|") { "${it.className}.${it.methodName}" }
        return sha256Hex("android|${root.javaClass.name}|$frames")
    }

    /** Full chained stack trace, capped so the payload stays under the body limit. */
    fun throwableToString(throwable: Throwable): String =
        runCatching { throwable.stackTraceToString() }
            .getOrDefault(throwable.javaClass.name)
            .take(ReportPayload.MAX_STACK_CHARS)

    /** One-line human title for the admin list view, e.g. "Crash: NullPointerException — …". */
    fun crashTitle(throwable: Throwable): String {
        val root = rootCause(throwable)
        val message = root.message?.take(120)?.takeIf { it.isNotBlank() }
        return if (message != null) "Crash: ${root.javaClass.simpleName} — $message"
               else "Crash: ${root.javaClass.simpleName}"
    }

    private fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        // Depth cap guards against pathological circular cause chains.
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 20) {
            current = current.cause!!
            depth++
        }
        return current
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}