package com.example.trafykamerasikotlin.data.gps

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import com.example.trafykamerasikotlin.data.sensors.GpsTrack

/**
 * Easytech (Trafy Dos Pro / Tres Pro) GPS provider.
 *
 * The Trafy Dos Pro family ships with a **pluggable physical GPS dongle**
 * but the precise retrieval endpoint isn't documented anywhere we've
 * looked. The OEM GoLook app reveals the standard Easytech CGI surface
 * (`/app/getsdinfo`, `/app/getparamvalue?param=…`, etc.) but no
 * `/app/get*gps*` URL. Static analysis of the dex confirms only an
 * `EEASYTECH_SET_GPS` key — a SET, not a query.
 *
 * Hypothesis: GPS data is dropped onto the SD card as a per-clip sidecar
 * file, the same way HiSilicon does. The Easytech firmware shares ancestry
 * with HiSilicon code, and GoLook bundles HiSilicon's `GpsDataManager`
 * which references `http://192.168.0.1/sd//GPSdata/`. The Easytech upload
 * surface at `http://192.168.169.1/upload/mnt/sdcard/` is the natural
 * place to look for an analogous structure.
 *
 * **This implementation probes a small list of candidate paths** in order
 * and returns the first one that yields a parseable NMEA body. Each
 * attempt is logged with its HTTP outcome so the first time the user
 * downloads a clip from a GPS-equipped Easytech cam we'll be able to
 * read the logs and lock the working path in.
 *
 * If you find a definitive endpoint by running the app, replace this
 * provider with a single-path implementation and delete the rest.
 */
object EeasytechCamGpsProvider : CamGpsProvider {

    private const val TAG = "Trafy.EeasytechCamGps"

    /**
     * Whether we've already discovered (or definitively failed to discover)
     * the GPS sidecar for this session. Cached so back-to-back downloads
     * don't burn another round of timeouts.
     *
     * `null` = haven't probed yet; `""` = probed and gave up; otherwise =
     * a URL pattern with `__BASE__` as the basename placeholder, learned
     * once and reused for every clip in the same session.
     */
    @Volatile private var sessionPattern: String? = null

    override suspend fun trackFor(
        file: MediaFile, deviceIp: String, context: Context,
    ): GpsTrack? {
        val base = file.name.substringBeforeLast('.', file.name)

        // Fast path: we already know the pattern (or know there isn't one).
        sessionPattern?.let { p ->
            if (p.isEmpty()) return null
            val url = p.replace("__BASE__", base).replace("__IP__", deviceIp)
            return fetchAndParse(url, file.name)
        }

        // Slow path: probe candidate URLs once, with tight per-call timeouts.
        // Trims a ~5-minute "all candidates time out" worst case to ~15 s.
        for (pattern in CANDIDATE_PATTERNS) {
            val url = pattern.replace("__BASE__", base).replace("__IP__", deviceIp)
            val body = DashcamHttpClient.getQuick(url, timeoutMs = PROBE_TIMEOUT_MS)
            if (body.isNullOrBlank()) {
                Log.d(TAG, "miss: $url")
                continue
            }
            val track = parseIfNmea(body, file.name)
            if (track != null) {
                Log.i(TAG, "GPS sidecar pattern locked: $pattern")
                sessionPattern = pattern
                return track
            } else {
                Log.d(TAG, "hit but unparseable: $url (${body.length}B)")
            }
        }
        Log.d(TAG, "no GPS sidecar for ${file.name} — won't probe again this session")
        sessionPattern = ""   // poison-pill: skip probing for the rest of the session
        return null
    }

    private suspend fun fetchAndParse(url: String, srcName: String): GpsTrack? {
        val body = DashcamHttpClient.getQuick(url, timeoutMs = PROBE_TIMEOUT_MS) ?: return null
        return parseIfNmea(body, srcName)
    }

    private fun parseIfNmea(body: String, srcName: String): GpsTrack? {
        val looksNmea = body.lineSequence().any { it.startsWith("$") && it.length > 6 }
        if (!looksNmea) return null
        return HiDvrCamGpsProvider.parseNmeaPublic(body, srcName)
    }

    private const val PROBE_TIMEOUT_MS = 2_500L

    /**
     * Top-3 most plausible URL patterns. `__BASE__` is replaced with the
     * clip's basename (no extension), `__IP__` with the device IP. Add
     * patterns to the end as you find new firmware variants — never
     * remove a working one without confirming it's dead.
     */
    private val CANDIDATE_PATTERNS = listOf(
        // HiSilicon-clone path under the Easytech upload mount (most likely)
        "http://__IP__/upload/mnt/sdcard/GPSdata/__BASE__.TXT",
        // Sibling-of-video, with .gps extension (used by some Easytech variants)
        "http://__IP__/upload/mnt/sdcard/__BASE__.gps",
        // CGI-shaped guess
        "http://__IP__/app/getgps?file=__BASE__",
    )
}
