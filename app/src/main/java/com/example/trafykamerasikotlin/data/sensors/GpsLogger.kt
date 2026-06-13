package com.example.trafykamerasikotlin.data.sensors

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.settings.GpsLoggingPreferences
import com.example.trafykamerasikotlin.data.wifi.DashcamWifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Process-wide singleton that pipes the phone's `LiveSensorProvider` stream
 * into an append-only NDJSON file on internal storage.
 *
 * **Gating** (all must be true):
 *   1. User opted in via [GpsLoggingPreferences] (default OFF).
 *   2. Phone is currently connected to a known dashcam Wi-Fi (proxy for
 *      "user is in the car"). Prevents logging the user walking around
 *      the house with the app open.
 *   3. Caller has invoked [start] (typically from MainActivity onResume).
 *
 * **Lifecycle**: any of (a) preference toggle off, (b) Wi-Fi disconnect,
 * (c) [stop] call → logger pauses, writer closes. Re-checking happens
 * every [GATE_RECHECK_MS] inside the writer loop, so the cost of a flag
 * change is at most one tick.
 *
 * **Storage**: one NDJSON file per UTC date at
 * `context.filesDir/gps_logs/YYYY-MM-DD.ndjson`. Append-only — a half-
 * written tail line on crash is dropped at parse time, no special
 * recovery needed.
 *
 * **Cadence**: throttled to ~1 Hz via the upstream flow's natural rate +
 * a [kotlinx.coroutines.flow.sample] window. Generous margin: typical
 * `LocationManager.GPS_PROVIDER` already emits ~1 Hz.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
object GpsLogger {
    private const val TAG = "Trafy.GpsLogger"
    private const val LOG_DIR        = "gps_logs"
    private const val GATE_RECHECK_MS = 5_000L
    private const val FLUSH_EVERY_MS  = 5_000L
    /** Files older than this many days are swept on the next [start] call. */
    private const val RETENTION_DAYS  = 30

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var runJob: Job? = null
    @Volatile private var sensorProvider: LiveSensorProvider? = null
    @Volatile private var currentWriter: BufferedWriter? = null
    @Volatile private var currentFileDate: String = ""

    /**
     * Idempotent. If already running, no-op. If the preference is OFF or
     * the phone isn't on a dashcam Wi-Fi, returns without subscribing —
     * gating is re-checked inside the writer loop, so just call this on
     * every foreground transition.
     */
    fun startIfEnabled(context: Context) {
        val app = context.applicationContext
        if (!GpsLoggingPreferences.get(app)) return
        if (runJob?.isActive == true) return
        if (DashcamWifiManager(app as Application).getCurrentDashcamSsid() == null) {
            Log.d(TAG, "skip start — not on a dashcam Wi-Fi")
            return
        }

        sweepOldFiles(app)

        val provider = LiveSensorProvider(app).also {
            sensorProvider = it
            it.start()
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        runJob = newScope.launch {
            try {
                // Throttle: only one sample per second written, regardless of
                // how often the location stream emits.
                provider.data
                    .sample(1_000L)
                    .collect { snapshot ->
                        // Re-check gates every emit — cheap, lets us bail out
                        // promptly when prefs flip or Wi-Fi drops.
                        if (!GpsLoggingPreferences.get(app)) {
                            stop()
                            return@collect
                        }
                        if (DashcamWifiManager(app).getCurrentDashcamSsid() == null) {
                            stop()
                            return@collect
                        }
                        write(app, snapshot)
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "writer loop ended: ${t.message}")
            } finally {
                runCatching { currentWriter?.close() }
                currentWriter = null
            }
        }
        // Periodic flush so a crash doesn't lose the last few seconds.
        newScope.launch {
            while (isActive) {
                delay(FLUSH_EVERY_MS)
                runCatching { currentWriter?.flush() }
            }
        }
    }

    /** Stops the writer, releases the sensor provider, closes today's file. */
    fun stop() {
        runJob?.cancel()
        runJob = null
        sensorProvider?.stop()
        sensorProvider = null
        runCatching { currentWriter?.flush() }
        runCatching { currentWriter?.close() }
        currentWriter = null
        currentFileDate = ""
        scope?.cancel()
        scope = null
    }

    /** Used by the Settings "delete all" button. */
    fun deleteAllLogs(context: Context) {
        val dir = File(context.applicationContext.filesDir, LOG_DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun write(context: Context, snapshot: LiveSensorData) {
        val nowMs = System.currentTimeMillis()
        // Skip when neither lat/lon nor any sensor reading is available
        // yet — keeps the file from being littered with empty objects
        // during GPS cold-lock.
        if (snapshot.speedKmh == null && snapshot.altitudeM == null &&
            snapshot.headingDeg == null && snapshot.accelerationG == null) return

        val writer = writerForToday(context, nowMs)
        try {
            writer.write(toNdjson(nowMs, snapshot))
            writer.newLine()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    private fun writerForToday(context: Context, nowMs: Long): BufferedWriter {
        val date = utcDateString(nowMs)
        currentWriter?.let { existing ->
            if (currentFileDate == date) return existing
            runCatching { existing.close() }
        }
        val dir = File(context.applicationContext.filesDir, LOG_DIR).apply { mkdirs() }
        val file = File(dir, "$date.ndjson")
        return BufferedWriter(FileWriter(file, /* append = */ true)).also {
            currentWriter = it
            currentFileDate = date
            Log.i(TAG, "opened $file (append mode)")
        }
    }

    /**
     * Plain hand-rolled JSON — avoids pulling kotlinx.serialization for one
     * small object. Numbers without decimals stay compact; nullable fields
     * are omitted rather than written as "null" to save bytes.
     */
    private fun toNdjson(tMs: Long, s: LiveSensorData): String {
        val sb = StringBuilder(160)
        sb.append('{')
        sb.append("\"t\":").append(tMs)
        // LiveSensorData speed is already km/h — store as m/s for consistency
        // with future raw-Location-based sources, so callers can convert
        // uniformly at read time.
        s.speedKmh?.let { sb.append(",\"spd\":").append(String.format(Locale.US, "%.2f", it / 3.6f)) }
        s.altitudeM?.let { sb.append(",\"alt\":").append(it) }
        s.headingDeg?.let { sb.append(",\"hdg\":").append(String.format(Locale.US, "%.1f", it)) }
        s.accelerationG?.let { sb.append(",\"g\":").append(String.format(Locale.US, "%.3f", it)) }
        sb.append('}')
        return sb.toString()
    }

    private fun utcDateString(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(epochMs))
    }

    private fun sweepOldFiles(context: Context) {
        val dir = File(context.applicationContext.filesDir, LOG_DIR)
        if (!dir.exists()) return
        val cutoffMs = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoffMs) {
                runCatching { f.delete() }
            }
        }
    }
}
