package com.example.trafykamerasikotlin.data.gps

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import com.example.trafykamerasikotlin.data.sensors.GpsFix
import com.example.trafykamerasikotlin.data.sensors.GpsTrack
import com.example.trafykamerasikotlin.data.sensors.IndexedGpsTrack
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HiSilicon G3518 / HI3516CV610 (Trafy Uno Pro / Trafy Dos).
 *
 * The HiDvr firmware writes a per-clip GPS sidecar to the SD card at
 * `/sd//GPSdata/<basename>.TXT` (mirror: `/sd//ADASdata/<basename>.TXT`
 * for ADAS events) — pattern documented in decompiled
 * `HiDvrProtocol.java:1426-1430` of the OEM app we have on hand. Some
 * variants ship without GPS hardware; for those the sidecar 404s and we
 * return null cleanly.
 *
 * Format: not yet confirmed against a real device, but the OEM strings
 * point to plain NMEA — almost certainly `$GPRMC` (lat, lon, speed,
 * heading, UTC timestamp) optionally paired with `$GPGGA` (altitude).
 * The parser tries both and stays tolerant of unknown sentences so a
 * future firmware variant adding `$GLRMC` or `$GNRMC` doesn't break us.
 *
 * The `.TXT` extension is `.TXT` (uppercase) per OEM code; we also try
 * `.txt` defensively in case some firmware varies.
 */
object HiDvrCamGpsProvider : CamGpsProvider {

    private const val TAG = "Trafy.HiDvrCamGps"
    /** Filename grammar in the sidecar URL — HiDvr serves under `/sd//GPSdata/`. */
    private const val SIDECAR_DIR_RAW = "/sd//GPSdata/"

    override suspend fun trackFor(
        file: MediaFile, deviceIp: String, context: Context,
    ): GpsTrack? {
        val base = file.name.substringBeforeLast('.', file.name)
        // Try both common extensions; HiDvr code uses .TXT but we've been
        // burned by case-only variations on other firmwares.
        val candidates = listOf("$base.TXT", "$base.txt")
        for (sidecar in candidates) {
            val url = "http://$deviceIp$SIDECAR_DIR_RAW$sidecar"
            val body = DashcamHttpClient.get(url) ?: continue
            if (body.isBlank()) continue
            return parseNmea(body, file.name) ?: continue
        }
        Log.d(TAG, "no GPS sidecar for ${file.name}")
        return null
    }

    /**
     * Public bridge for sibling providers (e.g. [EeasytechCamGpsProvider])
     * that fetch the body themselves but want to reuse the NMEA parser.
     */
    internal fun parseNmeaPublic(body: String, srcName: String): GpsTrack? = parseNmea(body, srcName)

