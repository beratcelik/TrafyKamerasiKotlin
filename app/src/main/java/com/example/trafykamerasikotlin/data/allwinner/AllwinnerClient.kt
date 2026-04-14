package com.example.trafykamerasikotlin.data.allwinner

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-level TCP client for the Allwinner V853 control channel (port 8000).
 *
 * Owns the Socket + a background reader coroutine that demuxes responses by `cookie`
 * into `CompletableDeferred` slots. Callers get a coroutine-friendly `request()` that
 * returns the response JSON.
 *
 * This class does not speak the `login` / `relay` / `bondlist` semantics — that's in
 * [AllwinnerSession]. It only handles framing, cookie matching, and lifecycle.
 */
internal class AllwinnerClient private constructor(
    private val socket: Socket,
    private val output: OutputStream,
    private val input: InputStream,
) {

    companion object {
        private const val TAG = "Trafy.Allwinner"

        suspend fun connect(ip: String, port: Int = 8000): AllwinnerClient = withContext(Dispatchers.IO) {
            Log.i(TAG, "connect() → $ip:$port")
            val sock = AllwinnerNetwork.createSocket(ip, port)
            sock.tcpNoDelay = true
            AllwinnerClient(sock, sock.getOutputStream(), sock.getInputStream()).also { it.startReader() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cookieSeq = AtomicInteger(37)  // matches OEM app starting value, harmless
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    // One-shot listeners for unsolicited push messages keyed by "f" field value.
    // Register before the event can arrive; removed on first match.
    private val pushListeners = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var readerJob: Job? = null
    @Volatile private var closed = false

    /**
     * Registers a one-shot listener for the next unsolicited push with the given [f] value
     * (the `"f"` field in the JSON, e.g. `"bondlist"`). Must be registered BEFORE the event
     * can arrive. Returns a [CompletableDeferred] that completes when the push is received.
     */
    fun listenForPush(f: String): CompletableDeferred<JSONObject> {
        val d = CompletableDeferred<JSONObject>()
        pushListeners[f] = d
        return d
    }

    /**
     * Sends a frame WITHOUT registering a cookie listener. Use for commands whose
     * response arrives as an unsolicited push (e.g. `relay` sub-commands).
     * Caller is responsible for registering a push listener via [listenForPush] before calling.
     * Cookie is auto-generated and NOT tracked — any response must be caught via [listenForPush].
     */
    suspend fun sendFrame(cmd: String, body: JSONObject) = withContext(Dispatchers.IO) {
        val cookie = cookieSeq.getAndIncrement()
        val jsonBytes = body.toString().toByteArray(Charsets.UTF_8)
        val header = "$cmd:$cookie".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(header.size + jsonBytes.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(jsonBytes, 0, payload, header.size, jsonBytes.size)
        Log.d(TAG, "→ $cmd:$cookie (no-reply) ${body.toString().take(200)}")
        synchronized(output) { AllwinnerFrameCodec.writeFrame(output, payload) }
    }

    /**
     * Sends a request and suspends until the matching response arrives (matched by `cookie`).
     *
     * [cmd]   — command name such as "login", "relay", "bondlist", "rtp2p".
     * [body]  — JSON object to serialize; `cookie` is added automatically and reflected
     *           in the response JSON as `"cookie":N`.
     */
    suspend fun request(
        cmd: String,
        body: JSONObject,
        timeoutMs: Long = 10_000L,
    ): JSONObject {
        val cookie = cookieSeq.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[cookie] = deferred

        val jsonBytes = body.toString().toByteArray(Charsets.UTF_8)
        val header = "$cmd:$cookie".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(header.size + jsonBytes.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(jsonBytes, 0, payload, header.size, jsonBytes.size)

        try {
            Log.d(TAG, "→ $cmd:$cookie ${body.toString().take(300)}")
            withContext(Dispatchers.IO) {
                synchronized(output) { AllwinnerFrameCodec.writeFrame(output, payload) }
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(cookie)
            throw e
        }
    }

    private fun startReader() {
        readerJob = scope.launch {
            try {
                while (!closed) {
                    val frame = AllwinnerFrameCodec.readFrame(input)
                    handleFrame(frame)
                }
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "reader loop ended: ${e.message}")
                // Fail every pending request and push listener so callers don't hang forever.
                pending.values.forEach { it.completeExceptionally(e) }
                pending.clear()
                pushListeners.values.forEach { it.completeExceptionally(e) }
                pushListeners.clear()
            }
        }
    }

    private fun handleFrame(frame: ByteArray) {
        val text = try { String(frame, Charsets.UTF_8) } catch (_: Exception) {
            Log.w(TAG, "Non-UTF8 frame of ${frame.size} bytes — dropping")
            return
        }
        // Some responses are pure JSON; a few push messages on some firmwares may be
        // prefixed (not seen in our pcap, but tolerate them).
        val jsonStart = text.indexOf('{')
        if (jsonStart < 0) {
            Log.v(TAG, "← non-JSON frame: ${text.take(120)}")
            return
        }
        val json = try { JSONObject(text.substring(jsonStart)) } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed: ${e.message} — body=${text.take(200)}")
            return
        }
        val cookie = json.optInt("cookie", -1)
        if (cookie < 0) {
            val f = json.optString("f")
            // relay responses don't carry a cookie — key them by "relay:<msg>"
            val listenerKey = if (f == "relay") {
                val msg = json.optString("msg")
                if (msg.isNotEmpty()) "relay:$msg" else f
            } else {
                f
            }
            val listener = pushListeners.remove(listenerKey)
            if (listener != null) {
                Log.d(TAG, "← push (listened): key=$listenerKey")
                listener.complete(json)
            } else {
                Log.v(TAG, "← push: f=$f msg=${json.optString("msg")}")
            }
            return
        }
        Log.d(TAG, "← cookie=$cookie f=${json.optString("f")} ret=${json.optInt("ret", 0)}")
        val slot = pending.remove(cookie)
        if (slot == null) {
            Log.w(TAG, "← orphan response cookie=$cookie (no pending request)")
            return
        }
        slot.complete(json)
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket.close() } catch (_: Exception) {}
        scope.cancel()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("client closed")) }
        pending.clear()
        pushListeners.values.forEach { it.completeExceptionally(IllegalStateException("client closed")) }
        pushListeners.clear()
    }
}
