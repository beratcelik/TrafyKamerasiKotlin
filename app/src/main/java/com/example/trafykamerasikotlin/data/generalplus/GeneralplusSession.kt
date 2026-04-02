package com.example.trafykamerasikotlin.data.generalplus

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a single-use TCP session to a GeneralPlus dashcam.
 *
 * Session lifecycle:
 *   1. Open TCP socket to [CAMERA_IP]:[CAMERA_PORT].
 *   2. Send AuthDevice (16-byte "GPSOCKET" packet) — required handshake.
 *   3. Read AuthDevice response; verify Type == ACK (0x02).
 *   4. Execute [block] with [send] and [receive] helpers.
 *   5. Close socket in finally block (always).
 *
 * All I/O runs on [Dispatchers.IO].
 * Returns null if any step fails (TCP error, NAK, or exception).
 *
 * Reference: Q6/h.java (GPlusMgr) + CamWrapper.java in viidure-jadx.
 */
object GeneralplusSession {

    private const val TAG = "Trafy.GPSession"

    const val CAMERA_IP   = "192.168.25.1"
    const val CAMERA_PORT = 8081
    private const val CONNECT_TIMEOUT_MS = 4_000
    // 8 s: RestartStreaming ACK arrives ~5 s after SetMode in a fresh session;
    // 5 s was too tight and caused receive() to time out before the ACK arrived.
    private const val READ_TIMEOUT_MS    = 8_000

    /** WiFi network to bind the TCP socket to (mirrors DashcamHttpClient). */
    @Volatile private var boundNetwork: Network? = null

    /**
     * Binds all subsequent TCP sessions to [network].
     * Call with null to restore default (unbound) behaviour.
     * Should be called from DashcamViewModel alongside DashcamHttpClient.bindToNetwork().
     */
    fun bindToNetwork(network: Network?) {
        boundNetwork = network
        Log.i(TAG, "bindToNetwork: ${if (network != null) "bound to $network" else "unbound"}")
    }

    /** Returns the currently bound WiFi network, or null if unbound. */
    fun getBoundNetwork(): Network? = boundNetwork

    // ── Held session (kept alive during RTSP file playback) ───────────────

    @Volatile private var heldSocket: Socket? = null
    @Volatile private var heldOut: java.io.OutputStream? = null
    @Volatile private var heldIn: java.io.InputStream? = null

    /**
     * Releases any held playback session. Safe to call if no session is held.
     */
    fun releaseHeldSession() {
        try { heldSocket?.close() } catch (_: Exception) {}
        heldSocket = null
        heldOut = null
        heldIn = null
        Log.d(TAG, "Held session released")
    }

