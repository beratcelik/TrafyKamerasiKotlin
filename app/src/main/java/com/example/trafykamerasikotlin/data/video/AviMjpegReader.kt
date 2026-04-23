package com.example.trafykamerasikotlin.data.video

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming reader for AVI containers that carry MJPEG video — which is
 * exactly what GeneralPlus dashcams record to their SD card. Each video
 * chunk in an MJPEG-inside-AVI file is a self-contained JPEG, so all we
 * need to do is parse enough of the AVI structure to find the movie data
 * list, then iterate the `##dc` chunks and hand their raw bytes back to
 * the caller — no codec needed.
 *
 * Why not [android.media.MediaExtractor]? It works on some devices for
 * AVI but not all, and its MJPEG codec support is spotty below API 29.
 * Parsing AVI ourselves is <200 lines and completely deterministic.
 *
 * AVI 101 (RIFF dialect):
 *  - File starts with `RIFF <size> AVI ` header (12 bytes).
 *  - Under that are LIST chunks. `hdrl` carries headers (avih, strh, strf).
 *    `movi` is where the frame data lives.
 *  - Every chunk: 4-byte FourCC tag + 4-byte LE size + data, padded to an
 *    even byte boundary.
 *  - Inside `movi`, video frames are tagged `##dc` (compressed,
 *    e.g. `00dc` for stream 0) or `##db` (uncompressed). Audio is `##wb`.
 *  - For MJPEG, each `##dc` chunk's data is a complete JPEG starting with
 *    `FF D8` and ending with `FF D9`.
 *
 * Frame rate: read from the `avih` MainAVIHeader.
 *   microsecPerFrame = dwMicroSecPerFrame (u32 LE, first field).
 *
 * Not thread-safe; caller owns the RandomAccessFile lifetime via [close].
 */
class AviMjpegReader(private val file: File) : AutoCloseable {

    val width:   Int
    val height:  Int
    val microsPerFrame: Long
    val totalFrames:    Long

    private val raf = RandomAccessFile(file, "r")

    /**
     * All `movi` LIST bodies found in the file — one per RIFF chunk. Most
     * AVI 1.0 files have a single movi. OpenDML AVI 2.0 (≥1 GB, also used
     * by some dashcams by convention regardless of size) splits frames
     * across multiple `RIFF AVIX` chunks, each with its own movi. Iterating
     * in file order reconstructs the continuous stream.
     */
    private data class MoviRange(val offset: Long, val endOffset: Long)
    private val moviRanges = mutableListOf<MoviRange>()

    init {
        val header = parseHeaders()
        width  = header.width
        height = header.height
        microsPerFrame = header.microsPerFrame
        totalFrames    = header.totalFrames
        require(moviRanges.isNotEmpty()) { "AVI is missing a 'movi' LIST — not a valid AVI file" }
    }

