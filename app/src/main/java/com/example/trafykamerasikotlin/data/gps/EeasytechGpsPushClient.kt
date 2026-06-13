package com.example.trafykamerasikotlin.data.gps

import android.net.Network
import android.util.Log
import com.example.trafykamerasikotlin.data.sensors.GpsFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * TCP client that subscribes to an Easytech (Mstar-family) dashcam's
 * push-socket on 192.168.169.1:5000 and parses GPS frames out of the
 * newline-delimited JSON stream.
 *
 * **Why TCP and not HTTP**: the Easytech firmware's HTTP CGI surface
 * (the `/app/...` tree) deliberately omits GPS — the cam pushes GPS over a
 * dedicated socket instead. Confirmed against the decompiled vendor app
 * at [golook-jadx/.../EeasytechProtocol.java:1614-1652] and
 * [MstarDevUtil.socketDataToBean]; the wire format we parse here mirrors
 * that reference parser exactly.
 *
 * **Lifecycle**: idempotent [start]/[stop]. On a disconnect we retry with
 * a back-off so a cam reboot or a transient Wi-Fi flap doesn't end the
 * subscription forever. The retry stops when the caller cancels or when
 * the cam closes EOF cleanly.
 *
 * **Wire format** (per JSON line):
 * ```
 * {"msgid":"gps","info":{"value":"YYYY/MM/DD HH:mm:ss N:<lat> E:<lon>
 *                                  <speed> km/h <heading> <altM> <sats>
 *                                  x:<gx> y:<gy> z:<gz>"}}
 * ```
 *   - latitude prefix is N/S (signed)
 *   - longitude prefix is E/W (signed)
 *   - speed unit is the literal token at split[5] (usually "km/h")
 *   - heading is degrees clockwise from north
 *   - altitude is in metres, sometimes float
 *   - gsensor x/y/z are floats in g (accelerometer)
 *
 * Lines that don't match GPS schema (`adas_result`, `staellite` SNR,
 * state events, etc.) are quietly dropped.
 */
