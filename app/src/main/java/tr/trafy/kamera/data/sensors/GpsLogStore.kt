package tr.trafy.kamera.data.sensors

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Read side of the phone-log NDJSON files. Loads the 1-2 day files that
 * overlap a clip's `[t0..t1]` window into memory and returns a
 * [GpsTrack]-implementing object the burn-in encoder can query per frame.
 *
 * Parse-tolerant: ignores blank lines, malformed JSON tails, and missing
 * keys. A half-written tail line from a crash gets silently dropped.
 *
 * Performance: a full day's file (~28 800 rows at 1 Hz × 8 h drive) parses
 * in ~50 ms on a mid-range phone. Most clips are <2 minutes so we only
 * actually walk a tiny slice — the encoder calls [load] once per
 * downloaded clip, then `fixAt()` per frame is O(log n).
 */
class GpsLogStore(private val context: Context) {

    companion object { private const val TAG = "Trafy.GpsLogStore" }

    /**
     * Returns a track covering `[t0EpochMs..t1EpochMs]` from the day file(s)
     * the window touches. The returned [GpsTrack] reports `coveragePercent`
     * for the requested window (not the whole file).
     */
    suspend fun load(t0EpochMs: Long, t1EpochMs: Long): GpsTrack = withContext(Dispatchers.IO) {
        if (t1EpochMs < t0EpochMs) return@withContext EmptyGpsTrack
        val dir = File(context.applicationContext.filesDir, "gps_logs")
        if (!dir.exists()) return@withContext EmptyGpsTrack

        val days = dayKeysForRange(t0EpochMs, t1EpochMs)
        val files = days.mapNotNull { d ->
            File(dir, "$d.ndjson").takeIf { it.exists() && it.length() > 0 }
        }
        if (files.isEmpty()) return@withContext EmptyGpsTrack

        val timesBuf = ArrayList<Long>(2048)
        val fixesBuf = ArrayList<GpsFix>(2048)
        for (f in files) {
            parseFileInto(f, timesBuf, fixesBuf, t0EpochMs, t1EpochMs)
        }
        if (timesBuf.isEmpty()) return@withContext EmptyGpsTrack

        // The file is already chronological because the logger appends in
        // real time, but defensively sort in case the user dropped extra
        // files in.
        val pairs = timesBuf.indices.sortedBy { timesBuf[it] }
        val sortedTimes = LongArray(pairs.size) { timesBuf[pairs[it]] }
        val sortedFixes = Array(pairs.size) { fixesBuf[pairs[it]] }

        IndexedGpsTrack(
            times          = sortedTimes,
            fixes          = sortedFixes,
            sourceLabel    = "telefondan",
            windowStartMs  = t0EpochMs,
            windowEndMs    = t1EpochMs,
        )
    }

    private fun parseFileInto(
        file: File,
        timesOut: MutableList<Long>,
        fixesOut: MutableList<GpsFix>,
        t0EpochMs: Long, t1EpochMs: Long,
    ) {
        try {
            BufferedReader(FileReader(file)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val fix = parseLine(line) ?: continue
                    if (fix.tEpochMs < t0EpochMs - GpsTrack.DEFAULT_MAX_GAP_MS) continue
                    if (fix.tEpochMs > t1EpochMs + GpsTrack.DEFAULT_MAX_GAP_MS) continue
                    timesOut += fix.tEpochMs
                    fixesOut += fix
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse failed on ${file.name}: ${t.message}")
        }
    }

    /**
     * Hand-rolled NDJSON line parser. Keeps the dependency footprint low
     * (no kotlinx.serialization) and tolerates missing fields. Skips on
     * unparseable timestamp — every other field is nullable so partial
     * lines still produce a usable [GpsFix].
     *
     * Keys: `t` (epoch ms), `src` ("phone"|"cam"), `lat`, `lon`, `spd`
     * (m/s), `alt` (int m), `hdg` (deg), `g` (signed g).
     */
    private fun parseLine(line: String): GpsFix? {
        val t = extractLong(line, "\"t\"")    ?: return null
        val lat = extractDouble(line, "\"lat\"")
        val lon = extractDouble(line, "\"lon\"")
        val spd = extractDouble(line, "\"spd\"")
        val alt = extractLong(line, "\"alt\"")?.toInt()
        val hdg = extractDouble(line, "\"hdg\"")?.toFloat()
        val g   = extractDouble(line, "\"g\"")?.toFloat()
        return GpsFix(
            tEpochMs   = t,
            lat        = lat,
            lon        = lon,
            speedMs    = spd?.toFloat(),
            altitudeM  = alt,
            headingDeg = hdg,
            accelG     = g,
        )
    }

    private fun extractLong(line: String, key: String): Long? {
        val keyStart = line.indexOf(key)
        if (keyStart < 0) return null
        val colon = line.indexOf(':', startIndex = keyStart + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < line.length && line[i] == ' ') i++
        val numStart = i
        while (i < line.length && (line[i] == '-' || line[i].isDigit())) i++
        if (i == numStart) return null
        return line.substring(numStart, i).toLongOrNull()
    }

    private fun extractDouble(line: String, key: String): Double? {
        val keyStart = line.indexOf(key)
        if (keyStart < 0) return null
        val colon = line.indexOf(':', startIndex = keyStart + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < line.length && line[i] == ' ') i++
        val numStart = i
        while (i < line.length && (line[i] == '-' || line[i] == '.' || line[i].isDigit() || line[i] == 'e' || line[i] == 'E' || line[i] == '+')) i++
        if (i == numStart) return null
        return line.substring(numStart, i).toDoubleOrNull()
    }

    private fun dayKeysForRange(t0EpochMs: Long, t1EpochMs: Long): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val out = ArrayList<String>(2)
        var t = t0EpochMs - 86_400_000L  // include the day before in case of UTC roll-over edge
        while (t <= t1EpochMs + 86_400_000L) {
            val key = fmt.format(Date(t))
            if (out.isEmpty() || out.last() != key) out += key
            t += 86_400_000L
        }
        return out
    }
}
