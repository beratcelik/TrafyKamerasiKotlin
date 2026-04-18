package com.example.trafykamerasikotlin.data.allwinner

import android.util.Log
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
     * Cold Flow of raw inbound UDP payloads. Each datagram is emitted as its own
     * ByteArray with no reassembly. First 3 packets are hex-dumped so the caller can
     * inspect whether datagrams are raw MPEG-TS, RTP-wrapped, or something else.
     */
    fun packets(): Flow<ByteArray> = callbackFlow {
        val recvBuf = ByteArray(RECV_BUFFER_SIZE)
        val packet = DatagramPacket(recvBuf, recvBuf.size)
        var n = 0
        try {
            while (!closed) {
                try {
                    socket.receive(packet)
                    val copy = recvBuf.copyOfRange(0, packet.length)
                    if (n < 3) {
                        Log.d(TAG, "rx UDP #${++n} len=${copy.size} first32=" +
                            copy.take(32).joinToString("") { "%02x".format(it) })
                    }
                    trySend(copy)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "recv failed: ${e.message}")
                    break
                }
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
        awaitClose {
            try { socket.close() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /** Sends the rtp2p stop command and tears the UDP side down. Idempotent. */
    suspend fun close() {
        if (closed) return
        closed = true
        heartbeatJob?.cancel()
        scope.cancel()
        withContext(Dispatchers.IO) {
            try {
                val stopBody = JSONObject().apply {
                    put("peer", session.uidx)
                    put("deviceid", session.deviceId)
                    put("start", 0)
                    put("camid", camid)
                    put("file", fileName)
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