    /**
     * Iterate video frames lazily across every `movi` body in the file,
     * in file order. Handles both AVI 1.0 (single movi) and OpenDML AVI
     * 2.0 (one movi per RIFF AVIX chunk), as well as `LIST rec ` groupings
     * inside movi (where each rec wraps one video + optional audio frame).
     *
     * The iterator walks movi body bytes directly: every time it sees a
     * `LIST` it steps INTO the list (no skip-over), every `##dc` tag is a
     * video frame, other chunks are skipped. Chunks are padded to an even
     * byte boundary.
     */
    fun frames(): Sequence<AviFrame> = sequence {
        var frameIdx = 0L
        // Pass 1: walk the AVI chunk structure. Fast path — works for
        // well-formed files.
        var chunkParseFailed = false
        var chunkFailureOffset = -1L
        for (range in moviRanges) {
            raf.seek(range.offset)
            Log.d(TAG, "frames: iterating movi range [${range.offset}..${range.endOffset})")
            while (raf.filePointer < range.endOffset) {
                val chunkStart = raf.filePointer
                val tag = readFourCC()
                if (tag == null) {
                    Log.w(TAG, "frames: readFourCC=null at $chunkStart, stopping chunk-parse")
                    chunkParseFailed = true
                    chunkFailureOffset = chunkStart
                    break
                }
                val size = readU32LE()
                val looksValid = tag.all { it in ' '..'~' }
                if (!looksValid) {
                    Log.w(TAG, "frames: garbage chunk tag at $chunkStart — switching to JPEG-marker scan for the rest")
                    chunkParseFailed = true
                    chunkFailureOffset = chunkStart
                    break
                }
                if (tag == "LIST") {
                    val listType = readFourCC() ?: break
                    if (frameIdx < 3L) Log.d(TAG, "  [$chunkStart] LIST '$listType' size=$size — descend")
                    continue
                }
                val isVideoCompressed = tag.length == 4 && tag.substring(2) == "dc"
                if (isVideoCompressed) {
                    if (size < 0 || size > range.endOffset - raf.filePointer) {
                        Log.w(TAG, "frames: '$tag' chunk size=$size at $chunkStart exceeds movi bounds — switching to JPEG-marker scan")
                        chunkParseFailed = true
                        chunkFailureOffset = chunkStart
                        break
                    }
                    val jpeg = ByteArray(size.toInt())
                    raf.readFully(jpeg)
                    if (size % 2 == 1L) raf.skipBytes(1)
                    yield(AviFrame(
                        jpeg               = jpeg,
                        presentationTimeUs = frameIdx * microsPerFrame,
                        frameIndex         = frameIdx,
                    ))
                    frameIdx++
                } else {
                    raf.skipBytes(size.toInt())
                    if (size % 2 == 1L) raf.skipBytes(1)
                }
            }
            if (chunkParseFailed) break
        }

        // Pass 2 — JPEG SOI scan. Runs when the chunk parser hit garbage
        // before exhausting the movi. Dashcam AVI files routinely lie
        // about chunk sizes / run out of valid framing mid-file, but the
        // JPEG stream itself is self-synchronising: every frame starts
        // with FF D8 and ends with FF D9. Scan for those markers across
        // the remaining file bytes and yield every JPEG we find.
        if (chunkParseFailed) {
            val scanStart = chunkFailureOffset
            val scanEnd   = raf.length()
            Log.i(TAG, "frames: starting JPEG-marker scan from $scanStart..$scanEnd")
            var found = scanForJpegsFrom(scanStart, scanEnd) { jpeg ->
                yield(AviFrame(
                    jpeg               = jpeg,
                    presentationTimeUs = frameIdx * microsPerFrame,
                    frameIndex         = frameIdx,
                ))
                frameIdx++
            }
            Log.i(TAG, "frames: JPEG-scan recovered $found additional frames")
        }

        Log.i(TAG, "frames: iteration done, yielded $frameIdx frames total")
    }

