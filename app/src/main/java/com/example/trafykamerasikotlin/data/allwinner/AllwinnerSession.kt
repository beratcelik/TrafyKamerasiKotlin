package com.example.trafykamerasikotlin.data.allwinner

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * High-level session on top of [AllwinnerClient]:
 *   1. `login` → obtains `uidx`
 *   2. `bondlist` → discovers the device's 32-hex `deviceid` (needed as `peer`
 *      for every `relay` sub-command)
 *   3. Initial `relay:getsettings` — cached so handshake + first fetchAll are cheap
 *   4. Starts a wakeup loop that keeps the device from sleeping the socket
 *   5. Exposes [relay] for typed sub-commands like `getsettings`, `setsettings`, etc.
 *
 * Process-wide there is at most one active [AllwinnerSession] (see [AllwinnerSessionHolder]).
 * The handshake opens it; the settings repository reuses it.
 */
internal class AllwinnerSession private constructor(
    private val client: AllwinnerClient,
    val deviceId: String,
    val uidx: Int,
) {

    companion object {
        private const val TAG = "Trafy.Allwinner"
        private const val APP_ID  = "ysjl"
        private const val APP_KEY = "5jsEuP8Pzt49Cbgq"
        // OEM sends wakeup every ~1 s, which is noisy. 20 s is well under any realistic
        // idle-kick timer and keeps the socket alive.
        private const val WAKEUP_INTERVAL_MS = 20_000L

        suspend fun open(ip: String): AllwinnerSession? {
            val client = try {
                AllwinnerClient.connect(ip, 8000)
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e.message}")
                return null
            }
            return try {
                // Register a push listener for bondlist BEFORE login, because the device
                // automatically pushes bondlist (without a cookie) right after login.
                // Sending an explicit "bondlist" request doesn't get a cookie-matched response.
                val bondlistDeferred = client.listenForPush("bondlist")

                val login = client.request(
                    cmd = "login",
                    body = JSONObject().apply {
                        put("app", APP_ID)
                        put("key", APP_KEY)
                        put("maxmsg", 0)
                        put("user", JSONObject().apply {
                            put("id", 1000652941)
                            put("uuid", "WX:local")
                            put("ut", 0)
                            put("but", 0)
                            put("lang", "tr")
                        })
                    }
                )
                if (login.optInt("ret", -1) != 0) {
                    Log.w(TAG, "login failed: $login")
                    client.close()
                    return null
                }
                val uidx = login.optInt("id", 100)
                Log.i(TAG, "login OK → uidx=$uidx")

                // Wait for the auto-pushed bondlist to arrive (device sends it after login).
                val bond = withTimeout(10_000L) { bondlistDeferred.await() }
                val list = bond.optJSONArray("list")
                if (list == null || list.length() == 0) {
                    Log.w(TAG, "bondlist push was empty — cannot derive deviceid")
                    client.close()
                    return null
                }
                val deviceId = list.getJSONObject(0).optString("deviceid")
                if (deviceId.isEmpty()) {
                    Log.w(TAG, "bondlist entry has no deviceid: ${list.getJSONObject(0)}")
                    client.close()
                    return null
                }
                Log.i(TAG, "bondlist OK → deviceid=$deviceId")

                val session = AllwinnerSession(client, deviceId, uidx)
                val initialSettings = session.relayInternal("getsettings", JSONObject())
                if (initialSettings.optInt("ret", -1) != 0) {
                    Log.w(TAG, "initial getsettings failed: $initialSettings")
                    session.close()
                    return null
                }
                session.cachedSettings = initialSettings
                Log.i(TAG, "initial getsettings OK: fwid=${initialSettings.optString("fwid")}")
                session.startWakeupLoop()
                session
            } catch (e: Exception) {
                Log.e(TAG, "open() failed: ${e.message}", e)
                client.close()
                null
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeupJob: Job? = null
    @Volatile private var closed = false
    @Volatile private var cachedSettings: JSONObject = JSONObject()

    /** Wraps a sub-command inside the standard `relay` envelope and returns the response. */
    suspend fun relay(msg: String, extra: JSONObject = JSONObject()): JSONObject {
        if (closed) throw IllegalStateException("AllwinnerSession closed")
        return relayInternal(msg, extra)
    }

    /**
     * Sends the top-level `rtp2p` command (used to start/stop UDP media streams
     * for recorded-file playback and live camera streams). Unlike relay sub-commands,
     * `rtp2p` has its own cookie-matched response and is routed via [AllwinnerClient.request].
     */
    suspend fun rtp2p(body: JSONObject): JSONObject {
        if (closed) throw IllegalStateException("AllwinnerSession closed")
        return client.request("rtp2p", body)
    }

    private suspend fun relayInternal(msg: String, extra: JSONObject): JSONObject {
        extra.put("msg", msg)
        extra.put("peer", deviceId)
        extra.put("timeout", 600)
        // Register the push listener BEFORE sending — relay responses have no cookie field;
        // they're routed by the composite key "relay:<msg>" in AllwinnerClient.handleFrame().
        val deferred = client.listenForPush("relay:$msg")
        client.sendFrame("relay", extra)
        return withTimeout(10_000L) { deferred.await() }.also { resp ->
            if (resp.optInt("ret", -1) != 0) {
                Log.w(TAG, "relay:$msg ret=${resp.optInt("ret")} err=${resp.optString("err")}")
            }
        }
    }

    /** Fetches the complete settings blob; also refreshes [cachedSettings]. */
    suspend fun getSettings(): JSONObject? {
        val resp = relay("getsettings")
        if (resp.optInt("ret", -1) != 0) return null
        cachedSettings = resp
        return resp
    }

    /** Last successful getsettings response. */
    fun lastSettings(): JSONObject = cachedSettings

    private fun startWakeupLoop() {
        wakeupJob = scope.launch {
            while (!closed) {
                delay(WAKEUP_INTERVAL_MS)
                if (closed) break
                // If the TCP socket died, don't keep firing writes into a dead pipe — mark
                // the session closed so AllwinnerSessionHolder reopens on next use.
                if (!client.isAlive()) {
                    Log.w(TAG, "wakeup: client is no longer alive, closing session")
                    close()
                    break
                }
                try {
                    // Wakeup responses (if any) also have no cookie field — use sendFrame to
                    // avoid orphaned pending-cookie slots accumulating in AllwinnerClient.
                    val body = JSONObject().apply {
                        put("msg", "wakeup")
                        put("peer", deviceId)
                        put("feature", 1)
                        put("lease", 60)
                        put("uidx", uidx)
                        put("timeout", 600)
                    }
                    client.sendFrame("relay", body)
                } catch (e: Exception) {
                    Log.w(TAG, "wakeup failed: ${e.message}")
                    // If the send failed because the socket is now dead, stop looping.
                    if (!client.isAlive()) {
                        Log.w(TAG, "wakeup: client died during send, closing session")
                        close()
                        break
                    }
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        wakeupJob?.cancel()
        scope.cancel()
        client.close()
    }

    /** True while the session's TCP socket + reader loop are healthy. */
    fun isAlive(): Boolean = !closed && client.isAlive()
}

/**
 * Process-wide holder for the single active [AllwinnerSession]. The handshake opens the
 * session and writes it here; [AllwinnerSettingsRepository] reads it back on its first
 * `fetchAll`. This avoids double-login (which races on the same socket) while keeping
 * the repository constructible the same way as `HiDvrSettingsRepository`.
 */
internal object AllwinnerSessionHolder {
    private const val TAG = "Trafy.Allwinner"
    @Volatile var current: AllwinnerSession? = null
    // Serialises concurrent reconnect attempts so two simultaneous dead-session
    // detections don't race to open two fresh sockets.
    private val reconnectMutex = Mutex()

    fun replace(new: AllwinnerSession?) {
        current?.close()
        current = new
    }

    fun clear() {
        current?.close()
        current = null
    }

    /**
     * Returns an alive session for [deviceIp], transparently reopening if the current
     * one is dead or absent. Call sites that used to do
     *   `AllwinnerSessionHolder.current ?: AllwinnerSession.open(ip).also { replace(it) }`
     * should use this instead so they get auto-reconnect on stale TCP.
     */
    suspend fun requireAlive(deviceIp: String): AllwinnerSession? = reconnectMutex.withLock {
        current?.takeIf { it.isAlive() }?.let { return@withLock it }
        val stale = current
        if (stale != null) {
            Log.i(TAG, "requireAlive: current session is dead — reopening")
            stale.close()
            current = null
        }
        val fresh = AllwinnerSession.open(deviceIp) ?: return@withLock null
        current = fresh
        fresh
    }
}
