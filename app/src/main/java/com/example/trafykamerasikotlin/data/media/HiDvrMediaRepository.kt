package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/**
 * Fetches, deletes and controls recording for HiSilicon (HiDVR) dashcam media files.
 *
 * Protocol flow (from RemoteFileManager.java + HiDvrProtocol.java):
 *  1. Stop recording — required before browsing
 *  2. GET getdirfilecount.cgi?&-dir=<sdPath> → var count="N"; — sets active directory server-side
 *  3. GET getfilelist.cgi?&-start=0&-end=N   → semicolon-separated file paths from active dir
 *  4. File HTTP URL = http://<ip><path>
 *  5. Thumbnail URL = same path but with .thm extension (HiDVR auto-generates .thm sidecars)
 *  6. Delete: deletefile.cgi?&-name=<path>   (path without "http://ip" prefix)
 *  7. Resume recording when done
 */
class HiDvrMediaRepository {

    companion object {
        private const val TAG = "Trafy.MediaRepo"
        private const val CGI = "/cgi-bin/hisnet"

        // HiDVR directory names — confirmed via getdircapability.cgi returning "emr,norm,photo"
        private const val DIR_NORMAL = "norm"
        private const val DIR_EVENT  = "emr"
        private const val DIR_PHOTO  = "photo"

        // Default dir set used when getdircapability.cgi is unavailable; matches the
        // single-camera HiSilicon firmware that has always worked here.
        private val FALLBACK_DIRS = listOf(DIR_NORMAL, DIR_EVENT, DIR_PHOTO)

        // Tokens used to classify a directory name reported by getdircapability.cgi.
        // Source: HIDevUtil.getDoubleCameraBack*/In* in the reference golook app.
        private val BACK_TOKENS   = listOf("back_", "rear", "after")
        private val INSIDE_TOKENS = listOf("int_", "inside")
        private val EVENT_TOKENS  = listOf("emr", "lock", "event")
        private val NORMAL_TOKENS = listOf("norm")
        private val PHOTO_TOKENS  = listOf("photo")
    }

    /** Video/photo directories on the camera's SD card, grouped by camera channel. */
    private data class DirLayout(
        val front:  List<Pair<String, Boolean>>,  // (dir, isPhoto)
        val back:   List<Pair<String, Boolean>>,
        val inside: List<Pair<String, Boolean>>,
    ) {
        fun videoDirs():  List<Triple<String, String, Boolean>> = // (dir, hint, isPhoto=false)
            front .filter  { !it.second }.map { Triple(it.first, "Front",  false) } +
            back  .filter  { !it.second }.map { Triple(it.first, "Back",   false) } +
            inside.filter  { !it.second }.map { Triple(it.first, "Inside", false) }
        fun photoDirs(): List<Triple<String, String, Boolean>> =
            front .filter  {  it.second }.map { Triple(it.first, "Front",  true) } +
            back  .filter  {  it.second }.map { Triple(it.first, "Back",   true) } +
            inside.filter  {  it.second }.map { Triple(it.first, "Inside", true) }
    }

    // ── Recording control ──────────────────────────────────────────────────

    suspend fun stopRecording(deviceIp: String): Boolean {
        val ok = DashcamHttpClient.probe("http://$deviceIp$CGI/workmodecmd.cgi?&-cmd=stop")
        Log.d(TAG, "stopRecording → $ok")
        return ok
    }

    suspend fun startRecording(deviceIp: String): Boolean {
        val ok = DashcamHttpClient.probe("http://$deviceIp$CGI/workmodecmd.cgi?&-cmd=start")
        Log.d(TAG, "startRecording → $ok")
        return ok
    }

    suspend fun registerClient(deviceIp: String, clientIp: String): Boolean {
        val ok = DashcamHttpClient.probe(
            "http://$deviceIp$CGI/client.cgi?&-operation=register&-ip=$clientIp"
        )
        Log.d(TAG, "registerClient → $ok")
        return ok
    }

    // ── Multi-camera live switching ───────────────────────────────────────

    /**
     * Camera channel keys this device exposes, always ordered Front → Back → Inside.
     * Reuses the same `getdircapability.cgi` probe that media browsing uses: a device
     * with `back_*` / `rear*` / `after*` directories has a rear camera wired up, and
     * `int_*` / `inside*` directories indicate an inside camera. Returns at least
     * `["Front"]` since every HiDVR unit has a primary lens.
     */
    suspend fun discoverCameras(deviceIp: String): List<String> {
        val layout = discoverDirs(deviceIp)
        return buildList {
            add("Front")
            if (layout.back.isNotEmpty())   add("Back")
            if (layout.inside.isNotEmpty()) add("Inside")
        }
    }

