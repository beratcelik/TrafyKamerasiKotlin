package com.example.trafykamerasikotlin.data.allwinner

import android.util.Log
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kcp.IKcp
import kcp.Kcp
import kcp.KcpOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UDP media transport for Allwinner V853 (A19-01) recorded-file playback and download.
 *
 * Session flow:
 *   1. Send `rtp2p` cmd over the TCP session: { start:1, camid, file, time, ... }
 *   2. Receive: { ip:<LE uint32>, port:2222, pwd:<nonce>, me, peer, cookie, ret:0 }
 *   3. Open a UDP socket, `connect()` to <ip>:<port>.
 *   4. **Send a 340-byte heartbeat every 200 ms**. The device only streams while it
 *      is receiving heartbeats; silence for ~1 s makes the firmware stop sending.
 *      This was the missing piece — a single holepunch packet was not enough.
 *   5. Consume inbound UDP datagrams (MPEG-TS fragments) via [packets].
 *   6. On close, cancel the heartbeat loop and send `rtp2p { start:0 }`.
 *
 * Heartbeat packet layout (340 bytes total; derived from the OEM CloudSpirit capture):
 *
 *   Offset  Bytes       Meaning
 *   ------  ----------  --------------------------------------------
 *    0..3   00000000    zero (packet type = 0 = keepalive)
 *    4..7   25201000    magic 0x00102025 LE (protocol ID)
 *    8..11  uidx LE     session uidx (from login response)
 *   12..15  01000000    peer = 1 (device)
 *   16..19  phone IPv4 LE (from local socket address)
 *   20..23  zero
 *   24..27  phone port LE (from local socket)
 *   28..31  zero
 *   32..35  01030 10a   fixed flags (observed constant)
 *   36..39  pwd LE      session nonce from rtp2p start response
 *   40..43  zero
 *   44..47  ~1 kHz counter (we send monotonic ms since boot — exact value
 *           is not verified against device, but observed values incremented
 *           smoothly, so a monotonic field is the safest bet)
 *   48      sequence byte (++ per packet)
 *   49..51  000101      fixed tail
 *   52..83  zero
 *   84..87  ffff0000    marker
 *   88..339 zero (padding)
 */
