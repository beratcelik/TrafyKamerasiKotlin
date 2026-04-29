package com.example.trafykamerasikotlin.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Network
import android.util.Log
import android.view.Surface
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Lightweight MJPEG-over-RTSP player for GeneralPlus dashcam file playback.
 *
 * The camera streams recorded files as MJPEG over RTP (RFC 2435, payload type 26)
 * via its RTSP server on port 8080.  IjkPlayer's FFmpeg build doesn't include the
 * MJPEG decoder, so this class implements the full pipeline:
 *
 *   RTSP signaling (TCP) → RTP receive (UDP) → RFC 2435 JPEG reassembly →
 *   BitmapFactory decode → Surface render
 *
 * Usage:
 *   val player = MjpegRtspPlayer(surface, network)
 *   player.start("rtsp://192.168.25.1:8080/?action=stream")
 *   // ... user watches video ...
 *   player.stop()
 */
class MjpegRtspPlayer(
    private val surface: Surface,
    private val network: Network?,
) {
    companion object {
        private const val TAG = "Trafy.MjpegRtsp"
        private const val RTP_HEADER_SIZE = 12
        private const val JPEG_HEADER_SIZE = 8
        private const val UDP_BUFFER_SIZE = 65536

        // ── Standard JPEG tables (RFC 2435 / JPEG spec Annex K) ───────────────

        @Suppress("MagicNumber")
        private val JPEG_LUMA_QUANTIZER = byteArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109.toByte(), 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99,
        )

        @Suppress("MagicNumber")
        private val JPEG_CHROMA_QUANTIZER = byteArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99,
            18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
        )

        // Standard Huffman tables from JPEG spec Annex K
        private val DC_LUMA_BITS = byteArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val DC_LUMA_VALS = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

        private val DC_CHROMA_BITS = byteArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        private val DC_CHROMA_VALS = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

        private val AC_LUMA_BITS = byteArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
        private val AC_LUMA_VALS = byteArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
            0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81.toByte(), 0x91.toByte(), 0xa1.toByte(), 0x08,
            0x23, 0x42, 0xb1.toByte(), 0xc1.toByte(), 0x15, 0x52, 0xd1.toByte(), 0xf0.toByte(),
            0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0a, 0x16,
            0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
            0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
            0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
            0x7a, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(),
            0x88.toByte(), 0x89.toByte(), 0x8a.toByte(), 0x92.toByte(), 0x93.toByte(),
            0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(),
            0x99.toByte(), 0x9a.toByte(), 0xa2.toByte(), 0xa3.toByte(), 0xa4.toByte(),
            0xa5.toByte(), 0xa6.toByte(), 0xa7.toByte(), 0xa8.toByte(), 0xa9.toByte(),
            0xaa.toByte(), 0xb2.toByte(), 0xb3.toByte(), 0xb4.toByte(), 0xb5.toByte(),
            0xb6.toByte(), 0xb7.toByte(), 0xb8.toByte(), 0xb9.toByte(), 0xba.toByte(),
            0xc2.toByte(), 0xc3.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xc6.toByte(),
            0xc7.toByte(), 0xc8.toByte(), 0xc9.toByte(), 0xca.toByte(), 0xd2.toByte(),
            0xd3.toByte(), 0xd4.toByte(), 0xd5.toByte(), 0xd6.toByte(), 0xd7.toByte(),
            0xd8.toByte(), 0xd9.toByte(), 0xda.toByte(), 0xe1.toByte(), 0xe2.toByte(),
            0xe3.toByte(), 0xe4.toByte(), 0xe5.toByte(), 0xe6.toByte(), 0xe7.toByte(),
            0xe8.toByte(), 0xe9.toByte(), 0xea.toByte(), 0xf1.toByte(), 0xf2.toByte(),
            0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf8.toByte(), 0xf9.toByte(), 0xfa.toByte(),
        )

        private val AC_CHROMA_BITS = byteArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        private val AC_CHROMA_VALS = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
            0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81.toByte(), 0x08, 0x14, 0x42, 0x91.toByte(),
            0xa1.toByte(), 0xb1.toByte(), 0xc1.toByte(), 0x09, 0x23, 0x33, 0x52, 0xf0.toByte(),
            0x15, 0x62, 0x72, 0xd1.toByte(), 0x0a, 0x16, 0x24, 0x34,
            0xe1.toByte(), 0x25, 0xf1.toByte(), 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38,
            0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
            0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
            0x79, 0x7a, 0x82.toByte(), 0x83.toByte(), 0x84.toByte(), 0x85.toByte(),
            0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8a.toByte(),
            0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(),
            0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9a.toByte(), 0xa2.toByte(),
            0xa3.toByte(), 0xa4.toByte(), 0xa5.toByte(), 0xa6.toByte(), 0xa7.toByte(),
            0xa8.toByte(), 0xa9.toByte(), 0xaa.toByte(), 0xb2.toByte(), 0xb3.toByte(),
            0xb4.toByte(), 0xb5.toByte(), 0xb6.toByte(), 0xb7.toByte(), 0xb8.toByte(),
            0xb9.toByte(), 0xba.toByte(), 0xc2.toByte(), 0xc3.toByte(), 0xc4.toByte(),
            0xc5.toByte(), 0xc6.toByte(), 0xc7.toByte(), 0xc8.toByte(), 0xc9.toByte(),
            0xca.toByte(), 0xd2.toByte(), 0xd3.toByte(), 0xd4.toByte(), 0xd5.toByte(),
            0xd6.toByte(), 0xd7.toByte(), 0xd8.toByte(), 0xd9.toByte(), 0xda.toByte(),
            0xe2.toByte(), 0xe3.toByte(), 0xe4.toByte(), 0xe5.toByte(), 0xe6.toByte(),
            0xe7.toByte(), 0xe8.toByte(), 0xe9.toByte(), 0xea.toByte(), 0xf2.toByte(),
            0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf8.toByte(), 0xf9.toByte(), 0xfa.toByte(),
        )
    }

    @Volatile private var running = false
    private var rtspSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var cseq = 0
    private var sessionId: String? = null
    private var receiveThread: Thread? = null
    var onFirstFrame: (() -> Unit)? = null

    /**
     * Optional per-frame tap. Invoked synchronously on the receive thread
     * with the decoded [Bitmap] BEFORE it is drawn to the surface and
     * recycled. Callers MUST NOT retain a reference — copy with
     * [Bitmap.copy] if you need the pixels to survive past the callback.
     * Used by the vision pipeline to feed frames into detection/OCR.
     */
    var onFrame: ((Bitmap) -> Unit)? = null

    /**
     * Invoked from the receive thread with the human-readable error message
     * when the RTSP/RTP pipeline fails (socket timeout, connect refused,
     * malformed SDP, etc.). Lets the VisionDebug screen surface a red
     * status instead of leaving a stale "Streaming" label up forever.
     */
    var onError: ((String) -> Unit)? = null

    fun start(url: String) {
        running = true
        receiveThread = thread(name = "MjpegRtsp") {
            try {
                runPlayer(url)
            } catch (e: Exception) {
                if (running) {
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "Player error: $msg")
                    try { onError?.invoke(msg) } catch (_: Throwable) {}
                }
            }
        }
    }

    fun stop() {
        running = false
        try { rtspSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        receiveThread?.join(2000)
    }

    // ── RTSP + RTP pipeline ────────────────────────────────────────────────

    private fun runPlayer(url: String) {
        // Parse host:port from URL
        val hostPort = url.removePrefix("rtsp://").substringBefore("/")
        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":", "554").toInt()

        // Open RTSP TCP connection
        val sock = if (network != null) {
            try { network.socketFactory.createSocket(host, port) }
            catch (_: Exception) { Socket(host, port) }
        } else Socket(host, port)
        sock.soTimeout = 10_000
        rtspSocket = sock

        val writer = PrintWriter(sock.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))

        // RTSP OPTIONS
        sendRtsp(writer, "OPTIONS", url, null)
        readRtspResponse(reader)

        // RTSP DESCRIBE
        sendRtsp(writer, "DESCRIBE", url, "Accept: application/sdp")
        val describeResp = readRtspResponse(reader)
        Log.d(TAG, "DESCRIBE SDP:\n$describeResp")

        // Parse video control URL from SDP; fix 0.0.0.0 → actual camera host
        var videoControl = parseVideoControl(describeResp, url)
        videoControl = videoControl.replace("://0.0.0.0:", "://$host:")
        Log.d(TAG, "Video control: $videoControl")

        // Allocate UDP port for RTP
        val udp = DatagramSocket()
        network?.bindSocket(udp)
        udpSocket = udp
        val clientPort = udp.localPort
        Log.d(TAG, "UDP socket on port $clientPort (bound=${network != null})")

        // RTSP SETUP (video stream only)
        sendRtsp(writer, "SETUP", videoControl,
            "Transport: RTP/AVP/UDP;unicast;client_port=$clientPort-${clientPort + 1}")
        val setupResp = readRtspResponse(reader)
        Log.d(TAG, "SETUP response:\n$setupResp")
        sessionId = parseSession(setupResp)
        Log.d(TAG, "Session: $sessionId")

        // RTSP PLAY
        val sessionHeader = sessionId?.let { "Session: $it" }
        sendRtsp(writer, "PLAY", url, "Range: npt=0.000-\r\n$sessionHeader")
        readRtspResponse(reader)
        Log.i(TAG, "PLAY sent — receiving RTP JPEG frames")

        // Receive and render MJPEG frames
        receiveAndRender(udp)

        // Cleanup: TEARDOWN
        if (running) {
            try {
                sendRtsp(writer, "TEARDOWN", url, sessionHeader)
                readRtspResponse(reader)
            } catch (_: Exception) {}
        }
    }

    // ── RTSP helpers ───────────────────────────────────────────────────────

    private fun sendRtsp(writer: PrintWriter, method: String, url: String, extra: String?) {
        cseq++
        val msg = buildString {
            append("$method $url RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("User-Agent: TrafyKamerasi\r\n")
            if (extra != null) append("$extra\r\n")
            append("\r\n")
        }
        writer.print(msg)
        writer.flush()
        Log.d(TAG, ">> $method CSeq=$cseq")
    }

    private fun readRtspResponse(reader: BufferedReader): String {
        val sb = StringBuilder()
        var contentLength = 0
        // Read headers
        while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            if (line.startsWith("Content-length:", ignoreCase = true) ||
                line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            if (line.isEmpty()) break
        }
        // Read body if present
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(body, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            sb.append(body, 0, read)
        }
        Log.d(TAG, "<< ${sb.toString().lines().firstOrNull()}")
        return sb.toString()
    }

    private fun parseVideoControl(sdp: String, baseUrl: String): String {
        // Find the video media section and its control attribute
        val lines = sdp.lines()
        var inVideo = false
        var control: String? = null
        var contentBase: String? = null
        for (line in lines) {
            if (line.startsWith("Content-Base:", ignoreCase = true)) {
                contentBase = line.substringAfter(":").trim()
            }
            if (line.startsWith("m=video")) inVideo = true
            if (line.startsWith("m=audio")) inVideo = false
            if (inVideo && line.startsWith("a=control:")) {
                control = line.substringAfter("a=control:").trim()
            }
        }
        val base = contentBase ?: baseUrl
        return when {
            control == null -> base
            control.startsWith("rtsp://") -> control
            else -> "${base.trimEnd('/')}/$control"
        }
    }

    private fun parseSession(response: String): String? {
        for (line in response.lines()) {
            if (line.startsWith("Session:", ignoreCase = true)) {
                return line.substringAfter(":").trim().substringBefore(";")
            }
        }
        return null
    }

    // ── RTP JPEG receive & render ──────────────────────────────────────────

    private fun receiveAndRender(udp: DatagramSocket) {
        val buf = ByteArray(UDP_BUFFER_SIZE)
        val packet = DatagramPacket(buf, buf.size)

        // Frame assembly state
        var currentTimestamp = -1L
        val fragments = mutableListOf<RtpJpegFragment>()
        var firstFrameEmitted = false
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var packetCount = 0
        var timeoutCount = 0
        var frameCount = 0
        // RTP sequence + frame-corruption tracking. The dashcam Wi-Fi drops
        // an occasional UDP packet; without these checks we'd happily decode
        // the partial JPEG and BitmapFactory would paint garbage macroblocks
        // where the missing entropy data should be — that's the random
        // coloured-pixel flicker users see on Live.
        var lastSeq = -1
        var frameCorrupt = false
        var droppedFrames = 0

        udp.soTimeout = 5000

        while (running) {
            try {
                udp.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                timeoutCount++
                Log.w(TAG, "UDP receive timeout #$timeoutCount (packets so far: $packetCount, frames: $frameCount)")
                if (timeoutCount >= 6) {
                    Log.e(TAG, "Too many timeouts — no RTP data arriving, giving up")
                    break
                }
                continue
            } catch (e: Exception) {
                Log.e(TAG, "UDP receive error: ${e.message}")
                break
            }

            timeoutCount = 0  // reset on successful receive
            packetCount++

            val data = packet.data
            val len = packet.length
            if (packetCount <= 3) {
                val pt = data[1].toInt() and 0x7F
                Log.d(TAG, "RTP pkt #$packetCount len=$len from=${packet.address}:${packet.port} pt=$pt")
            }
            if (len < RTP_HEADER_SIZE + JPEG_HEADER_SIZE) continue

            // Parse RTP header
            val seq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val marker = (data[1].toInt() and 0x80) != 0
            val timestamp = ByteBuffer.wrap(data, 4, 4).int.toLong() and 0xFFFFFFFFL

            // Parse RFC 2435 JPEG header (after 12-byte RTP header)
            val off = RTP_HEADER_SIZE
            val fragmentOffset = ((data[off + 1].toInt() and 0xFF) shl 16) or
                    ((data[off + 2].toInt() and 0xFF) shl 8) or
                    (data[off + 3].toInt() and 0xFF)
            val jpegType = data[off + 4].toInt() and 0xFF
            val quality = data[off + 5].toInt() and 0xFF
            val width = (data[off + 6].toInt() and 0xFF) * 8
            val height = (data[off + 7].toInt() and 0xFF) * 8

            // Determine payload start (skip quantization table header if present)
            var payloadOffset = off + JPEG_HEADER_SIZE
            var quantData: ByteArray? = null
            if (fragmentOffset == 0 && quality >= 128) {
                // Quantization table header present
                if (payloadOffset + 4 <= len) {
                    val qtLength = ((data[payloadOffset + 2].toInt() and 0xFF) shl 8) or
                            (data[payloadOffset + 3].toInt() and 0xFF)
                    if (payloadOffset + 4 + qtLength <= len) {
                        quantData = data.copyOfRange(payloadOffset + 4, payloadOffset + 4 + qtLength)
                        payloadOffset += 4 + qtLength
                    }
                }
            }

            if (payloadOffset >= len) continue
            val jpegData = data.copyOfRange(payloadOffset, len)

            // New frame?
            val isNewFrame = timestamp != currentTimestamp
            if (isNewFrame) {
                // Reaching here without ever seeing the marker bit means the
                // previous frame's tail packet was lost — the JPEG would be
                // truncated and decode to a partial garbage image. Drop it.
                if (fragments.isNotEmpty()) {
                    droppedFrames++
                    if (droppedFrames <= 5 || droppedFrames % 50 == 0) {
                        Log.d(TAG, "drop frame: missing marker (frags=${fragments.size}, droppedTotal=$droppedFrames)")
                    }
                }
                fragments.clear()
                currentTimestamp = timestamp
                frameCorrupt = false
            }

            // Detect packet loss within the current frame via RTP seq gaps.
            // A gap on a frame transition is almost always the lost marker
            // packet of the previous frame (already dropped above), so we
            // only flag the *current* frame as corrupt when the gap occurs
            // between two packets carrying the same timestamp.
            if (lastSeq >= 0) {
                val expected = (lastSeq + 1) and 0xFFFF
                if (seq != expected && !isNewFrame) {
                    frameCorrupt = true
                }
            }
            lastSeq = seq

            fragments.add(RtpJpegFragment(
                fragmentOffset = fragmentOffset,
                jpegType = jpegType,
                quality = quality,
                width = width,
                height = height,
                quantData = quantData,
                data = jpegData,
                marker = marker,
            ))

            // Marker bit = last packet of frame
            if (marker && fragments.isNotEmpty()) {
                // Final integrity check: fragment offsets must be contiguous
                // starting at 0. Catches the cases the seq-gap check can't:
                //   (a) lost first packet of new frame — frame-boundary seq
                //       gaps aren't flagged as corrupt because we can't tell
                //       whether the missing seq belonged to the previous
                //       frame's marker or the new frame's head;
                //   (b) any other split where a fragment didn't arrive but
                //       sequence numbers happened to line up (rare, but
                //       BitmapFactory will happily render the truncated JPEG
                //       as a flickering pixel field if we let it through).
                if (!frameCorrupt) {
                    val sorted = fragments.sortedBy { it.fragmentOffset }
                    var expectedOffset = 0
                    for (frag in sorted) {
                        if (frag.fragmentOffset != expectedOffset) {
                            frameCorrupt = true
                            break
                        }
                        expectedOffset += frag.data.size
                    }
                }
                if (frameCorrupt) {
                    droppedFrames++
                    if (droppedFrames <= 5 || droppedFrames % 50 == 0) {
                        Log.d(TAG, "drop frame: corrupt (frags=${fragments.size}, droppedTotal=$droppedFrames)")
                    }
                } else {
                    frameCount++
                    renderFrame(fragments, paint, frameCount)
                    if (!firstFrameEmitted) {
                        firstFrameEmitted = true
                        onFirstFrame?.invoke()
                    }
                }
                fragments.clear()
                currentTimestamp = -1
                frameCorrupt = false
            }
        }
    }

    private fun renderFrame(fragments: List<RtpJpegFragment>, paint: Paint, frameNum: Int = 0) {
        if (fragments.isEmpty()) return

        // Sort by fragment offset
        val sorted = fragments.sortedBy { it.fragmentOffset }
        val first = sorted[0]

        if (frameNum <= 3) {
            val hex = first.data.take(16).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "Frame #$frameNum: ${sorted.size} frags, ${first.width}x${first.height}, " +
                    "type=${first.jpegType} q=${first.quality} quantData=${first.quantData?.size} " +
                    "firstBytes=[$hex]")
        }

        // The GeneralPlus camera sends q=1 but embeds complete JPEG data (with its
        // own DQT/SOF/DHT/SOS headers) inside the RTP fragments. Try raw
        // concatenation first; fall back to RFC 2435 header synthesis only if needed.
        val rawData = concatFragments(sorted)
        val jpeg = if (rawData.size >= 2 &&
            rawData[0] == 0xFF.toByte() && rawData[1] == 0xD8.toByte()) {
            // Data already starts with SOI — it's a complete JPEG
            if (frameNum <= 3) Log.d(TAG, "Frame #$frameNum: raw JPEG detected (SOI present)")
            // Ensure EOI is present
            if (rawData[rawData.size - 2] == 0xFF.toByte() && rawData[rawData.size - 1] == 0xD9.toByte()) {
                rawData
            } else {
                rawData + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            }
        } else if (rawData.size >= 2 &&
            rawData[0] == 0xFF.toByte() && (rawData[1] == 0xDB.toByte() || rawData[1] == 0xC0.toByte())) {
            // Data starts with DQT or SOF — just needs SOI prefix
            if (frameNum <= 3) Log.d(TAG, "Frame #$frameNum: JPEG headers without SOI")
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + rawData + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        } else {
            // Pure entropy data — use RFC 2435 header synthesis
            if (frameNum <= 3) Log.d(TAG, "Frame #$frameNum: RFC 2435 assembly (entropy data)")
            assembleJpeg(sorted, first.width, first.height,
                first.jpegType, first.quality, first.quantData)
        }
        if (jpeg == null) {
            if (frameNum <= 5) Log.w(TAG, "Frame #$frameNum: assembleJpeg returned null")
            return
        }

        // Decode JPEG to bitmap
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap == null) {
            if (frameNum <= 5) Log.w(TAG, "Frame #$frameNum: decode failed (${jpeg.size} bytes, " +
                    "first4=[${jpeg.take(4).joinToString(" ") { "%02X".format(it) }}])")
            return
        }
        if (frameNum <= 3) Log.d(TAG, "Frame #$frameNum: decoded ${bitmap.width}x${bitmap.height}")

        // Vision pipeline tap — give consumers a chance to copy before we
        // recycle the bitmap below. Thrown exceptions here don't kill the
        // player; a broken vision consumer shouldn't stop the video.
        onFrame?.let { cb ->
            try { cb(bitmap) } catch (t: Throwable) {
                if (frameNum <= 5) Log.w(TAG, "onFrame callback threw", t)
            }
        }

        // Render to surface
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val dstRect = fitCenter(bitmap.width, bitmap.height, canvas.width, canvas.height)
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {
            // Surface may be invalid if player is stopping
        } finally {
            bitmap.recycle()
        }
    }

    private fun fitCenter(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = (srcW * scale).toInt()
        val h = (srcH * scale).toInt()
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Concatenates all fragment data in order into a single byte array. */
    private fun concatFragments(sorted: List<RtpJpegFragment>): ByteArray {
        val out = ByteArrayOutputStream(sorted.sumOf { it.data.size })
        for (frag in sorted) out.write(frag.data)
        return out.toByteArray()
    }

    // ── RFC 2435 JPEG assembly ─────────────────────────────────────────────

    private data class RtpJpegFragment(
        val fragmentOffset: Int,
        val jpegType: Int,
        val quality: Int,
        val width: Int,
        val height: Int,
        val quantData: ByteArray?,
        val data: ByteArray,
        val marker: Boolean,
    )

    private fun assembleJpeg(
        fragments: List<RtpJpegFragment>,
        width: Int, height: Int,
        jpegType: Int, quality: Int,
        quantData: ByteArray?,
    ): ByteArray? {
        val out = ByteArrayOutputStream(65536)

        // SOI marker
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

        // APP0 (JFIF) marker - minimal
        out.write(byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, // "JFIF\0"
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        ))

        // DQT markers (quantization tables)
        if (quality >= 128 && quantData != null) {
            // Use quantization tables from the RTP packet
            writeQuantTablesFromData(out, quantData)
        } else {
            // Generate standard tables scaled by quality
            val q = if (quality == 0) 1 else quality
            val factor = if (q < 50) 5000 / q else 200 - q * 2
            writeDqt(out, 0, scaleTable(JPEG_LUMA_QUANTIZER, factor))
            writeDqt(out, 1, scaleTable(JPEG_CHROMA_QUANTIZER, factor))
        }

        // SOF0 (Start of Frame)
        val components = if (jpegType and 0x3F == 0) 3 else 3  // always YCbCr for type 0/1
        val hsamp = if (jpegType and 0x3F == 0) 0x22 else 0x21 // type 0: 4:2:2→2x1, wait actually
        // RFC 2435 type 0 = YUV 4:2:2 (H=2,V=1), type 1 = YUV 4:2:0 (H=2,V=2)
        val yHV = if (jpegType and 0x3F == 1) 0x22.toByte() else 0x21.toByte()
        out.write(byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x11,  // length = 17
            0x08,        // precision = 8 bits
            (height shr 8).toByte(), (height and 0xFF).toByte(),
            (width shr 8).toByte(), (width and 0xFF).toByte(),
            0x03,        // 3 components
            0x01, yHV, 0x00,      // Y: id=1, sampling, table=0
            0x02, 0x11, 0x01,     // Cb: id=2, 1x1, table=1
            0x03, 0x11, 0x01,     // Cr: id=3, 1x1, table=1
        ))

        // DHT (Huffman tables) - standard tables from JPEG spec
        writeDht(out, 0x00, DC_LUMA_BITS, DC_LUMA_VALS)
        writeDht(out, 0x10, AC_LUMA_BITS, AC_LUMA_VALS)
        writeDht(out, 0x01, DC_CHROMA_BITS, DC_CHROMA_VALS)
        writeDht(out, 0x11, AC_CHROMA_BITS, AC_CHROMA_VALS)

        // SOS (Start of Scan)
        out.write(byteArrayOf(
            0xFF.toByte(), 0xDA.toByte(),
            0x00, 0x0C,  // length = 12
            0x03,        // 3 components
            0x01, 0x00,  // Y: DC=0, AC=0
            0x02, 0x11,  // Cb: DC=1, AC=1
            0x03, 0x11,  // Cr: DC=1, AC=1
            0x00, 0x3F, 0x00, // Ss=0, Se=63, Ah/Al=0
        ))

        // JPEG entropy-coded data from all fragments
        for (frag in fragments) {
            out.write(frag.data)
        }

        // EOI marker
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))

        return out.toByteArray()
    }

    private fun writeQuantTablesFromData(out: ByteArrayOutputStream, data: ByteArray) {
        // data may contain 1 or 2 quantization tables (64 bytes each)
        if (data.size >= 64) {
            writeDqt(out, 0, data.copyOfRange(0, 64))
        }
        if (data.size >= 128) {
            writeDqt(out, 1, data.copyOfRange(64, 128))
        }
    }

    private fun writeDqt(out: ByteArrayOutputStream, tableId: Int, table: ByteArray) {
        out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte()))
        val len = 2 + 1 + 64  // length field + Pq/Tq byte + 64 values
        out.write(byteArrayOf((len shr 8).toByte(), (len and 0xFF).toByte()))
        out.write(tableId)  // Pq=0 (8-bit), Tq=tableId
        out.write(table, 0, 64.coerceAtMost(table.size))
        // Pad with zeros if table is short
        repeat(64 - table.size.coerceAtMost(64)) { out.write(0) }
    }

    private fun scaleTable(base: ByteArray, factor: Int): ByteArray {
        return ByteArray(64) { i ->
            val v = (base[i].toInt() and 0xFF) * factor + 50
            (v / 100).coerceIn(1, 255).toByte()
        }
    }

    private fun writeDht(out: ByteArrayOutputStream, tcTh: Int, bits: ByteArray, vals: ByteArray) {
        out.write(byteArrayOf(0xFF.toByte(), 0xC4.toByte()))
        val len = 2 + 1 + bits.size + vals.size
        out.write(byteArrayOf((len shr 8).toByte(), (len and 0xFF).toByte()))
        out.write(tcTh)
        out.write(bits)
        out.write(vals)
    }

}
