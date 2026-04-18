package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSession
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import com.example.trafykamerasikotlin.data.model.MediaFile
import org.json.JSONObject

/** Capacity snapshot of the camera's SD card, in bytes. */
data class AllwinnerSdInfo(val totalBytes: Long, val freeBytes: Long)

/**
 * Media-browsing repository for Allwinner V853 (A19) devices.
 *
 * Listing + capacity go through the relay envelope on TCP :8000, owned by
 * [AllwinnerSessionHolder]:
 *   - `getdates`               → { dates: { list: [{ name: "YYYYMMDD" }, …] } }
 *   - `getvideos` { date:"…" } → { videos: { list: [{ name, mtime, size }, …] } }
 *   - `sdinfo`                 → { total:<bytes>, left:<bytes> }
 *
 * Video filename grammar: `[F|B]YYYYMMDDhhmmss-<dur>-<flag>.ts`
 *   F = front cam, B = rear cam; flag L = loop (E likely = event).
 *
 * Playback and download both go through the separate UDP RTP2P transport — see
 * [com.example.trafykamerasikotlin.data.allwinner.AllwinnerRtp2pClient]. Delete
 * is unsupported by the device firmware (absent from the OEM app's UI as well).
 */
class AllwinnerMediaRepository {

    companion object {
        private const val TAG = "Trafy.AllwinnerMedia"

        /** Parses `<dur>` from `<letter>YYYYMMDDhhmmss-<dur>-<flag>.ts`. 0 if missing. */
        fun parseDurationSecondsFromName(name: String): Int {
            val dashIdx = name.indexOf('-')
            if (dashIdx <= 0) return 0
            val rest = name.substring(dashIdx + 1)
            val nextDash = rest.indexOf('-')
            val durStr = if (nextDash < 0) rest.substringBefore('.') else rest.substring(0, nextDash)
            return durStr.toIntOrNull() ?: 0
        }

        /** First letter F → 0 (front), B → 1 (back). Other inputs default to 0. */
        fun cameraIdFromName(name: String): Int = when (name.firstOrNull()) {
            'B', 'b' -> 1
            else     -> 0
        }

        /** Best-effort: recover `YYYYMMDD` from either `allwinner://<date>/<name>` path or filename. */
        fun dateFromPathOrName(path: String, name: String): String {
            val marker = "allwinner://"
            if (path.startsWith(marker)) {
                val after = path.removePrefix(marker)
                val slash = after.indexOf('/')
                if (slash > 0) return after.substring(0, slash)
            }
            // Fallback: the filename itself has YYYYMMDD starting at index 1.
            if (name.length >= 9) {
                val stamp = name.substring(1, 9)
                if (stamp.all { it.isDigit() }) return stamp
            }
            return ""
        }
    }

    /** Aggregates videos across every date folder on the SD card, newest first. */
    suspend fun fetchVideos(deviceIp: String): List<MediaFile> {
        Log.i(TAG, "fetchVideos deviceIp=$deviceIp")
        val session = requireSession(deviceIp) ?: return emptyList()

        val datesResp = try {
            session.relay("getdates")
        } catch (e: Exception) {
            Log.e(TAG, "getdates failed: ${e.message}", e)
            return emptyList()
        }
        val datesList = datesResp.optJSONObject("dates")?.optJSONArray("list")
        if (datesList == null || datesList.length() == 0) {
            Log.i(TAG, "no dates on SD card")
            return emptyList()
        }

        val out = mutableListOf<MediaFile>()
        for (i in 0 until datesList.length()) {
            val date = datesList.optJSONObject(i)?.optString("name").orEmpty()
            if (date.isEmpty()) continue

            val resp = try {
                session.relay("getvideos", JSONObject().put("date", date))
            } catch (e: Exception) {
                Log.w(TAG, "getvideos date=$date failed: ${e.message}")
                continue
            }
            val vids = resp.optJSONObject("videos")?.optJSONArray("list") ?: continue
            for (j in 0 until vids.length()) {
                val v = vids.optJSONObject(j) ?: continue
                val name = v.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val sizeBytes = v.optLong("size", -1L).takeIf { it > 0L }
                out += MediaFile(
                    path         = "allwinner://$date/$name",
                    httpUrl      = "",   // playback goes through the RTP2P UDP transport, not HTTP
                    thumbnailUrl = "",   // device doesn't expose thumbnails; UI falls back to Movie icon
                    name         = name,
                    isPhoto      = false,
                    sizeBytes    = sizeBytes,
                )
            }
        }

        Log.i(TAG, "fetchVideos → ${out.size} videos across ${datesList.length()} dates")
        return out.sortedByDescending { parseEpochFromName(it.name) }
    }

    /** This device records video only (camlist is "F,B"); no still-photo folder. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchPhotos(deviceIp: String): List<MediaFile> = emptyList()

    /**
     * Best-effort SD-card usage snapshot. Returns null on failure so the UI can
     * silently omit the usage row without affecting the rest of the load.
     */
    suspend fun fetchSdInfo(deviceIp: String): AllwinnerSdInfo? {
        val session = requireSession(deviceIp) ?: return null
        return try {
            val resp = session.relay("sdinfo")
            if (resp.optInt("ret", -1) != 0) return null
            val total = resp.optLong("total", -1L)
            val left  = resp.optLong("left",  -1L)
            if (total <= 0L || left < 0L) null else AllwinnerSdInfo(total, left)
        } catch (e: Exception) {
            Log.w(TAG, "sdinfo failed: ${e.message}")
            null
        }
    }

    /** Resolves the [AllwinnerSession] so callers (e.g. MediaViewModel) can open RTP2P streams. */
    internal suspend fun session(deviceIp: String): AllwinnerSession? = requireSession(deviceIp)

    /** Uses the session opened during handshake; re-opens it if it's dead (stale TCP) or absent. */
    private suspend fun requireSession(deviceIp: String): AllwinnerSession? =
        AllwinnerSessionHolder.requireAlive(deviceIp)

    /**
     * Extracts the start-time epoch (seconds) from a filename like
     * `F20260412163807-60-L.ts`. Returns 0 if the name doesn't match — such
     * entries sort last. UTC is fine here: we only need a stable ordering.
     */
    private fun parseEpochFromName(name: String): Long {
        // Strip leading F/B, take the next 14 chars as YYYYMMDDhhmmss.
        if (name.length < 15) return 0L
        val first = name[0]
        if (first != 'F' && first != 'B') return 0L
        val stamp = name.substring(1, 15)
        if (!stamp.all { it.isDigit() }) return 0L
        return try {
            val y  = stamp.substring(0, 4).toInt()
            val mo = stamp.substring(4, 6).toInt()
            val d  = stamp.substring(6, 8).toInt()
            val h  = stamp.substring(8, 10).toInt()
            val mi = stamp.substring(10, 12).toInt()
            val s  = stamp.substring(12, 14).toInt()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, s)
            cal.timeInMillis / 1000L
        } catch (_: Exception) { 0L }
    }
}