    /**
     * Executes [block] on the currently held session.
     * Returns null if no session is held or if it has become invalid.
     */
    suspend fun <T> onHeldSession(
        block: suspend (
            send      : (mode: Byte, cmdId: Byte) -> Unit,
            sendPacket: (ByteArray) -> Unit,
            receive   : (expectedCmdId: Byte) -> GeneralplusProtocol.Response?,
        ) -> T,
    ): T? {
        val out   = heldOut ?: return null
        val input = heldIn  ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val send: (Byte, Byte) -> Unit = { mode, cmdId ->
                    val idx = nextIdx()
                    val pkt = GeneralplusProtocol.buildCommand(idx, mode, cmdId)
                    out.write(pkt)
                    out.flush()
                    Log.d(TAG, ">> CMD mode=$mode cmdId=$cmdId idx=$idx")
                }
                val sendPacket: (ByteArray) -> Unit = { pkt ->
                    val idx = nextIdx()
                    val patched = pkt.copyOf()
                    patched[9] = idx
                    out.write(patched)
                    out.flush()
                    Log.d(TAG, ">> PKT len=${patched.size} mode=${patched[10]} cmdId=${patched[11]} idx=$idx")
                }
                val receive: (Byte) -> GeneralplusProtocol.Response? = drain@{ expectedCmdId ->
                    repeat(10) {
                        val resp = GeneralplusProtocol.readResponse(input) ?: run {
                            Log.w(TAG, "<< readResponse null waiting for cmdId=$expectedCmdId")
                            return@drain null
                        }
                        Log.d(TAG, "<< resp type=${resp.type} cmdId=${resp.cmdId} dataLen=${resp.data.size}")
                        if (resp.cmdId == expectedCmdId) {
                            return@drain if (resp.isAck) resp else null
                        }
                    }
                    Log.w(TAG, "receive: no ACK for cmdId=$expectedCmdId after 10 packets")
                    null
                }
                block(send, sendPacket, receive)
            } catch (e: Exception) {
                Log.e(TAG, "Held session error: ${e.message}")
                releaseHeldSession()
                null
            }
        }
    }

    /** Global sequence counter — wraps at 255 (uint8). */
    private val cmdIndex = AtomicInteger(0)

    private fun nextIdx(): Byte = (cmdIndex.getAndIncrement() and 0xFF).toByte()

    /**
     * Opens a GeneralPlus TCP session, authenticates, then runs [block].
     *
     * [block] receives:
     *  - **send(mode, cmdId)** — writes a 12-byte command packet.
     *  - **sendPacket(bytes)** — writes a pre-built packet of any size; the cmdIdx
     *    byte (offset 9) is patched with the session's internal sequence counter.
     *  - **receive(expectedCmdId)** — reads responses until one matching
     *    [expectedCmdId] with Type==ACK is found; returns it or null on error.
     *
     * Returns null if the socket fails to connect or AuthDevice is rejected.
     */
    suspend fun <T> withSession(
        holdOpen: Boolean = false,
        block: suspend (
            send      : (mode: Byte, cmdId: Byte) -> Unit,
            sendPacket: (ByteArray) -> Unit,
            receive   : (expectedCmdId: Byte) -> GeneralplusProtocol.Response?,
        ) -> T,
    ): T? = withContext(Dispatchers.IO) {
        // Release any previous held session before opening a new one.
        if (holdOpen) releaseHeldSession()

        // Use the WiFi network's SocketFactory so the TCP socket is routed through wlan0,
        // not the default (cellular) interface. Falls back to a plain Socket if unbound or
        // if the bound network's SocketFactory throws (e.g. EPERM when network is stale).
        var socket: Socket? = null
        try {
            socket = try {
                boundNetwork?.socketFactory?.createSocket() ?: Socket()
            } catch (_: Exception) {
                Log.w(TAG, "Network-bound socket failed, falling back to plain socket")
                Socket()
            }
            socket.connect(InetSocketAddress(CAMERA_IP, CAMERA_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            Log.i(TAG, "TCP connected to $CAMERA_IP:$CAMERA_PORT")

            val out   = socket.getOutputStream()
            val input = socket.getInputStream()

            // ── Handshake: AuthDevice ──────────────────────────────────────
            val authPacket = GeneralplusProtocol.buildAuthDevice(cmdIdx = 0x00)
            out.write(authPacket)
            out.flush()
            Log.d(TAG, ">> AuthDevice sent (${authPacket.size} bytes)")

            val authResp = GeneralplusProtocol.readResponse(input)
            if (authResp == null || !authResp.isAck) {
                Log.e(TAG, "AuthDevice failed: resp=$authResp")
                return@withContext null
            }
            Log.i(TAG, "AuthDevice OK (cmdIdx=${authResp.cmdIdx})")

            // ── Helpers for callers ────────────────────────────────────────

            val send: (Byte, Byte) -> Unit = { mode, cmdId ->
                val idx = nextIdx()
                val pkt = GeneralplusProtocol.buildCommand(idx, mode, cmdId)
                out.write(pkt)
                out.flush()
                Log.d(TAG, ">> CMD mode=$mode cmdId=$cmdId idx=$idx")
            }

            val sendPacket: (ByteArray) -> Unit = { pkt ->
                val idx = nextIdx()
                val patched = pkt.copyOf()
                patched[9] = idx
                out.write(patched)
                out.flush()
                Log.d(TAG, ">> PKT len=${patched.size} mode=${patched[10]} cmdId=${patched[11]} idx=$idx")
            }

            val receive: (Byte) -> GeneralplusProtocol.Response? = drain@{ expectedCmdId ->
                repeat(10) {
                    val resp = GeneralplusProtocol.readResponse(input) ?: run {
                        Log.w(TAG, "<< readResponse null waiting for cmdId=$expectedCmdId")
                        return@drain null
                    }
                    Log.d(TAG, "<< resp type=${resp.type} cmdId=${resp.cmdId} dataLen=${resp.data.size}")
                    if (resp.cmdId == expectedCmdId) {
                        // NAK for the expected command = immediate failure (no point looping further)
                        return@drain if (resp.isAck) resp else null
                    }
                }
                Log.w(TAG, "receive: no ACK for cmdId=$expectedCmdId after 10 packets")
                null
            }

            val result = block(send, sendPacket, receive)

            // If requested, keep the TCP connection alive for RTSP playback.
            // The camera stops streaming when the control connection drops.
            if (holdOpen) {
                heldSocket = socket
                heldOut = out
                heldIn = input
                socket = null  // prevent finally from closing
                Log.i(TAG, "Session held open for playback")
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "Session error: ${e.message}")
            null
        } finally {
            if (socket != null) {
                try { socket.close() } catch (_: Exception) {}
                Log.d(TAG, "TCP socket closed")
            }
        }
    }
}