    /**
     * Parses an NMEA-ish body into a [GpsTrack]. Tolerant of CR/LF mixes,
     * unknown talker prefixes (GP, GN, GL — they all use the same RMC/GGA
     * sentence layout), and lines that aren't NMEA at all (skipped).
     *
     * Returns null when no usable fix was extracted — caller treats that
     * the same as a 404.
     */
    private fun parseNmea(body: String, srcName: String): GpsTrack? {
        // Carry-over date from any RMC we see: GGA only has time-of-day, so
        // we anchor it to the RMC's date when interleaved.
        var rmcDateCalendar: Calendar? = null
        val times  = ArrayList<Long>(128)
        val fixes  = ArrayList<GpsFix>(128)

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.length < 7) return@forEach
            if (line[0] != '$') return@forEach
            val payload = line.substring(1).substringBefore('*')
            val parts = payload.split(',')
            if (parts.size < 2) return@forEach
            val talker = parts[0]
            when {
                talker.endsWith("RMC") -> parseRmc(parts)?.let { (tMs, fix, cal) ->
                    rmcDateCalendar = cal
                    times += tMs
                    fixes += fix
                }
                talker.endsWith("GGA") && rmcDateCalendar != null ->
                    parseGgaAlt(parts, rmcDateCalendar!!)?.let { (tMs, alt) ->
                        // Merge altitude into the closest preceding fix (same
                        // second) instead of emitting a duplicate row.
                        val idx = times.indexOfLast { kotlin.math.abs(it - tMs) <= 1_000L }
                        if (idx >= 0) {
                            fixes[idx] = fixes[idx].copy(altitudeM = alt)
                        }
                    }
            }
        }

        if (times.isEmpty()) {
            Log.w(TAG, "no RMC sentences parsed in sidecar for $srcName")
            return null
        }

        // Sort by time defensively — most sidecars are already chronological.
        val sortedIdx = times.indices.sortedBy { times[it] }
        val sortedTimes = LongArray(sortedIdx.size) { times[sortedIdx[it]] }
        val sortedFixes = Array(sortedIdx.size) { fixes[sortedIdx[it]] }
        return IndexedGpsTrack(
            times          = sortedTimes,
            fixes          = sortedFixes,
            sourceLabel    = "kameradan",
            windowStartMs  = sortedTimes.first(),
            windowEndMs    = sortedTimes.last(),
        )
    }

    /**
     * `$GPRMC,hhmmss.ss,A,llll.ll,a,yyyyy.yy,a,x.x,x.x,ddmmyy,...,*hh`
     *
     * Field indices (with talker-stripped `parts`):
     *  - 1: UTC time of fix (hhmmss[.sss])
     *  - 2: A (valid) / V (invalid)
     *  - 3: latitude DDMM.MMMM, 4: N/S
     *  - 5: longitude DDDMM.MMMM, 6: E/W
     *  - 7: speed over ground in knots
     *  - 8: course over ground in degrees true
     *  - 9: date ddmmyy
     */
    private fun parseRmc(parts: List<String>): Triple<Long, GpsFix, Calendar>? {
        if (parts.size < 10) return null
        if (parts.getOrNull(2) != "A") return null   // no fix
        val hhmmss = parts[1].takeIf { it.length >= 6 } ?: return null
        val ddmmyy = parts[9].takeIf { it.length == 6 } ?: return null
        val cal = nmeaTimeToCalendar(hhmmss, ddmmyy) ?: return null

        val lat = parseLatLon(parts[3], parts.getOrNull(4))
        val lon = parseLatLon(parts[5], parts.getOrNull(6))
        val speedMs = parts.getOrNull(7)?.toFloatOrNull()?.let { it * 0.514444f }  // knots → m/s
        val headingDeg = parts.getOrNull(8)?.toFloatOrNull()

        val tMs = cal.timeInMillis
        val fix = GpsFix(
            tEpochMs   = tMs,
            lat        = lat,
            lon        = lon,
            speedMs    = speedMs,
            altitudeM  = null,        // GGA fills this if present
            headingDeg = headingDeg,
            accelG     = null,
        )
        return Triple(tMs, fix, cal)
    }

    /**
     * `$GPGGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,x,xx,x.x,x.x,M,x.x,M,...`
     *
     * We only need fields 1 (time) and 9 (altitude in metres).
     */
    private fun parseGgaAlt(parts: List<String>, dateAnchor: Calendar): Pair<Long, Int>? {
        if (parts.size < 11) return null
        val hhmmss = parts[1].takeIf { it.length >= 6 } ?: return null
        val alt = parts[9].toFloatOrNull()?.toInt() ?: return null
        val anchorDDMMYY = "%02d%02d%02d".format(
            dateAnchor.get(Calendar.DAY_OF_MONTH),
            dateAnchor.get(Calendar.MONTH) + 1,
            dateAnchor.get(Calendar.YEAR) % 100,
        )
        val cal = nmeaTimeToCalendar(hhmmss, anchorDDMMYY) ?: return null
        return cal.timeInMillis to alt
    }

    /** `DDMM.MMMM` + `N|S|E|W` → signed decimal degrees, null on parse failure. */
    private fun parseLatLon(raw: String, hemisphere: String?): Double? {
        if (raw.isBlank() || hemisphere.isNullOrBlank()) return null
        val dot = raw.indexOf('.').takeIf { it >= 3 } ?: return null
        // The "degrees" segment is everything left of the rightmost two
        // digits before the decimal point: lat DDMM.M…, lon DDDMM.M….
        val deg = raw.substring(0, dot - 2).toIntOrNull() ?: return null
        val min = raw.substring(dot - 2).toDoubleOrNull() ?: return null
        val signed = deg + min / 60.0
        return when (hemisphere) {
            "N", "E" ->  signed
            "S", "W" -> -signed
            else     -> null
        }
    }

    /** "hhmmss[.fff]" + "ddmmyy" → UTC Calendar, null on parse failure. */
    private fun nmeaTimeToCalendar(hhmmss: String, ddmmyy: String): Calendar? {
        return try {
            val ms = (hhmmss.substringAfter('.', "").take(3).padEnd(3, '0').toIntOrNull() ?: 0)
            val hh = hhmmss.substring(0, 2).toInt()
            val mm = hhmmss.substring(2, 4).toInt()
            val ss = hhmmss.substring(4, 6).toInt()
            val dd = ddmmyy.substring(0, 2).toInt()
            val mo = ddmmyy.substring(2, 4).toInt()
            val yy = ddmmyy.substring(4, 6).toInt() + 2000
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(yy, mo - 1, dd, hh, mm, ss)
                set(Calendar.MILLISECOND, ms)
            }
        } catch (_: Throwable) { null }
    }

    @Suppress("unused")     // kept for log/debug if we end up needing the wall-clock date display
    private fun fmt(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            format(Date(epochMs))
        }
    }
}