    /**
     * Tells the camera to route the given channel onto /livestream/1.
     * camid: 0=Front, 1=Back, 2=Inside (matches the reference golook app's convention).
     * Callers must restart their RTSP player after this returns — the stream URL
     * stays the same but its contents change to the selected lens.
     */
    suspend fun selectLiveCamera(deviceIp: String, camid: Int): Boolean {
        val url = "http://$deviceIp$CGI/getcamchnl.cgi?&-camid=$camid"
        val body = DashcamHttpClient.get(url)
        Log.i(TAG, "selectLiveCamera: camid=$camid body=${body?.take(80)}")
        return body != null
    }

    suspend fun unregisterClient(deviceIp: String, clientIp: String) {
        DashcamHttpClient.probe(
            "http://$deviceIp$CGI/client.cgi?&-operation=unregister&-ip=$clientIp"
        )
        Log.d(TAG, "unregisterClient sent")
        delay(1_000)
    }

    // ── File listing ───────────────────────────────────────────────────────

    /**
     * Fetches all video files (normal + event directories, all camera channels)
     * sorted newest-first. For dual-camera HiSilicon firmware, discovers rear/inside
     * channel directories via getdircapability.cgi; single-camera firmware keeps
     * the long-standing norm+emr behavior.
     */
    suspend fun fetchVideos(deviceIp: String): List<MediaFile> {
        val layout = discoverDirs(deviceIp)
        val all = layout.videoDirs().flatMap { (dir, hint, isPhoto) ->
            fetchDir(deviceIp, dir, isPhoto, cameraHint = hint)
        }
        return all.sortedByDescending { it.name }
    }