class EeasytechGpsPushClient(
    private val deviceIp: String = "192.168.169.1",
    private val port: Int = 5000,
    /** Optional dashcam-bound network so the socket goes through the right interface. */
    private val network: Network? = null,
) {

    companion object {
        private const val TAG = "Trafy.EasytechGpsTcp"
        private const val INITIAL_BACKOFF_MS  = 1_000L
        private const val MAX_BACKOFF_MS      = 30_000L
        private const val CONNECT_TIMEOUT_MS  = 4_000
        private const val READ_TIMEOUT_MS     = 60_000   // cam sends every ~1 s, 60 s of silence = dead
    }

    private val _fixes = MutableSharedFlow<GpsFix>(extraBufferCapacity = 64)
    /** Emits one fix per parsed `msgid:"gps"` JSON line. */
    val fixes: SharedFlow<GpsFix> = _fixes.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** Idempotent. Starts the connect loop on a background coroutine. */
    fun start() {
        if (job?.isActive == true) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        job = newScope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                val cleanExit = runOnce()
                if (cleanExit) {
                    Log.i(TAG, "cam closed cleanly — ending subscription")
                    return@launch
                }
                Log.i(TAG, "reconnecting after ${backoff}ms")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
    }

    /**
     * One connect → read → close cycle. Returns true when the cam closes
     * the socket on its own (clean EOF — we shouldn't reconnect), false
     * for any error (we should back off + retry).
     */
    private suspend fun runOnce(): Boolean {
        val sock = try {
            (network?.socketFactory ?: javax.net.SocketFactory.getDefault()).createSocket().apply {
                tcpNoDelay = true
                soTimeout  = READ_TIMEOUT_MS
                connect(InetSocketAddress(deviceIp, port), CONNECT_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        Log.i(TAG, "connected to $deviceIp:$port")
        try {
            BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = try { reader.readLine() } catch (t: Throwable) {
                        Log.w(TAG, "read failed: ${t.javaClass.simpleName}: ${t.message}"); return false
                    } ?: run {
                        Log.i(TAG, "EOF from cam")
                        return true
                    }
                    handleLine(line)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            return true   // unreachable; keeps the compiler happy
        } finally {
            runCatching { sock.close() }
        }
    }

    private suspend fun handleLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        if (!line.contains("\"msgid\"")) return
        if (!line.contains("\"gps\"")) return       // only the gps msgid for now
        val fix = parseGpsLine(line) ?: return
        _fixes.emit(fix)
        Log.i(TAG, "cam fix: t=${fix.tEpochMs} lat=${fix.lat} lon=${fix.lon} " +
            "spd=${fix.speedMs?.let { "%.2fm/s".format(it) } ?: "—"} " +
            "alt=${fix.altitudeM ?: "—"} hdg=${fix.headingDeg ?: "—"} g=${fix.accelG ?: "—"}")
    }

    /**
     * Parses one full JSON line into a [GpsFix]. Mirrors
     * `MstarDevUtil.socketDataToBean` exactly — same split rules, same
     * tolerance for missing trailing fields, same null-on-(0,0) policy.
     */
    fun parseGpsLine(line: String): GpsFix? {
        val obj = try { JSONObject(line) } catch (_: Throwable) { return null }
        if (obj.optString("msgid") != "gps") return null
        val value = obj.optJSONObject("info")?.optString("value") ?: return null
        return parseValueString(value)
    }

    /**
     * The `value` field is a single space-separated string. We follow the
     * exact decomposition the OEM parser uses (see [MstarDevUtil] in the
     * jadx tree). Public so unit tests can exercise weird payloads
     * without spinning up a socket.
     */
    fun parseValueString(raw: String): GpsFix? {
        val withoutPos = raw.replace("Pos=", "").replace("  ", " ").trim()
        val parts = withoutPos.split(" ")
        if (parts.size < 4) return null
        // Time: "YYYY/MM/DD HH:mm:ss". Parsed as UTC — the cam usually
        // ships its clock in local time, but for HUD alignment any
        // monotonic clock works as long as offline burn-in does the same.
        val tMs = try {
            DATE_FMT.parse("${parts[0]} ${parts[1]}")?.time
        } catch (_: Throwable) { null } ?: System.currentTimeMillis()

        val lat = parsePrefixedDouble(parts[2], 'N', 'S')
        val lon = parsePrefixedDouble(parts[3], 'E', 'W')
        if (lat == null || lon == null) return null
        if (lat == 0.0 && lon == 0.0) return null  // unfixed sample

        val speedMs = parts.getOrNull(4)?.toFloatOrNull()?.let { v ->
            val unit = parts.getOrNull(5) ?: "km/h"
            when {
                unit.equals("km/h", ignoreCase = true) -> v / 3.6f
                unit.equals("mph",  ignoreCase = true) -> v * 0.44704f
                unit.equals("m/s",  ignoreCase = true) -> v
                else                                   -> v / 3.6f
            }
        }
        val heading  = parts.getOrNull(6)?.toFloatOrNull()
        val altitude = parts.getOrNull(7)?.toFloatOrNull()?.toInt()
        // satellites = parts[8] — not in GpsFix today; logged via the fix emit.
        val gx = parts.getOrNull(9)?.let  { parsePrefixedDouble(it, 'x', 'X') }
        val gy = parts.getOrNull(10)?.let { parsePrefixedDouble(it, 'y', 'Y') }
        val gz = parts.getOrNull(11)?.let { parsePrefixedDouble(it, 'z', 'Z') }
        // Synthesize a longitudinal-ish g from the dominant horizontal
        // axis, in g units. Phone-side LiveSensorProvider derives this
        // from GPS speed deltas; cam side hands it directly.
        val accelG: Float? = listOfNotNull(gx, gy)
            .maxByOrNull { kotlin.math.abs(it) }?.toFloat()

        return GpsFix(
            tEpochMs   = tMs,
            lat        = lat,
            lon        = lon,
            speedMs    = speedMs,
            altitudeM  = altitude,
            headingDeg = heading,
            accelG     = accelG,
        )
    }

    private fun parsePrefixedDouble(token: String, positive: Char, negative: Char): Double? {
        if (token.length < 3) return null
        val colon = token.indexOf(':')
        if (colon <= 0) return token.toDoubleOrNull()
        val prefix = token[0]
        val numStr = token.substring(colon + 1)
        val value = numStr.toDoubleOrNull() ?: return null
        return when (prefix) {
            positive -> value
            negative -> -value
            else     -> value     // unknown prefix — preserve magnitude
        }
    }

    private val DATE_FMT = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
}
