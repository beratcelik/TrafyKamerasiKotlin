package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import org.json.JSONObject

/**
 * Fetches and manages recorded media for Easytech/Allwinner dashcam devices.
 *
 * Flow:
 *  1. GET /app/playback?param=enter    — must be called before browsing
 *  2. GET /app/getfilelist?folder=FOLDER&start=START&end=END
 *     Folders: loop (normal), emr (event/lock), event (photos), park (parking)
 *     Response: {"result":0,"info":[{"files":[...],"count":N,"folder":"loop"}]}
 *  3. File download URL: http://IP + name   (name = "/loop/file.ts")
 *  4. Thumbnail URL:     http://IP/app/getthumbnail?file=PATH
 *  5. Delete:            GET /app/deletefile?file=PATH
 *  6. GET /app/playback?param=exit     — call when done
 *
 * Multi-camera: a single folder request returns all cameras' files together.
 * Camera can be identified by filename suffix: _f = front, _b = rear, _i = inside.
 *
 * Reference: EeasytechProtocol.java / EeasytechProtocol.smali (getFileList, jsonToFileItem)
 */
class EeasytechMediaRepository {

    companion object {
        private const val TAG = "Trafy.EeasytechMedia"

        /** Max files per API request — matches GoLook's getMaxFileCount(). */
        private const val PAGE_SIZE = 49

        // Folder names used by the camera API (from EeasytechProtocol.smali)
        private const val FOLDER_LOOP  = "loop"   // normal loop recordings
        private const val FOLDER_EMR   = "emr"    // event / locked recordings
        private const val FOLDER_PHOTO = "event"  // photos (DEVICE_DIR_PHOTO on-device name)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Puts the camera in playback mode. Must be called before any file listing. */
    suspend fun enterPlayback(deviceIp: String): Boolean {
        val ok = DashcamHttpClient.probe("http://$deviceIp/app/playback?param=enter")
        Log.d(TAG, "playback?param=enter → $ok")
        return ok
    }

    /**
     * Exits playback mode. Playback doesn't compete with the encoder
     * (file-system access can run alongside SD recording), so we don't
     * need to explicitly resume recording here — the cam keeps recording
     * throughout the Media browse session. The HomeScreen reaffirms
     * `rec=1` as a safety net when the user returns to the main page.
     */
    suspend fun exitPlayback(deviceIp: String) {
        val ok = DashcamHttpClient.probe("http://$deviceIp/app/playback?param=exit")
        Log.d(TAG, "playback?param=exit → $ok")
    }

    /**
     * Fetches all video files (loop + emr folders) sorted newest-first.
     * Includes footage from all active cameras (front, rear, inside).
     */
    suspend fun fetchVideos(deviceIp: String): List<MediaFile> {
        val loop = fetchFolder(deviceIp, FOLDER_LOOP, isPhoto = false)
        val emr  = fetchFolder(deviceIp, FOLDER_EMR,  isPhoto = false)
        return (loop + emr).sortedByDescending { it.name }
    }

    /**
     * Fetches all photo files sorted newest-first.
     */
    suspend fun fetchPhotos(deviceIp: String): List<MediaFile> {
        return fetchFolder(deviceIp, FOLDER_PHOTO, isPhoto = true)
            .sortedByDescending { it.name }
    }

    /**
     * Deletes a single file from the camera's SD card.
     * Returns true if the camera responded with HTTP 200.
     */
    suspend fun deleteFile(deviceIp: String, file: MediaFile): Boolean {
        // file.path is the camera-side path, e.g. "/loop/20230101_120000_F.ts"
        val url = "http://$deviceIp/app/deletefile?file=${file.path}"
        val ok  = DashcamHttpClient.probe(url)
        Log.i(TAG, "delete ${file.name} → $ok")
        return ok
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Fetches all files in one folder, paging through results until done.
     */
    private suspend fun fetchFolder(
        deviceIp: String,
        folder: String,
        isPhoto: Boolean,
    ): List<MediaFile> {
        val result = mutableListOf<MediaFile>()
        var start  = 0
        while (true) {
            val end  = start + PAGE_SIZE - 1
            val url  = "http://$deviceIp/app/getfilelist?folder=$folder&start=$start&end=$end"
            val body = DashcamHttpClient.get(url)
            if (body == null) {
                Log.w(TAG, "getfilelist($folder, $start..$end) failed")
                break
            }
            val (files, totalCount) = parseFileList(body, deviceIp, isPhoto)
            result.addAll(files)
            Log.d(TAG, "fetchFolder folder=$folder start=$start got=${files.size} total=$totalCount")
            if (result.size >= totalCount || files.isEmpty()) break
            start += PAGE_SIZE
        }
        return result
    }

    /**
     * Parses a /app/getfilelist response.
     *
     * Shape:
     * {
     *   "result": 0,
     *   "info": [
     *     {
     *       "files": [
     *         { "name": "/loop/20230101_120000_F.ts", "size": "4096", "type": 2, "createtime": 1700000000 }
     *       ],
     *       "count": 42,
     *       "folder": "loop"
     *     }
     *   ]
     * }
     *
     * @return Pair(parsedFiles, totalCount) where totalCount is the full folder count
     */
    private fun parseFileList(
        json: String,
        deviceIp: String,
        isPhoto: Boolean,
    ): Pair<List<MediaFile>, Int> {
        val files = mutableListOf<MediaFile>()
        var totalCount = 0
        try {
            val root     = JSONObject(json)
            val infoArr  = root.optJSONArray("info") ?: return Pair(files, 0)
            if (infoArr.length() == 0) return Pair(files, 0)
            val infoObj  = infoArr.getJSONObject(0)
            totalCount   = infoObj.optInt("count", 0)
            val filesArr = infoObj.optJSONArray("files") ?: return Pair(files, totalCount)

            for (i in 0 until filesArr.length()) {
                val obj  = filesArr.optJSONObject(i) ?: continue
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val type = obj.optInt("type", 2)  // 1=photo, 2=video

                val httpUrl      = "http://$deviceIp$name"
                val thumbnailUrl = "http://$deviceIp/app/getthumbnail?file=$name"
                val bareFileName = name.substringAfterLast('/')

                files.add(
                    MediaFile(
                        path         = name,
                        httpUrl      = httpUrl,
                        thumbnailUrl = thumbnailUrl,
                        name         = bareFileName,
                        isPhoto      = type == 1,
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseFileList error: ${e.message}")
        }
        Log.d(TAG, "parseFileList → ${files.size} files, total=$totalCount")
        return Pair(files, totalCount)
    }
}
