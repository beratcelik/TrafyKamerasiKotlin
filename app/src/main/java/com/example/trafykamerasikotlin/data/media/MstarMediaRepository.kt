package com.example.trafykamerasikotlin.data.media

import android.util.Log
import android.util.Xml
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Lists / deletes recordings for MStar / Hime3 devices (Trafy Tres).
 *
 * The album is served by the same `Config.cgi` endpoint, with the `action`
 * naming the sensor and `property` the file category:
 *   action = dir (front) | thirddir (inside) | reardir (rear) | sddir (SD)
 *   property = Normal | Event | Parking | Photo
 *   &format=all&count=<n>&from=<offset>
 *
 * Response is XML: `<Normal><amount>N</amount> …entries… </Normal>`. Each entry
 * carries name / size / time / format / attr (+ optional GPSPATH), matching the
 * OEM's jsonToFileItem. Streaming/thumbnail URLs are derived from the name:
 *   file  → http://<ip>/<name>
 *   thumb → http://<ip>/thumb/<name>
 * Delete: `action=del&property=<name>`.
 *
 * The cam keeps recording while the album is browsed (no stop/register
 * handshake — the HiDVR path's stopRecording/registerClient are the very calls
 * that 404'd on MStar and left the tab empty).
 *
 * Reference: MstartDvrProtocol (getFileList / jsonToFileItem / MstarDevConst)
 * in golook-jadx, cross-checked against a live Waycam album logcat trace.
 */
class MstarMediaRepository {

    companion object {
        private const val TAG = "Trafy.MstarMedia"
        private const val PAGE_SIZE = 50

        /** action → camera channel label ([MediaFile.cameraHint]). */
        private val CAMERA_ACTIONS = listOf(
            "dir"      to "Front",
            "thirddir" to "Inside",
            "reardir"  to "Back",
        )
        private val VIDEO_CATEGORIES = listOf("Normal", "Event", "Parking")
        private const val PHOTO_CATEGORY = "Photo"
    }

    suspend fun fetchVideos(deviceIp: String): List<MediaFile> =
        fetch(deviceIp, VIDEO_CATEGORIES, isPhoto = false)

    suspend fun fetchPhotos(deviceIp: String): List<MediaFile> =
        fetch(deviceIp, listOf(PHOTO_CATEGORY), isPhoto = true)

    /**
     * Deletes a file. The cam expects the on-device path (the `name` field,
     * which we stored back into [MediaFile.path]).
     */
    suspend fun deleteFile(deviceIp: String, file: MediaFile): Boolean {
        val prop = file.path.trimStart('/')
        val ok = DashcamHttpClient.probe(
            "http://$deviceIp/cgi-bin/Config.cgi?action=del&property=$prop"
        )
        Log.i(TAG, "deleteFile ${file.name} → $ok")
        return ok
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private suspend fun fetch(
        deviceIp: String,
        categories: List<String>,
        isPhoto: Boolean,
    ): List<MediaFile> {
        val result = mutableListOf<MediaFile>()
        for ((action, cameraHint) in CAMERA_ACTIONS) {
            for (category in categories) {
                val url = "http://$deviceIp/cgi-bin/Config.cgi?action=$action" +
                    "&property=$category&format=all&count=$PAGE_SIZE&from=0"
                val body = DashcamHttpClient.get(url)
                if (body == null) {
                    Log.w(TAG, "$action/$category → no response")
                    continue
                }
                val entries = parseFileList(body, deviceIp, cameraHint, isPhoto)
                Log.d(TAG, "$action/$category → ${entries.size} files")
                result += entries
            }
        }
        // Newest first, by the numeric timestamp in the name/time field.
        val sorted = result.sortedByDescending { it.mtimeEpoch ?: 0L }
        Log.i(TAG, "fetch(isPhoto=$isPhoto) → ${sorted.size} files")
        return sorted
    }

    /**
     * Parses `<Category><amount>N</amount> …entries… </Category>`.
     *
     * Robust to both entry shapes (attributes on the entry element, or child
     * `<name>/<size>/…` elements) since we can't see a non-empty response from
     * an empty card: any element directly under the root that isn't `<amount>`
     * is treated as a file entry, and its `name` is read from either its
     * attributes or its child text.
     */
    private fun parseFileList(
        xml: String,
        deviceIp: String,
        cameraHint: String,
        isPhoto: Boolean,
    ): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var depth = 0
            var inEntry = false
            var entryDepth = -1
            var fields = mutableMapOf<String, String>()
            var childTag: String? = null
            val text = StringBuilder()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val tag = parser.name
                        when {
                            // depth 1 = root (<Normal> etc); depth 2 = entry OR <amount>.
                            depth == 2 && !tag.equals("amount", ignoreCase = true) -> {
                                inEntry = true
                                entryDepth = depth
                                fields = mutableMapOf()
                                // attribute-style entry
                                for (i in 0 until parser.attributeCount) {
                                    fields[parser.getAttributeName(i).lowercase()] =
                                        parser.getAttributeValue(i)
                                }
                            }
                            inEntry && depth == entryDepth + 1 -> {
                                childTag = tag.lowercase()
                                text.setLength(0)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (childTag != null) text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name
                        if (inEntry && childTag != null && depth == entryDepth + 1) {
                            val v = text.toString().trim()
                            if (v.isNotEmpty()) fields[childTag!!] = v
                            childTag = null
                        }
                        if (inEntry && depth == entryDepth && !tag.equals("amount", ignoreCase = true)) {
                            buildFile(fields, deviceIp, cameraHint, isPhoto)?.let { files += it }
                            inEntry = false
                            entryDepth = -1
                        }
                        depth--
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseFileList error: ${e.message}")
        }
        return files
    }

    private fun buildFile(
        fields: Map<String, String>,
        deviceIp: String,
        cameraHint: String,
        isPhoto: Boolean,
    ): MediaFile? {
        val rawName = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val name = rawName.trimStart('/')
        val bareName = name.substringAfterLast('/')
        val size = fields["size"]?.toLongOrNull()
        val mtime = fields["time"]?.let { parseTimeToEpoch(it) }
        return MediaFile(
            path         = rawName,
            httpUrl      = "http://$deviceIp/$name",
            thumbnailUrl = "http://$deviceIp/thumb/$name",
            name         = bareName,
            isPhoto      = isPhoto,
            sizeBytes    = size,
            cameraHint   = cameraHint,
            mtimeEpoch   = mtime,
        )
    }

    /** "2026-07-18 09:00:00" → epoch seconds (best effort; 0 on parse failure). */
    private fun parseTimeToEpoch(time: String): Long? {
        val digits = time.filter { it.isDigit() }
        // yyyyMMddHHmmss → a monotonic sortable key; exact TZ isn't needed for ordering.
        return digits.toLongOrNull()
    }
}
