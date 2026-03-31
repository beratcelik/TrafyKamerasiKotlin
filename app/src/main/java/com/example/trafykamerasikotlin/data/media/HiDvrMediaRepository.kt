package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient

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

    // ── File listing ───────────────────────────────────────────────────────

    /**
     * Fetches all video files (normal + event directories) sorted newest-first.
     */
    suspend fun fetchVideos(deviceIp: String): List<MediaFile> {
        val normal = fetchDir(deviceIp, DIR_NORMAL, isPhoto = false)
        val event  = fetchDir(deviceIp, DIR_EVENT,  isPhoto = false)
        return (normal + event).sortedByDescending { it.name }
    }

    /**
     * Fetches all photo files sorted newest-first.
     */
    suspend fun fetchPhotos(deviceIp: String): List<MediaFile> {
        return fetchDir(deviceIp, DIR_PHOTO, isPhoto = true).sortedByDescending { it.name }
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
     * Fetches all files in one SD-card directory.
     * Two-step: first getdirfilecount (sets the active directory server-side), then getfilelist.
     */
    private suspend fun fetchDir(
        deviceIp: String,
        sdDir: String,
        isPhoto: Boolean,
    ): List<MediaFile> {
        val base = "http://$deviceIp$CGI"

        // Step 1: get count AND tell the camera which directory we want
        val countBody = DashcamHttpClient.get("$base/getdirfilecount.cgi?&-dir=$sdDir")
        if (countBody == null) {
            Log.w(TAG, "getdirfilecount failed for $sdDir")
            return emptyList()
        }
        val count = parseVarInt(countBody, "count")
        Log.d(TAG, "dir=$sdDir count=$count")
        if (count <= 0) return emptyList()

        // Step 2: list files — getdirfilelist.cgi uses -dir + 0-based inclusive end index
        val listBody = DashcamHttpClient.get("$base/getdirfilelist.cgi?&-dir=$sdDir&-start=0&-end=${count - 1}")
        if (listBody == null) {
            Log.w(TAG, "getfilelist failed for $sdDir")
            return emptyList()
        }

        return parseSemicolonList(listBody)
            .filter { path -> isMediaFile(path) }
            .map { path ->
                // Camera returns paths like "sd//norm/file.MP4" — prepend "/" for HTTP
                val httpUrl      = "http://$deviceIp/$path"
                val thumbnailUrl = if (isPhoto) httpUrl
                                   else "http://$deviceIp/${path.substringBeforeLast('.')}.thm"
                MediaFile(
                    path         = path,
                    httpUrl      = httpUrl,
                    thumbnailUrl = thumbnailUrl,
                    name         = path.substringAfterLast('/'),
                    isPhoto      = isPhoto,
                )
            }
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
