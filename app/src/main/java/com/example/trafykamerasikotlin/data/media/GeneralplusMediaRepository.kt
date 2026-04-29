package com.example.trafykamerasikotlin.data.media

import android.net.Uri
import android.util.Log
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusProtocol
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession
import com.example.trafykamerasikotlin.data.model.MediaFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fetches and manages media files on a GeneralPlus dashcam via the GPSOCKET protocol.
 *
 * All file operations use MODE_PLAYBACK (0x03) commands over TCP port 8081.
 *
 * ─── Fetch flow (single session) ──────────────────────────────────────────────
 *   SetMode(PLAYBACK) → GetFileCount → GetNameList(type=1, start=0) →
 *   for each file: GetThumbnail(fileIndex) → cache JPEG to disk
 *
 * ─── Download flow ─────────────────────────────────────────────────────────────
 *   SetMode(PLAYBACK) → Stop(0x41) → GetRawData(fileIndex) →
 *   receive streaming ACK chunks until RIFF total size reached or NAK/timeout
 *   Note: first chunk may be ≤61440 bytes; partial-chunk EOF only for non-RIFF files.
 *
 * ─── Delete flow ───────────────────────────────────────────────────────────────
 *   SetMode(PLAYBACK) → Stop(0x41) → DeleteFile(fileIndex) → ACK = success, NAK = failure
 *
 * ─── Playback flow (opens RTSP in system player) ──────────────────────────────
 *   SetMode(PLAYBACK) → RestartStreaming → StartPlayback(fileIndex) →
 *   caller opens RTSP_URL in video player
 *
 * ─── GetNameList response structure (confirmed from PCAP) ──────────────────────
 *   Byte 0     : count (uint8) — number of entries that follow
 *   Per entry (13 bytes):
 *     Byte  0  : typeCode (e.g. 'A'=AVI, 'J'=JPG, 'L'=Locked AVI)
 *     Bytes 1–2: fileIndex (uint16 LE)
 *     Byte  3  : year – 2000  (e.g. 0x1a = 26 → 2026)
 *     Byte  4  : month
 *     Byte  5  : day
 *     Byte  6  : hour
 *     Byte  7  : minute
 *     Byte  8  : second
 *     Bytes 9–12: unknown (observed 0x00 0xe8 0x03 0x00)
 *
 * Reference: CamWrapper.java / PCAP PCAPdroid_01_Nis_18_13_49.pcap
 */
class GeneralplusMediaRepository {