internal class AllwinnerRtp2pClient private constructor(
    private val session: AllwinnerSession,
    private val socket: DatagramSocket,
    private val pwd: Long,
    private val camid: Int,
    private val fileName: String,
) {

    companion object {
        private const val TAG = "Trafy.AllwinnerRtp2p"

        /** Heartbeat cadence — OEM app sends ~5 per second; 200 ms is a safe match. */
        private const val HEARTBEAT_INTERVAL_MS = 200L
        private const val READ_TIMEOUT_MS = 2_000
        private const val RECV_BUFFER_SIZE = 65_536
        private const val HEARTBEAT_SIZE = 340
        private const val MAGIC = 0x00102025
        /**
         * Bytes prefixed to each KCP-reassembled media chunk by the cam:
         * `[4B seq LE][12B opaque header]`. Stripped before the payload is
         * forwarded to the player. Empirically derived from the cam's
         * stream — exact layout of the 12-byte header isn't documented;
         * it varies per packet and may include nonce/timestamp/type fields.
         */
        private const val INNER_HEADER_SIZE = 16

        /** Magic bytes that mark a kcp-msg-header inside a KCP message: `d1 1d f2 10`. */
        private const val MAGIC_B0 = 0xd1.toByte()
        private const val MAGIC_B1 = 0x1d.toByte()
        private const val MAGIC_B2 = 0xf2.toByte()
        private const val MAGIC_B3 = 0x10.toByte()
        /** Bytes 32..35 of the heartbeat — observed as constant across all sessions. */
        private val FIXED_FLAGS = byteArrayOf(0x01, 0x03, 0x01, 0x0a)

        suspend fun open(
            session: AllwinnerSession,
            camid: Int,
            fileName: String,
            epoch: Long,
        ): AllwinnerRtp2pClient? = withContext(Dispatchers.IO) {
            val startBody = JSONObject().apply {
                put("peer", session.uidx)
                put("deviceid", session.deviceId)
                put("start", 1)
                put("camid", camid)
                put("file", fileName)
                put("time", epoch)
                put("timeout", 600)
                put("flag", 0)
                put("ver", 1)
            }
            val resp = try {
                session.rtp2p(startBody)
            } catch (e: Exception) {
                Log.e(TAG, "rtp2p start failed for $fileName: ${e.message}", e)
                return@withContext null
            }
            if (resp.optInt("ret", -1) != 0) {
                Log.w(TAG, "rtp2p start ret=${resp.optInt("ret")}: $resp")
                return@withContext null
            }

            val ipInt = resp.optLong("ip", 0L)
            val port  = resp.optInt("port", 0)
            val pwd   = resp.optLong("pwd", 0L)
            if (ipInt == 0L || port == 0) {
                Log.w(TAG, "rtp2p start missing ip/port: $resp")
                return@withContext null
            }
            val ipStr = ipFromLeUint32(ipInt)
            Log.i(TAG, "rtp2p start OK: $ipStr:$port pwd=$pwd uidx=${session.uidx}")

            val socket = try {
                AllwinnerNetwork.createDatagramSocket().apply {
                    soTimeout = READ_TIMEOUT_MS
                    // connect() pins the kernel's return path and gives us a stable
                    // localAddress/localPort that we can embed in the heartbeat.
                    connect(InetAddress.getByName(ipStr), port)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createDatagramSocket / connect failed: ${e.message}", e)
                return@withContext null
            }

            val client = AllwinnerRtp2pClient(session, socket, pwd, camid, fileName)
            client.startHeartbeat()
            client
        }

        /**
         * Open an rtp2p session for **live preview** — no recorded file, no
         * timestamp, just the camera feed. Reverse-engineered from a CloudSpirit
         * PCAP: the OEM omits both `file` and `time` fields when starting live.
         * Camera replies with port 2222 + pwd and immediately starts streaming
         * once heartbeats begin.
         */
        suspend fun openLive(
            session: AllwinnerSession,
            camid: Int,
        ): AllwinnerRtp2pClient? = withContext(Dispatchers.IO) {
            val startBody = JSONObject().apply {
                put("peer", session.uidx)
                put("deviceid", session.deviceId)
                put("start", 1)
                put("camid", camid)
                put("timeout", 600)
                put("flag", 0)
                put("ver", 1)
                // NOTE: deliberately NO "file" and NO "time" — the absence of
                // both is what tells the camera "give me live, not a stored
                // file" (CloudSpirit PCAP, cookie 28 / 56).
            }
            val resp = try {
                session.rtp2p(startBody)
            } catch (e: Exception) {
                Log.e(TAG, "rtp2p live start failed: ${e.message}", e)
                return@withContext null
            }
            if (resp.optInt("ret", -1) != 0) {
                Log.w(TAG, "rtp2p live start ret=${resp.optInt("ret")}: $resp")
                return@withContext null
            }

            val ipInt = resp.optLong("ip", 0L)
            val port  = resp.optInt("port", 0)
            val pwd   = resp.optLong("pwd", 0L)
            if (ipInt == 0L || port == 0) {
                Log.w(TAG, "rtp2p live start missing ip/port: $resp")
                return@withContext null
            }
            val ipStr = ipFromLeUint32(ipInt)
            Log.i(TAG, "rtp2p live start OK: $ipStr:$port pwd=$pwd uidx=${session.uidx} camid=$camid")

            val socket = try {
                AllwinnerNetwork.createDatagramSocket().apply {
                    soTimeout = READ_TIMEOUT_MS
                    connect(InetAddress.getByName(ipStr), port)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createDatagramSocket / connect failed: ${e.message}", e)
                return@withContext null
            }

            // Use "<live>" as a placeholder fileName for log/teardown purposes.
            // Stop is sent with the same shape (no file/time).
            val client = AllwinnerRtp2pClient(session, socket, pwd, camid, "<live>")
            client.startHeartbeat()
            client
        }

        /** PCAP shows ip=19114176 (0x01 23 A8 C0) encoded little-endian = 192.168.35.1. */
        private fun ipFromLeUint32(v: Long): String {
            val b0 = (v and 0xFF).toInt()
            val b1 = ((v shr 8)  and 0xFF).toInt()
            val b2 = ((v shr 16) and 0xFF).toInt()
            val b3 = ((v shr 24) and 0xFF).toInt()
            return "$b0.$b1.$b2.$b3"
        }

        /** Encodes an IPv4 address into its little-endian uint32 form for the heartbeat. */
        private fun ipv4ToLeUint32(addr: InetAddress): Int {
            val bytes = addr.address  // always 4 bytes for Inet4Address (big-endian)
            return ((bytes[0].toInt() and 0xFF))             or
                   ((bytes[1].toInt() and 0xFF) shl 8)       or
                   ((bytes[2].toInt() and 0xFF) shl 16)      or
                   ((bytes[3].toInt() and 0xFF) shl 24)
        }
    }

    @Volatile private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    /**
     * Tracks the most recently seen frame type within the receive loop.
     * Continuation chunks (no `00 11 1?/2? d8` tag) inherit the type of the
     * frame they're extending. We only forward video continuations; audio
     * continuations are dropped along with their headers.
     */
    @Volatile private var lastFrameWasVideo = false
    /**
     * Accumulates the bytes of the current video frame across multiple
     * inner-frames / KCP messages. Flushed when a new video tag arrives
     * (start of a different frame) or when an audio tag arrives (current
     * video frame complete). Each flush emits one [ByteArray] on the
     * outer Flow — i.e. one logical H.264 access unit — so the muxer can
     * wrap it in exactly one PES packet with one PTS.
     */
    private val frameAccum = java.io.ByteArrayOutputStream(64 * 1024)
    // Sequence byte at heartbeat offset 48; rolls over at 256 in the OEM capture too.
    private var heartbeatSeq = 0

    /** Locally-bound port assigned after `connect()`, embedded in heartbeat payload. */
    private val localPort: Int = socket.localPort
    /** Phone's IPv4 on the dashcam Wi-Fi — needed so the device knows where to send. */
    private val localIpLe: Int = run {
        val addr = socket.localAddress
        if (addr is Inet4Address) ipv4ToLeUint32(addr)
        else {
            Log.w(TAG, "localAddress is not IPv4: $addr — heartbeat IP field will be 0")
            0
        }
    }

    init {
        Log.i(TAG, "rtp2p socket: local=$localIpLe (LE)/$localPort target=${socket.remoteSocketAddress}")
    }

    /**
     * Two kinds of KCP messages from this cam, distinguished by what's at
     * bytes 4..7 of the message:
     *
     *   1. **Frame-start / tagged**: `[4B seq][d11df210 magic][4B][4B] +
     *      [4B type tag] + payload`. Total 20-byte preamble before the
     *      payload of the first inner-segment. May contain MULTIPLE
     *      inner-messages glued together; we scan for additional magics
     *      to split them apart.
     *
     *   2. **Continuation**: `[4B seq] + raw payload bytes`. NO magic, NO
     *      type tag — the cam just streams the next chunk of the current
     *      frame's H.264 bytes. Strip only the seq and append.
     *
     * Earlier we treated case 2 as if it had a 16B header too, which lost
     * 12 bytes per continuation message — that's where the vertical-band
     * corruption was coming from (an IDR slice of ~80 packets was missing
     * ≈1 KB of slice data).
     */
    private fun consumeKcpMessage(kcpMsg: ByteArray, emit: (ByteArray) -> Unit) {
        if (kcpMsg.size < 4) return

        val hasHeader = kcpMsg.size >= 8 &&
            kcpMsg[4] == MAGIC_B0 && kcpMsg[5] == MAGIC_B1 &&
            kcpMsg[6] == MAGIC_B2 && kcpMsg[7] == MAGIC_B3

        if (!hasHeader) {
            // Pure continuation: seq# followed by raw payload bytes.
            if (lastFrameWasVideo && kcpMsg.size > 4) {
                frameAccum.write(kcpMsg, 4, kcpMsg.size - 4)
            }
            return
        }

        // Header-bearing message. Find every embedded inner-message
        // boundary (additional `d11df210` magics inside the same KCP
        // message — the cam sometimes packs multiple inner-frames in one
        // KCP send).
        val boundaries = ArrayList<Int>(4)
        boundaries.add(0)
        var i = 8
        while (i + 4 <= kcpMsg.size) {
            if (kcpMsg[i] == MAGIC_B0 && kcpMsg[i + 1] == MAGIC_B1 &&
                kcpMsg[i + 2] == MAGIC_B2 && kcpMsg[i + 3] == MAGIC_B3
            ) {
                boundaries.add(i - 4)
                i += INNER_HEADER_SIZE
            } else {
                i++
            }
        }

        for (k in boundaries.indices) {
            val start = boundaries[k]
            val end = if (k + 1 < boundaries.size) boundaries[k + 1] else kcpMsg.size
            if (end - start <= INNER_HEADER_SIZE) continue
            val afterHdr = kcpMsg.copyOfRange(start + INNER_HEADER_SIZE, end)
            consumeInnerSegment(afterHdr, emit)
        }
    }

    /**
     * Routes a single inner-segment (post 16B header) into the per-frame
     * accumulator. Three classes of segment, all detected by the upper
     * 24 bits of the 4-byte type tag (the low byte's flag bits aren't
     * part of frame identity):
     *
     *   • `00 11 10 ??` — video tag → flush + start new frame.
     *   • `00 11 20 ??` — audio tag → flush, then drop this segment.
     *   • no recognisable tag → continuation of current frame; appended
     *     verbatim if the last typed segment was video, else dropped.
     */
    private fun consumeInnerSegment(seg: ByteArray, emit: (ByteArray) -> Unit) {
        if (seg.size < 4) {
            if (lastFrameWasVideo) frameAccum.write(seg, 0, seg.size)
            return
        }
        val b0 = seg[0].toInt() and 0xff
        val b1 = seg[1].toInt() and 0xff
        val b2 = seg[2].toInt() and 0xff
        when {
            b0 == 0x00 && b1 == 0x11 && b2 == 0x10 -> {
                flushFrame(emit)
                lastFrameWasVideo = true
                frameAccum.write(seg, 4, seg.size - 4)
            }
            b0 == 0x00 && b1 == 0x11 && b2 == 0x20 -> {
                flushFrame(emit)
                lastFrameWasVideo = false
            }
            else -> {
                if (lastFrameWasVideo) frameAccum.write(seg, 0, seg.size)
            }
        }
    }

    private fun flushFrame(emit: (ByteArray) -> Unit) {
        if (frameAccum.size() > 0) {
            emit(frameAccum.toByteArray())
            frameAccum.reset()
        }
    }

    private fun buildHeartbeat(): ByteArray {
        val buf = ByteBuffer.allocate(HEARTBEAT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)                                    // [0..3]   zero
        buf.putInt(MAGIC)                                // [4..7]   magic
        buf.putInt(session.uidx)                         // [8..11]  uidx
        buf.putInt(1)                                    // [12..15] peer = 1
        buf.putInt(localIpLe)                            // [16..19] phone IPv4
        buf.putInt(0)                                    // [20..23] zero
        buf.putInt(localPort)                            // [24..27] phone port
        buf.putInt(0)                                    // [28..31] zero
        buf.put(FIXED_FLAGS)                             // [32..35] fixed flags
        buf.putInt(pwd.toInt())                          // [36..39] pwd
        buf.putInt(0)                                    // [40..43] zero
        // [44..47] monotonic ~1 kHz counter. We can't replicate the device's exact
        // clock but values observed in the OEM capture incremented smoothly by ~200
        // per 200 ms, so ms-since-boot should read as well-formed.
        buf.putInt((System.nanoTime() / 1_000_000L).toInt())
        // [48..51] sequence byte + fixed 00 01 01 tail (observed).
        val seq = heartbeatSeq.also { heartbeatSeq = (heartbeatSeq + 1) and 0xFF }
        buf.put(seq.toByte())
        buf.put(0x00)
        buf.put(0x01)
        buf.put(0x01)
        // [52..83] zeros
        buf.position(0x54)
        buf.put(0xff.toByte()); buf.put(0xff.toByte())   // [84..85] marker
        // Remaining bytes (already zero-initialised) pad to 340.
        return buf.array()
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && !closed) {
                try {
                    val payload = buildHeartbeat()
                    socket.send(DatagramPacket(payload, payload.size))
                    if (heartbeatSeq <= 2) {
                        Log.d(TAG, "heartbeat tx #$heartbeatSeq (${payload.size}B)")
                    }
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "heartbeat send failed: ${e.message}")
                    break
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Cold Flow of reassembled stream bytes. Inbound UDP datagrams come in two
     * shapes on this transport:
     *
     *   • **340-byte heartbeat ACKs** — first 4 bytes zero, then the same
     *     `MAGIC` we send out. These echo our keep-alives and carry no media;
     *     they're filtered out before reaching the player.
     *   • **KCP-PUSH frames** — non-zero conv (first 4 bytes) followed by the
     *     standard KCP header (cmd=0x51 for data). Multiple datagrams form
     *     one logical message via fragmentation/sequencing; KCP handles
     *     reassembly, ordering, and retransmission. The reassembled payload
     *     is the actual MPEG-TS chunk we feed the player.
     *
     * We use kcp-base's [Kcp] implementation: lazy-init on the first KCP
     * packet (conv extracted from its header), feed each datagram through
     * `input()`, drain reassembled bytes via `mergeRecv()`. A periodic
     * `update()` coroutine drives ACK timing — without it the cam thinks
     * we never got the data and stalls retransmitting.
     */
    fun packets(): Flow<ByteArray> = callbackFlow {
        val recvBuf = ByteArray(RECV_BUFFER_SIZE)
        val packet = DatagramPacket(recvBuf, recvBuf.size)
        var kcp: IKcp? = null
        val kcpLock = Mutex()
        var debugN = 0

        // KCP needs to send ACKs back to the cam — route them through the
        // already-connected UDP socket. Capture-by-reference is safe; the
        // socket only closes once at the end of this flow.
        val kcpOutput = KcpOutput { buf, _ ->
            val len = buf.readableBytes()
            if (len <= 0) return@KcpOutput
            val out = ByteArray(len)
            buf.readBytes(out)
            try {
                socket.send(DatagramPacket(out, len))
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "kcp output send failed: ${e.message}")
            }
        }

        // Periodic update — KCP schedules ACKs/retransmits from this tick.
        val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val updateJob = updateScope.launch {
            while (isActive && !closed) {
                kcp?.let { k ->
                    try { kcpLock.withLock { k.update(System.currentTimeMillis()) } }
                    catch (e: Exception) { if (!closed) Log.w(TAG, "kcp update: ${e.message}") }
                }
                delay(10)
            }
        }

        try {
            while (!closed) {
                try {
                    socket.receive(packet)
                    val len = packet.length

                    // Heartbeat ACK: 340-byte status frame with conv=0.
                    val isHeartbeatAck = len == HEARTBEAT_SIZE &&
                        recvBuf[0] == 0.toByte() && recvBuf[1] == 0.toByte() &&
                        recvBuf[2] == 0.toByte() && recvBuf[3] == 0.toByte()
                    if (isHeartbeatAck) {
                        if (debugN < 3) {
                            Log.d(TAG, "rx UDP #${++debugN} heartbeat-ack ${len}B — drop")
                        }
                        continue
                    }

                    // First KCP packet: extract conv and instantiate.
                    if (kcp == null) {
                        if (len < 24) {
                            Log.w(TAG, "non-status packet too short for KCP: $len bytes — drop")
                            continue
                        }
                        val conv = (recvBuf[0].toInt() and 0xff) or
                            ((recvBuf[1].toInt() and 0xff) shl 8) or
                            ((recvBuf[2].toInt() and 0xff) shl 16) or
                            ((recvBuf[3].toInt() and 0xff) shl 24)
                        kcp = Kcp(conv, kcpOutput).apply {
                            // Stream-friendly defaults: nodelay=on, interval=10ms,
                            // resend after 2 losses, no congestion control. Cam
                            // never throttles us so disabling congestion is safe.
                            nodelay(true, 10, 2, true)
                            setMtu(1400)
                        }
                        Log.i(TAG, "kcp init: conv=0x${"%08x".format(conv)} (first KCP packet)")
                    }

                    // Feed datagram and drain any newly reassembled messages.
                    val datagram = recvBuf.copyOfRange(0, len)
                    kcpLock.withLock {
                        val k = kcp ?: return@withLock
                        val inBuf: ByteBuf = Unpooled.wrappedBuffer(datagram)
                        k.input(inBuf, true, System.currentTimeMillis())
                        // Use mergeRecv(): kcp-base returns each KCP message as a
                        // single merged ByteBuf. The earlier worry — that this
                        // would concatenate multiple distinct messages — was
                        // actually the cam packing multiple inner-frames into
                        // one KCP message, which we now handle by scanning for
                        // the d11df210 magic inside [consumeKcpMessage]. The
                        // List<ByteBuf> overload of recv() instead splits a
                        // single message into wire-fragments, and processing
                        // each fragment independently chops bytes mid-stream.
                        while (k.canRecv()) {
                            val merged: ByteBuf = k.mergeRecv() ?: break
                            val outLen = merged.readableBytes()
                            if (outLen <= 0) { merged.release(); continue }
                            val out = ByteArray(outLen)
                            merged.readBytes(out)
                            merged.release()
                            if (debugN < 6) {
                                Log.d(TAG, "kcp emit #${++debugN} len=${out.size}" +
                                    " firstBytes=${out.take(16).joinToString("") { "%02x".format(it) }}")
                            }
                            consumeKcpMessage(out) { frame -> trySend(frame) }
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "recv failed: ${e.message}")
                    break
                }
            }
        } finally {
            updateJob.cancel()
            updateScope.cancel()
            kcp?.let { runCatching { it.release() } }
            try { socket.close() } catch (_: Exception) {}
        }
        close()
        awaitClose {
            updateJob.cancel()
            updateScope.cancel()
            try { socket.close() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sends the rtp2p stop command and tears the UDP side down. Idempotent.
     * Live-mode stops use the same JSON shape as start (no `file` field) per
     * the CloudSpirit PCAP (cookie 71).
     */
    suspend fun close() {
        if (closed) return
        closed = true
        heartbeatJob?.cancel()
        scope.cancel()
        withContext(Dispatchers.IO) {
            try {
                val isLive = fileName == "<live>"
                val stopBody = JSONObject().apply {
                    put("peer", session.uidx)
                    put("deviceid", session.deviceId)
                    put("start", 0)
                    put("camid", camid)
                    if (!isLive) put("file", fileName)
                    put("timeout", 600)
                    put("flag", 0)
                    put("ver", 1)
                }
                session.rtp2p(stopBody)
                Log.i(TAG, "rtp2p stop OK for $fileName")
            } catch (e: Exception) {
                Log.w(TAG, "rtp2p stop failed: ${e.message}")
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
