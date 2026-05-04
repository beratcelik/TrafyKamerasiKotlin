package com.example.trafykamerasikotlin.data.media

import android.app.Application
import android.util.Log
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerRtp2pClient
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import com.example.trafykamerasikotlin.data.allwinner.LiveTsHttpServer
import com.example.trafykamerasikotlin.data.allwinner.MpegTsMuxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live-preview repository for Allwinner V853 (A19) devices.
 *
 * Reverse-engineered from a CloudSpirit PCAP capture: live preview rides on
 * the same `rtp2p` UDP transport as recorded-file playback, but the start
 * command omits both `file` and `time` fields. Camera replies with
 * port 2222 + a session pwd, then begins streaming MPEG-TS as soon as the
 * client's heartbeat loop kicks in (200 ms cadence).
 *
 * We drain incoming UDP into a growing temp file (`allwinner_live.ts`) and
 * hand its `file://` URI to the player once enough bytes have buffered. The
 * file keeps growing while the user watches; we just keep filling it.
 */
class AllwinnerLiveRepository(private val app: Application) {

    companion object {
        private const val TAG = "Trafy.AllwinnerLive"

        /** Hand the file URI to the player once we've buffered this many bytes. */
        private const val BUFFERED_EMIT_BYTES = 256L * 1024L
        /** …or after this many ms with at least one packet, whichever comes first. */
        private const val BUFFERED_EMIT_DELAY_MS = 1_500L
        /** Camera never sent the first packet within this window → bail out fast. */
        private const val INITIAL_RX_TIMEOUT_MS = 6_000L
    }

    /**
     * Cancels any in-flight stream. Keeps the temp file in place so the
     * developer can `adb pull` it for offline analysis (ffplay / mediainfo).
     * Production behaviour can re-enable the delete once the format is
     * confirmed end-to-end.
     */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
        // Intentionally not deleting cache/allwinner_live.ts yet.
    }

    @Volatile private var currentJob: Job? = null

    /**
     * Opens an rtp2p live session for [camid] (0=front, 1=back), drains UDP
     * into a temp file, and invokes [onBuffered] once the buffer threshold
     * is reached so the UI can hand the URI to a player. Suspends until the
     * stream ends (cancellation, EOS, or fatal error).
     */
    suspend fun stream(
        deviceIp: String,
        camid: Int,
        onBuffered: (uri: String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val session = AllwinnerSessionHolder.requireAlive(deviceIp)
        if (session == null) {
            Log.w(TAG, "stream: no Allwinner session available")
            return@withContext false
        }

        val client = AllwinnerRtp2pClient.openLive(session, camid)
            ?: return@withContext false.also {
                Log.w(TAG, "stream: openLive returned null")
            }

        // Live MPEG-TS over local HTTP. Going via a `file://` path made
        // FFmpeg stop at EOF as soon as it caught up to the writer; HTTP
        // keeps the read open as long as the server keeps writing.
        val tsServer = LiveTsHttpServer()
        val muxer = MpegTsMuxer(object : java.io.OutputStream() {
            override fun write(b: Int) {
                tsServer.write(byteArrayOf(b.toByte()))
            }
            override fun write(buf: ByteArray, off: Int, len: Int) {
                if (off == 0 && len == buf.size) tsServer.write(buf)
                else tsServer.write(buf.copyOfRange(off, off + len))
            }
            override fun flush() { tsServer.flush() }
        })
        // DEBUG: dump the raw H.264 stream we're feeding the muxer (one
        // frame per Flow emission, 4-byte BE length prefix). Used for
        // offline analysis with ffprobe / hex inspection.
        val debugRaw = File(app.cacheDir, "allwinner_live_raw.bin").apply {
            if (exists()) delete()
        }
        val debugRawOut = java.io.FileOutputStream(debugRaw)

        val receivedBytes = AtomicLong(0)
        val packetsSeen   = AtomicInteger(0)
        var bufferedEmitted = false
        val started = System.currentTimeMillis()
        val lastRxTime = AtomicLong(started)

        try {
            coroutineScope {
                // Watchdog: if the device never sent a packet within
                // INITIAL_RX_TIMEOUT_MS, stop trying. The collect loop can't
                // self-terminate when the input is silent.
                val watchdog = launch {
                    while (isActive) {
                        delay(500L)
                        val elapsed = System.currentTimeMillis() - started
                        if (packetsSeen.get() == 0 && elapsed >= INITIAL_RX_TIMEOUT_MS) {
                            Log.w(TAG, "watchdog: no packets in ${elapsed}ms — device refused to stream")
                            break
                        }
                    }
                    try { client.close() } catch (_: Exception) {}
                }

                try {
                    client.packets().collect { payload ->
                        // Debug dump first: 4-byte BE length prefix, then payload.
                        debugRawOut.write(byteArrayOf(
                            (payload.size ushr 24 and 0xFF).toByte(),
                            (payload.size ushr 16 and 0xFF).toByte(),
                            (payload.size ushr  8 and 0xFF).toByte(),
                            (payload.size        and 0xFF).toByte(),
                        ))
                        debugRawOut.write(payload)
                        debugRawOut.flush()
                        muxer.writeFrame(payload)
                        muxer.flush()
                        val total = receivedBytes.addAndGet(payload.size.toLong())
                        val n = packetsSeen.incrementAndGet()
                        lastRxTime.set(System.currentTimeMillis())
                        if (n <= 3) {
                            Log.d(TAG, "rx packet #$n len=${payload.size}" +
                                " firstBytes=${payload.take(16).joinToString("") { "%02x".format(it) }}")
                        }
                        if (!bufferedEmitted &&
                            (total >= BUFFERED_EMIT_BYTES ||
                             System.currentTimeMillis() - started >= BUFFERED_EMIT_DELAY_MS)
                        ) {
                            bufferedEmitted = true
                            onBuffered(tsServer.url)
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stream: collect failed: ${e.message}")
        } finally {
            try { tsServer.close() } catch (_: Exception) {}
            try { debugRawOut.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
        val n = packetsSeen.get()
        Log.i(TAG, "stream: done, $n packets, ${receivedBytes.get()} bytes")
        n > 0
    }
}