    companion object {
        private const val TAG = "Trafy.GPMedia"

        /** Prefix used in MediaFile.path to identify GeneralPlus files. */
        const val GP_PATH_PREFIX = "gp://"

        /** RTSP URL served by the camera after StartPlayback over GPSOCKET. */
        val RTSP_URL = "rtsp://${GeneralplusSession.CAMERA_IP}:8080/?action=stream"

        /** Extracts the file index from a gp:// path. Returns -1 on invalid input. */
        fun fileIndexOf(path: String): Int =
            path.removePrefix(GP_PATH_PREFIX).toIntOrNull() ?: -1
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fetches all media files and caches their thumbnails into [thumbCacheDir].
     * Opens a single session that covers SetMode, GetFileCount, GetNameList, and all thumbnails.
     * Returns (videos, photos) on success, or null if the session fails.
     */
    suspend fun fetchFiles(
        thumbCacheDir: File,
        deviceIp: String,
    ): Pair<List<MediaFile>, List<MediaFile>>? {
        Log.i(TAG, "fetchFiles: $deviceIp")

        return GeneralplusSession.withSession { send, sendPacket, receive ->

            // 1. Switch to Playback mode
            sendPacket(GeneralplusProtocol.buildSetMode(0, GeneralplusProtocol.DEVICE_MODE_PLAYBACK))
            // Consume the SetMode ACK (cmdId=0x00) if it arrives; skip otherwise.
            receive(GeneralplusProtocol.CMD_SET_MODE)

            // 2. GetFileCount
            send(GeneralplusProtocol.MODE_PLAYBACK, GeneralplusProtocol.CMD_PLAYBACK_GET_COUNT)
            val countResp = receive(GeneralplusProtocol.CMD_PLAYBACK_GET_COUNT)
            if (countResp == null || countResp.data.size < 2) {
                Log.e(TAG, "GetFileCount failed")
                return@withSession null
            }
            val fileCount = ByteBuffer.wrap(countResp.data, 0, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            Log.i(TAG, "GetFileCount: $fileCount files")
            if (fileCount == 0) return@withSession Pair(emptyList(), emptyList())

            // 3. GetNameList — all files, starting from index 0
            sendPacket(GeneralplusProtocol.buildGetNameList(0, type = 0x01, startIdx = 0))
            val listResp = receive(GeneralplusProtocol.CMD_PLAYBACK_GET_LIST)
            if (listResp == null || listResp.data.isEmpty()) {
                Log.e(TAG, "GetNameList failed")
                return@withSession null
            }
            val entries = parseNameList(listResp.data)
            Log.i(TAG, "GetNameList: ${entries.size} entries")

            // 4. Build MediaFile objects, fetching thumbnails
            thumbCacheDir.mkdirs()
            val videos = mutableListOf<MediaFile>()
            val photos = mutableListOf<MediaFile>()

            val keepThumbNames = mutableSetOf<String>()
            for (entry in entries) {
                val name      = buildFileName(entry)
                // The dashcam recycles fileIndex values when its SD card loops
                // — without the timestamp suffix, today's clip-at-index-N is
                // served from yesterday's cached thumb. Keying by index +
                // recording timestamp guarantees a fresh fetch whenever the
                // recording at a given index changes.
                val thumbName = "gp_thumb_${entry.fileIndex}_" +
                    "%04d%02d%02d_%02d%02d%02d".format(
                        entry.year, entry.month, entry.day,
                        entry.hour, entry.min, entry.sec) + ".jpg"
                val thumbFile = File(thumbCacheDir, thumbName)
                keepThumbNames += thumbName

                // Fetch and cache thumbnail only if not already on disk.
                // Camera sends: data chunk(s) + empty ACK end-marker (confirmed PCAP [91][92]).
                // We must drain the empty end-marker so it doesn't pollute the next request.
                if (!thumbFile.exists()) {
                    sendPacket(GeneralplusProtocol.buildPlaybackCmd(
                        0, GeneralplusProtocol.CMD_PLAYBACK_GET_THUMB, entry.fileIndex))
                    var thumbData = ByteArray(0)
                    while (true) {
                        val chunk = receive(GeneralplusProtocol.CMD_PLAYBACK_GET_THUMB) ?: break
                        if (chunk.data.isEmpty()) break   // empty ACK = end marker
                        thumbData += chunk.data
                    }
                    if (thumbData.isNotEmpty()) {
                        thumbFile.writeBytes(thumbData)
                        Log.d(TAG, "Thumbnail cached: file=${entry.fileIndex} (${thumbData.size} B)")
                    } else {
                        Log.w(TAG, "GetThumbnail failed for file=${entry.fileIndex}")
                    }
                }

                val isPhoto = entry.typeCode.uppercaseChar() == 'J'
                val media = MediaFile(
                    path         = "$GP_PATH_PREFIX${entry.fileIndex}",
                    httpUrl      = RTSP_URL,
                    thumbnailUrl = Uri.fromFile(thumbFile).toString(),
                    name         = name,
                    isPhoto      = isPhoto,
                )
                if (isPhoto) photos.add(media) else videos.add(media)
                Log.d(TAG, "File ${entry.fileIndex}: $name (photo=$isPhoto)")
            }

            // Prune cached thumbs that no longer correspond to a current
            // recording on the camera. Without this, the cache grows
            // unboundedly as the SD card loops through filenames.
            thumbCacheDir.listFiles()
                ?.filter { it.name.startsWith("gp_thumb_") && it.name !in keepThumbNames }
                ?.forEach { stale ->
                    if (stale.delete()) Log.d(TAG, "Pruned stale thumb: ${stale.name}")
                }

            Log.i(TAG, "fetchFiles complete: ${videos.size} videos, ${photos.size} photos")
            Pair(videos, photos)
        }
    }

    /**
     * Downloads a file via GetRawData streaming and writes it to [destFile].
     * Detects end-of-file from the RIFF header (AVI/MOV) or partial chunk heuristic.
     * [onProgress] is called with 0–100 each time the integer percentage advances.
     * Returns true if at least some data was written successfully.
     */
    suspend fun downloadFile(
        deviceIp: String,
        fileIndex: Int,
        destFile: File,
        onProgress: ((receivedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean {
        Log.i(TAG, "downloadFile: fileIndex=$fileIndex → ${destFile.name}")

        val success = GeneralplusSession.withSession { _, sendPacket, receive ->
            // Switch to Playback mode
            sendPacket(GeneralplusProtocol.buildSetMode(0, GeneralplusProtocol.DEVICE_MODE_PLAYBACK))
            receive(GeneralplusProtocol.CMD_SET_MODE)

            // Stop any active RTSP stream before GetRawData (confirmed from PCAP [97→98→99])
            sendPacket(GeneralplusProtocol.buildPlaybackStop(0))
            receive(GeneralplusProtocol.CMD_PLAYBACK_STOP)

            // Start streaming the raw file data
            sendPacket(GeneralplusProtocol.buildPlaybackCmd(
                0, GeneralplusProtocol.CMD_PLAYBACK_GET_RAW, fileIndex))

            var totalExpected      = Long.MAX_VALUE
            var totalReceived      = 0L
            var firstChunk        = true
            var lastReportedPct   = -1

            FileOutputStream(destFile).use { out ->
                while (totalReceived < totalExpected) {
                    currentCoroutineContext().ensureActive()
                    val chunk = receive(GeneralplusProtocol.CMD_PLAYBACK_GET_RAW) ?: break
                    out.write(chunk.data)
                    totalReceived += chunk.data.size

                    // Parse RIFF file size from the first chunk header
                    if (firstChunk) {
                        firstChunk = false
                        if (chunk.data.size >= 8 &&
                            chunk.data[0] == 0x52.toByte() && chunk.data[1] == 0x49.toByte() &&
                            chunk.data[2] == 0x46.toByte() && chunk.data[3] == 0x46.toByte()) {
                            // RIFF: bytes 4-7 = data size (LE uint32). Total = data size + 8.
                            val riffDataSize = ByteBuffer.wrap(chunk.data, 4, 4)
                                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            totalExpected = riffDataSize + 8
                            Log.d(TAG, "RIFF detected: totalExpected=$totalExpected bytes")
                        }
                    }

                    // Emit progress only when the integer percentage advances (max 100 callbacks).
                    if (totalExpected != Long.MAX_VALUE) {
                        val pct = ((totalReceived * 100L) / totalExpected).toInt().coerceIn(0, 100)
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            onProgress?.invoke(totalReceived, totalExpected)
                        }
                    }

                    Log.v(TAG, "Chunk: ${chunk.data.size} B, total=$totalReceived / $totalExpected")

                    // Partial chunk = EOF fallback only when no RIFF size is known.
                    // PCAP shows the first real chunk can be 61390 B (not 61440) and 50-byte
                    // packets appear mid-stream, so this heuristic is only safe as a last resort.
                    if (totalExpected == Long.MAX_VALUE &&
                        chunk.data.size < GeneralplusProtocol.RAW_DATA_CHUNK_SIZE) break
                }
            }

            val ok = totalReceived > 0
            if (ok) Log.i(TAG, "downloadFile: done — $totalReceived bytes")
            else    Log.e(TAG, "downloadFile: no data received")
            ok
        }
        return success == true
    }

    /**
     * Deletes a file from the camera's SD card.
     * Returns true on ACK from camera; false on NAK or session failure.
     */
    suspend fun deleteFile(deviceIp: String, fileIndex: Int): Boolean {
        Log.i(TAG, "deleteFile: fileIndex=$fileIndex")

        val success = GeneralplusSession.withSession { _, sendPacket, receive ->
            sendPacket(GeneralplusProtocol.buildSetMode(0, GeneralplusProtocol.DEVICE_MODE_PLAYBACK))
            receive(GeneralplusProtocol.CMD_SET_MODE)

            // Stop any active stream so the file is not locked (PCAP [154] NAK when streaming)
            sendPacket(GeneralplusProtocol.buildPlaybackStop(0))
            receive(GeneralplusProtocol.CMD_PLAYBACK_STOP)

            sendPacket(GeneralplusProtocol.buildPlaybackCmd(
                0, GeneralplusProtocol.CMD_PLAYBACK_DELETE, fileIndex))
            val ack = receive(GeneralplusProtocol.CMD_PLAYBACK_DELETE)
            val ok  = ack != null
            if (ok) Log.i(TAG, "deleteFile: ACK — success")
            else    Log.w(TAG, "deleteFile: NAK or no ACK")
            ok
        }
        return success == true
    }

    /**
     * Prepares the camera for RTSP playback of a specific file.
     *
     * Sequence confirmed from PCAP (PCAPdroid_01_Nis_21_54_57.pcap):
     *   1. SetMode(PLAYBACK) — ensure the camera is in playback mode (new session)
     *   2. RestartStreaming   — reinitialise the camera's RTSP server for file playback
     *   3. StartPlayback(fileIndex) — direct the stream to the requested file
     *
     * IMPORTANT: No Stop(0x41) before RestartStreaming — the PCAP shows the Viidure app
     * does NOT send Stop before playback. Sending Stop causes RestartStreaming to NAK.
     *
     * The GPSOCKET TCP session is held open (not closed) because the camera stops
     * the RTSP stream when the control connection drops.  [exitPlayback] releases it.
     *
     * Returns true if commands were sent (caller should then open [RTSP_URL]).
     */
    suspend fun preparePlayback(deviceIp: String, fileIndex: Int): Boolean {
        Log.i(TAG, "preparePlayback: fileIndex=$fileIndex")

        val success = GeneralplusSession.withSession(holdOpen = true) { send, sendPacket, receive ->
            // 1. Ensure camera is in Playback mode (new TCP session; mode may not persist)
            sendPacket(GeneralplusProtocol.buildSetMode(0, GeneralplusProtocol.DEVICE_MODE_PLAYBACK))
            receive(GeneralplusProtocol.CMD_SET_MODE)

            // 2. Restart the streaming server (switches RTSP from live to file playback)
            send(GeneralplusProtocol.MODE_GENERAL, GeneralplusProtocol.CMD_RESTART_STREAMING)
            receive(GeneralplusProtocol.CMD_RESTART_STREAMING)

            // 3. Start streaming the requested file
            sendPacket(GeneralplusProtocol.buildPlaybackCmd(
                0, GeneralplusProtocol.CMD_PLAYBACK_START, fileIndex))
            val ack = receive(GeneralplusProtocol.CMD_PLAYBACK_START)
            if (ack == null) Log.w(TAG, "preparePlayback: no ACK — camera may already be streaming")
            else             Log.i(TAG, "preparePlayback: ACK — camera streaming file $fileIndex")
            true  // treat as success regardless of ACK
        }
        return success == true
    }

    /**
     * Restores the camera to recording mode after leaving the Media screen.
     *
     * Uses the held playback session if available (i.e. the user played a file),
     * otherwise opens a fresh session. Always releases the held session at the end.
     *
     * Full sequence confirmed from PCAP [164-179] and GoplusPlayerActivity.java:
     *   1. Stop(0x41)                  — stop any active RTSP file stream
     *   2. SetMode(DEVICE_MODE_RECORD) — switch device back to record mode
     *   3. MODE_RECORD cmd=0x00        — explicitly start recording (PCAP [172])
     *   4. RestartStreaming             — restart the live RTSP stream
     */
    suspend fun exitPlayback(deviceIp: String) {
        Log.i(TAG, "exitPlayback")

        // Use the held playback session if available; otherwise open a fresh one.
        val ran = GeneralplusSession.onHeldSession { send, sendPacket, receive ->
            sendExitCommands(send, sendPacket, receive)
        }
        if (ran == null) {
            GeneralplusSession.withSession { send, sendPacket, receive ->
                sendExitCommands(send, sendPacket, receive)
            }
        }
        GeneralplusSession.releaseHeldSession()
    }

    private fun sendExitCommands(
        send: (Byte, Byte) -> Unit,
        sendPacket: (ByteArray) -> Unit,
        receive: (Byte) -> GeneralplusProtocol.Response?,
    ) {
        // 1. Stop active RTSP stream
        sendPacket(GeneralplusProtocol.buildPlaybackStop(0))
        receive(GeneralplusProtocol.CMD_PLAYBACK_STOP)

        // 2. Switch to Record device mode
        sendPacket(GeneralplusProtocol.buildSetMode(0, GeneralplusProtocol.DEVICE_MODE_RECORD))
        receive(GeneralplusProtocol.CMD_SET_MODE)

        // 3. Start recording (MODE_RECORD=0x01, cmd=0x00 — 12-byte basic command)
        send(GeneralplusProtocol.MODE_RECORD, 0x00)
        receive(0x00)

        // 4. Restart live RTSP stream
        send(GeneralplusProtocol.MODE_GENERAL, GeneralplusProtocol.CMD_RESTART_STREAMING)
        receive(GeneralplusProtocol.CMD_RESTART_STREAMING)
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Parsed entry from a GetNameList response. */
    data class GpFileEntry(
        val typeCode: Char,
        val fileIndex: Int,
        val year: Int, val month: Int, val day: Int,
        val hour: Int, val min: Int, val sec: Int,
    )

    /**
     * Parses the binary GetNameList response payload.
     * Format: count(1B) + entries(13B each).
     */
    private fun parseNameList(data: ByteArray): List<GpFileEntry> {
        if (data.isEmpty()) return emptyList()
        val count   = data[0].toInt() and 0xFF
        val entries = mutableListOf<GpFileEntry>()
        var offset  = 1
        repeat(count) {
            if (offset + 13 > data.size) return@repeat
            val entry = GpFileEntry(
                typeCode  = (data[offset].toInt() and 0xFF).toChar(),
                fileIndex = ByteBuffer.wrap(data, offset + 1, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF,
                year      = (data[offset + 3].toInt() and 0xFF) + 2000,
                month     = data[offset + 4].toInt() and 0xFF,
                day       = data[offset + 5].toInt() and 0xFF,
                hour      = data[offset + 6].toInt() and 0xFF,
                min       = data[offset + 7].toInt() and 0xFF,
                sec       = data[offset + 8].toInt() and 0xFF,
            )
            entries.add(entry)
            Log.d(TAG, "  entry: type=${entry.typeCode} idx=${entry.fileIndex} " +
                "${entry.year}-${entry.month.toString().padStart(2,'0')}-${entry.day.toString().padStart(2,'0')} " +
                "${entry.hour.toString().padStart(2,'0')}:${entry.min.toString().padStart(2,'0')}:${entry.sec.toString().padStart(2,'0')}")
            offset += 13
        }
        return entries
    }

    /**
     * Constructs a filename like "2026_04_01_181126.avi" from a file entry.
     * File extension is inferred from the type code byte.
     */
    private fun buildFileName(entry: GpFileEntry): String {
        val ext = when (entry.typeCode.uppercaseChar()) {
            'A', 'V'      -> "avi"
            'J'           -> "jpg"
            'L', 'K'      -> "avi"   // Locked/event recording
            'S', 'O'      -> "avi"   // SOS recording
            'T'           -> "mov"   // Time-lapse
            'M', 'C', 'P' -> "mov"   // Various MOV types
            'm', 'l', 's', 't' -> "mp4"  // MP4 variants
            else          -> "avi"
        }
        return "%04d_%02d_%02d_%02d%02d%02d.%s".format(
            entry.year, entry.month, entry.day,
            entry.hour, entry.min, entry.sec,
            ext,
        )
    }
}
