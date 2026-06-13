package com.example.trafykamerasikotlin.data.sensors

import android.app.Application
import android.content.Context
import android.util.Log
import android.net.wifi.WifiManager
import com.example.trafykamerasikotlin.data.gps.EeasytechGpsPushClient
import com.example.trafykamerasikotlin.data.sensors.GpsFix
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
    @Volatile private var camPushClient: EeasytechGpsPushClient? = null
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
                        writePhone(app, snapshot)
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

        // Easytech cam push GPS over TCP :5000 is intentionally NOT auto-
        // started. On Trafy Dos Pro the cam reboots when we open that
        // socket without the OEM-specific handshake — we'd take the cam
        // down on every Settings entry. The parser and client class are
        // kept (see [EeasytechGpsPushClient]) so a future reverse-engineering
        // session can re-enable it once the handshake is known.
    }

    /**
     * True when the current Wi-Fi network looks like an Easytech dashcam
     * (gateway 192.168.169.x). We avoid hammering 192.168.169.1:5000
     * with retries when we're not actually attached to such a cam.
     */
    private fun isOnEasytechSubnet(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return false
        // Android encodes IPv4 as int in LITTLE-endian order — `192.168.169.x`
        // packs to the same first three bytes regardless, so we can compare
        // the low 24 bits as `(b0)|(b1<<8)|(b2<<16)`.
        val b0 = ip and 0xFF
        val b1 = (ip ushr 8) and 0xFF
        val b2 = (ip ushr 16) and 0xFF
        return b0 == 192 && b1 == 168 && b2 == 169
    }

    /** Stops the writer, releases the sensor provider, closes today's file. */
    fun stop() {
        runJob?.cancel()
        runJob = null
        sensorProvider?.stop()
        sensorProvider = null
        camPushClient?.stop()
        camPushClient = null
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

    private fun writePhone(context: Context, snapshot: LiveSensorData) {
        val nowMs = System.currentTimeMillis()
        // Skip when neither lat/lon nor any sensor reading is available
        // yet — keeps the file from being littered with empty objects
        // during GPS cold-lock.
        if (snapshot.speedKmh == null && snapshot.altitudeM == null &&
            snapshot.headingDeg == null && snapshot.accelerationG == null) return

        val writer = writerForToday(context, nowMs)
        try {
            writer.write(toNdjsonPhone(nowMs, snapshot))
            writer.newLine()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    private fun writeCamFix(context: Context, fix: GpsFix) {
        val writer = writerForToday(context, fix.tEpochMs)
        try {
            writer.write(toNdjsonCam(fix))
            writer.newLine()
        } catch (t: Throwable) {
            Log.w(TAG, "cam write failed: ${t.message}")
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
     *
     * `src` distinguishes phone-derived rows from cam-push rows — the
     * read side ([GpsLogStore]) uses it so cam rows win when both sources
     * have a fix for the same second.
     */
    private fun toNdjsonPhone(tMs: Long, s: LiveSensorData): String {
        val sb = StringBuilder(160)
        sb.append('{')
        sb.append("\"t\":").append(tMs)
        sb.append(",\"src\":\"phone\"")
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

    private fun toNdjsonCam(f: GpsFix): String {
        val sb = StringBuilder(220)
        sb.append('{')
        sb.append("\"t\":").append(f.tEpochMs)
        sb.append(",\"src\":\"cam\"")
        f.lat?.let       { sb.append(",\"lat\":").append(String.format(Locale.US, "%.6f", it)) }
        f.lon?.let       { sb.append(",\"lon\":").append(String.format(Locale.US, "%.6f", it)) }
        f.speedMs?.let   { sb.append(",\"spd\":").append(String.format(Locale.US, "%.2f", it)) }
        f.altitudeM?.let { sb.append(",\"alt\":").append(it) }
        f.headingDeg?.let{ sb.append(",\"hdg\":").append(String.format(Locale.US, "%.1f", it)) }
        f.accelG?.let    { sb.append(",\"g\":").append(String.format(Locale.US, "%.3f", it)) }
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