    /**
     * Fetches all photo files across every camera channel sorted newest-first.
     */
    suspend fun fetchPhotos(deviceIp: String): List<MediaFile> {
        val layout = discoverDirs(deviceIp)
        val all = layout.photoDirs().flatMap { (dir, hint, isPhoto) ->
            fetchDir(deviceIp, dir, isPhoto, cameraHint = hint)
        }
        return all.sortedByDescending { it.name }
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    /**
     * Deletes a single file on the dashcam.
     * The camera expects the SD-card path without the "http://ip" prefix.
     */
    suspend fun deleteFile(deviceIp: String, file: MediaFile): Boolean {
        val url = "http://$deviceIp$CGI/deletefile.cgi?&-name=${file.path}"
        val ok  = DashcamHttpClient.probe(url)
        Log.i(TAG, "delete ${file.name} → $ok")
        return ok
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Probes getdircapability.cgi? and classifies each reported dir into
     * (front/back/inside) × (video/photo) buckets. Falls back to the static
     * norm+emr+photo set when the probe fails or returns nothing usable,
     * preserving behavior for every single-camera HiDVR already in the field.
     */
    private suspend fun discoverDirs(deviceIp: String): DirLayout {
        val body = DashcamHttpClient.get("http://$deviceIp$CGI/getdircapability.cgi?")
        val capability = body?.let { parseVarString(it, "capability") }
        val dirs = capability
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .takeUnless { it.isNullOrEmpty() }
            ?: FALLBACK_DIRS
        Log.i(TAG, "discoverDirs: $dirs")

        val front  = mutableListOf<Pair<String, Boolean>>()
        val back   = mutableListOf<Pair<String, Boolean>>()
        val inside = mutableListOf<Pair<String, Boolean>>()
        for (dir in dirs) {
            val lower = dir.lowercase()
            val bucket = when {
                BACK_TOKENS.any   { lower.contains(it) } -> back
                INSIDE_TOKENS.any { lower.contains(it) } -> inside
                else                                     -> front
            }
            val isPhoto = when {
                PHOTO_TOKENS.any { lower.contains(it) }  -> true
                NORMAL_TOKENS.any { lower.contains(it) } -> false
                EVENT_TOKENS.any { lower.contains(it) }  -> false
                else -> continue  // unknown dir kind, skip so we don't mis-route
            }
            bucket.add(dir to isPhoto)
        }
        return DirLayout(front, back, inside)
    }

    /**
     * Fetches all files in one SD-card directory.
     * Two-step: first getdirfilecount (sets the active directory server-side), then getfilelist.
     */
    private suspend fun fetchDir(
        deviceIp: String,
        sdDir: String,
        isPhoto: Boolean,
        cameraHint: String? = null,
    ): List<MediaFile> = CamHttpGate.mutex.withLock {
        // Hold the cam HTTP gate for the full count+list sequence. Without
        // this, an in-flight thumbnail MMR stream saturates the cam's two
        // HTTP slots and the listing's TCP connect attempts time out
        // (`Failed to connect to /192.168.0.1:80`) → user sees empty Media.
        val base = "http://$deviceIp$CGI"

        // Step 1: get count AND tell the camera which directory we want
        val countBody = DashcamHttpClient.get("$base/getdirfilecount.cgi?&-dir=$sdDir")
        if (countBody == null) {
            Log.w(TAG, "getdirfilecount failed for $sdDir")
            return emptyList()
        }
        val count = parseVarInt(countBody, "count")
        Log.d(TAG, "dir=$sdDir count=$count hint=$cameraHint")
        if (count <= 0) return emptyList()

        // Step 2: list files — getdirfilelist.cgi uses -dir + 0-based inclusive end index
        val listUrl = "$base/getdirfilelist.cgi?&-dir=$sdDir&-start=0&-end=${count - 1}"
        var listBody = DashcamHttpClient.get(listUrl)
        // The G3518 firmware sometimes returns 200 with an empty body under
        // stress (e.g. shortly after several parallel range requests). It's a
        // transient cam-side hiccup — retry once with a small backoff before
        // giving up. Without this, the user sees "0 videos" for a cam that
        // clearly has files (count > 0).
        if (listBody.isNullOrBlank() && count > 0) {
            Log.w(TAG, "getfilelist returned empty body for $sdDir despite count=$count — retrying after 600ms")
            kotlinx.coroutines.delay(600)
            listBody = DashcamHttpClient.get(listUrl)
        }
        if (listBody == null) {
            Log.w(TAG, "getfilelist failed for $sdDir")
            return emptyList()
        }
        if (listBody.isBlank() && count > 0) {
            // Surface as an error to the caller so the UI shows "retry" instead
            // of pretending the SD card is empty.
            throw RuntimeException("Camera returned an empty file list for $sdDir despite reporting $count files. Probably overloaded — try again in a few seconds.")
        }

        return parseSemicolonList(listBody)
            .filter { path -> isMediaFile(path) }
            .map { path ->
                // Camera returns paths like "sd//norm/file.MP4" — prepend "/" for HTTP
                val httpUrl      = "http://$deviceIp/$path"
                // Trafy Uno Pro / G3518 firmware doesn't generate `.thm`
                // sidecars (probed → 404). Route video thumbnails through
                // the client-side frame-extraction fetcher; photos use their
                // own URL since they ARE images.
                val thumbnailUrl = if (isPhoto) httpUrl
                                   else HiDvrThumbnailFetcher.urlFor(deviceIp, path)
                MediaFile(
                    path         = path,
                    httpUrl      = httpUrl,
                    thumbnailUrl = thumbnailUrl,
                    name         = path.substringAfterLast('/'),
                    isPhoto      = isPhoto,
                    cameraHint   = cameraHint,
                )
            }
    }

    /** Parses `var key="value";` and returns the value, or null if not found. */
    private fun parseVarString(body: String, key: String): String? {
        val marker   = "var $key=\""
        val start    = body.indexOf(marker).takeIf { it >= 0 } ?: return null
        val valStart = start + marker.length
        val end      = body.indexOf('"', valStart).takeIf { it >= 0 } ?: return null
        return body.substring(valStart, end)
    }

    /** Parses "var count=\"5\";" and returns 5. */
    private fun parseVarInt(body: String, key: String): Int {
        val marker   = "var $key=\""
        val start    = body.indexOf(marker).takeIf { it >= 0 } ?: return 0
        val valStart = start + marker.length
        val end      = body.indexOf('"', valStart).takeIf { it >= 0 } ?: return 0
        return body.substring(valStart, end).trim().toIntOrNull() ?: 0
    }

    /** Splits a semicolon-delimited list and returns non-empty trimmed entries. */
    private fun parseSemicolonList(body: String): List<String> =
        body.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** True if the path extension looks like a video or photo (not a .thm / .TXT sidecar). */
    private fun isMediaFile(path: String): Boolean {
        val ext = path.substringAfterLast('.').lowercase()
        return ext in setOf("mp4", "h264", "mov", "avi", "jpg", "jpeg")
    }
}