    /**
     * Fallback frame finder: scans a byte range for JPEG SOI (FF D8 FF)
     * markers and yields each complete JPEG (from SOI to the following
     * EOI or next SOI boundary) to [emit]. Returns the number of frames
     * emitted.
     *
     * Implementation reads in 256 KB windows with a small overlap so a
     * marker straddling a window boundary isn't missed.
     */
    private inline fun scanForJpegsFrom(
        start: Long,
        end:   Long,
        emit:  (ByteArray) -> Unit,
    ): Int {
        val windowSize = 256 * 1024
        val overlap    = 6              // FF D8 FF ?? is 4 bytes; overlap is paranoia
        var cursor     = start
        var emitted    = 0

        // State across windows: when we see an SOI, note its position.
        // On the next SOI (or at EOF), we slice [soiPos .. next) as a frame.
        var pendingSoi: Long = -1L

        val buf = ByteArray(windowSize)
        while (cursor < end) {
            raf.seek(cursor)
            val toRead = minOf(buf.size.toLong(), end - cursor).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n <= 0) break

            var i = 0
            val scanEnd = n - 2  // need 3 bytes for FF D8 FF check
            while (i < scanEnd) {
                if (buf[i] == 0xFF.toByte() &&
                    buf[i + 1] == 0xD8.toByte() &&
                    buf[i + 2] == 0xFF.toByte()) {
                    val absPos = cursor + i
                    if (pendingSoi >= 0L && absPos > pendingSoi) {
                        // Emit [pendingSoi, absPos)
                        val frame = readRange(pendingSoi, absPos)
                        if (frame != null) { emit(frame); emitted++ }
                    }
                    pendingSoi = absPos
                    i += 3
                } else {
                    i++
                }
            }
            // Advance cursor, keeping an overlap so a marker straddling
            // the boundary isn't missed.
            cursor += (n - overlap).coerceAtLeast(1)
        }
        // Emit the last pending frame, capped at end.
        if (pendingSoi >= 0L && pendingSoi < end) {
            val frame = readRange(pendingSoi, end)
            if (frame != null) { emit(frame); emitted++ }
        }
        return emitted
    }

    private fun readRange(from: Long, to: Long): ByteArray? {
        val len = (to - from).toInt()
        if (len <= 0 || len > 32 * 1024 * 1024) return null  // 32 MB sanity cap
        raf.seek(from)
        val buf = ByteArray(len)
        raf.readFully(buf)
        return buf
    }

    override fun close() {
        try { raf.close() } catch (_: Throwable) {}
    }

    // ── parsing internals ──────────────────────────────────────────────────

    private data class Header(
        val width: Int,
        val height: Int,
        val microsPerFrame: Long,
        val totalFrames: Long,
    )

    private fun parseHeaders(): Header {
        var width = 0
        var height = 0
        var microsPerFrame: Long = 33_333L  // 30 fps default
        var totalFrames: Long = 0L

        val fileLen = raf.length()
        raf.seek(0)

        // Outer loop — walk every RIFF chunk in the file. AVI 1.0 has one
        // ("RIFF ... AVI "); OpenDML AVI 2.0 has one primary ("RIFF AVI ")
        // followed by N secondary ("RIFF AVIX") chunks, each with its own
        // movi. Dashcams sometimes emit AVIX even below the 1 GB threshold.
        while (raf.filePointer < fileLen) {
            val outerStart = raf.filePointer
            val riff = readFourCC() ?: break
            if (riff != "RIFF") {
                Log.w(TAG, "non-RIFF chunk at $outerStart: '$riff' — stopping")
                break
            }
            val chunkSize = readU32LE()   // size covers type + body
            val chunkType = readFourCC() ?: break
            if (chunkType != "AVI " && chunkType != "AVIX") {
                Log.w(TAG, "unknown RIFF type '$chunkType' at $outerStart — skipping")
                raf.seek(outerStart + 8L + chunkSize)
                continue
            }
            val riffEnd = outerStart + 8L + chunkSize

            // Inner loop — walk this RIFF's top-level chunks (hdrl, movi, idx1, …).
            while (raf.filePointer < riffEnd) {
                val chunkStart = raf.filePointer
                val tag  = readFourCC() ?: break
                val size = readU32LE()
                val payloadStart = raf.filePointer

                when (tag) {
                    "LIST" -> {
                        val listType = readFourCC() ?: break
                        when (listType) {
                            "hdrl" -> {
                                val hdrlEnd = payloadStart + size
                                while (raf.filePointer < hdrlEnd) {
                                    val sub = readFourCC() ?: break
                                    val subSize = readU32LE()
                                    val subStart = raf.filePointer
                                    when (sub) {
                                        "avih" -> {
                                            val buf = ByteArray(subSize.toInt())
                                            raf.readFully(buf)
                                            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                                            microsPerFrame = (bb.int.toLong() and 0xFFFFFFFFL)
                                            bb.position(bb.position() + 12)
                                            totalFrames = (bb.int.toLong() and 0xFFFFFFFFL)
                                            bb.position(32)
                                            width  = bb.int
                                            height = bb.int
                                        }
                                        else -> raf.skipBytes(subSize.toInt())
                                    }
                                    raf.seek(subStart + subSize)
                                    if (subSize % 2 == 1L) raf.skipBytes(1)
                                }
                            }
                            "movi" -> {
                                val moviStart = payloadStart + 4   // after the "movi" type
                                val moviEnd   = payloadStart + size
                                moviRanges += MoviRange(moviStart, moviEnd)
                            }
                            else -> {}  // INFO, odml, etc.
                        }
                    }
                    else -> {}  // idx1, JUNK, etc.
                }
                raf.seek(chunkStart + 8L + size)
                if (size % 2 == 1L) raf.skipBytes(1)
            }
            // Move to the next RIFF chunk (if any).
            raf.seek(riffEnd)
        }

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "AVI parsed but dimensions not found; defaulting to 1920x1080")
            return Header(1920, 1080, microsPerFrame, totalFrames)
        }
        val moviBytes = moviRanges.sumOf { it.endOffset - it.offset }
        Log.i(TAG, "AVI parsed: ${width}x${height}, µs/frame=$microsPerFrame, hdrTotalFrames=$totalFrames, " +
                "moviRanges=${moviRanges.size} (totalBytes=$moviBytes)")
        return Header(width, height, microsPerFrame, totalFrames)
    }

    private fun readFourCC(): String? {
        val buf = ByteArray(4)
        val n = raf.read(buf)
        if (n != 4) return null
        return String(buf, Charsets.US_ASCII)
    }

    /** Read 4-byte little-endian unsigned int as a Long (avoids sign issues). */
    private fun readU32LE(): Long {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return (buf[0].toLong() and 0xFF) or
               ((buf[1].toLong() and 0xFF) shl 8) or
               ((buf[2].toLong() and 0xFF) shl 16) or
               ((buf[3].toLong() and 0xFF) shl 24)
    }

    companion object { private const val TAG = "Trafy.AviReader" }
}

data class AviFrame(
    val jpeg:               ByteArray,
    val presentationTimeUs: Long,
    val frameIndex:         Long,
)
