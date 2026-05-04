package com.example.trafykamerasikotlin.data.allwinner

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny single-client HTTP server that streams live MPEG-TS over loopback.
 *
 * Why we need this instead of a `file://` URL: IjkPlayer's FFmpeg uses
 * `read()` on file paths and stops at EOF. A growing TS file therefore
 * plays the bytes available at open-time and freezes — exactly what we
 * saw on the live preview. HTTP semantics are different: the server
 * keeps the connection open and FFmpeg keeps reading until the socket
 * closes, so as long as we keep [write]ing the player keeps decoding.
 *
 * Bound to 127.0.0.1 only, no auth, single-connection — strictly an
 * intra-process pipe between [MpegTsMuxer] and IjkPlayer.
 */
class LiveTsHttpServer {

    companion object { private const val TAG = "Trafy.LiveTsServer" }

    private val server: ServerSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    /** URL the player should hit. Available immediately after construction. */
    val url: String = "http://127.0.0.1:${server.localPort}/stream.ts"

    @Volatile private var clientOut: OutputStream? = null
    @Volatile private var clientSock: Socket? = null
    @Volatile private var stopped = false

    init {
        thread(name = "LiveTsHttpServer-accept", isDaemon = true) {
            try {
                val sock = server.accept()
                sock.tcpNoDelay = true
                Log.i(TAG, "client connected from ${sock.remoteSocketAddress}")
                val input = sock.getInputStream()
                val req = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    req.append(b.toChar())
                    if (req.endsWith("\r\n\r\n")) break
                    if (req.length > 4096) break
                }
                val out = sock.getOutputStream()
                out.write(
                    ("HTTP/1.0 200 OK\r\n" +
                        "Content-Type: video/mp2t\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n" +
                        "\r\n").toByteArray()
                )
                synchronized(this) {
                    clientSock = sock
                    clientOut = out
                }
                out.flush()
            } catch (e: Exception) {
                if (!stopped) Log.w(TAG, "accept loop ended: ${e.message}")
            }
        }
    }

    /**
     * Forwards [data] to the connected player, or drops it if no client
     * has connected yet. Pre-connect bytes used to be buffered so the
     * player wouldn't miss the first keyframe — but that buffer ended up
     * fed to IjkPlayer in one rush and translated directly into permanent
     * lag. Dropping pre-connect bytes means a black screen until the
     * first keyframe arrives after connect (≤1 GOP = ~1 s), which is
     * far better than seconds of persistent latency.
     */
    fun write(data: ByteArray) {
        val out = clientOut ?: return
        try {
            out.write(data)
        } catch (e: IOException) {
            if (!stopped) Log.w(TAG, "client write failed: ${e.message}")
            close()
        }
    }

    /** Best-effort flush — IjkPlayer is happier seeing PAT/PMT promptly. */
    fun flush() {
        try { clientOut?.flush() } catch (_: Exception) {}
    }

    fun close() {
        if (stopped) return
        stopped = true
        try { clientOut?.close() } catch (_: Exception) {}
        try { clientSock?.close() } catch (_: Exception) {}
        try { server.close() } catch (_: Exception) {}
    }
}
