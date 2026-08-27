package tr.trafy.kamera.data.media

import android.util.Log
import tr.trafy.kamera.data.network.DashcamHttpClient
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages live RTSP streaming for Easytech/Allwinner dashcams.
 *
 * Protocol (from EeasytechProtocol.smali registerClient / togglePreview / unregisterClient):
 *  1. GET /app/enterrecorder          — register client before streaming
 *  2. Camera count detection (two strategies, see queryCamNum)
 *  3. RTSP: rtsp://192.168.169.1:554  — single RTSP URL serves all cameras
 *  4. GET /app/setparamvalue?param=switchcam&value=INDEX  — switch active camera
 *  5. GET /app/exitrecorder           — unregister client on leave
 *
 * Camera indices for 3-camera device (confirmed from getRtsps() in smali):
 *   index 0 = Camera 1, index 1 = Camera 2, index 2 = Camera 3
 *
 * All cameras share the same RTSP URL; switchcam changes which feed is streamed.
 * No recording stop/start required — live streaming is independent of recording.
 */
class EeasytechLiveRepository {

    companion object {
        private const val TAG = "Trafy.EeasytechLive"

        /** The single RTSP URL for all cameras on Easytech devices. */
        const val RTSP_URL = "rtsp://192.168.169.1:554"
    }

    /**
     * Registers the client for live streaming and queries the camera count.
     * Returns the number of available cameras (1–3); defaults to 1 on error.
     */
    suspend fun enterLive(deviceIp: String): Int {
        val ok = DashcamHttpClient.probe("http://$deviceIp:80/app/enterrecorder")
        Log.d(TAG, "enterrecorder → $ok")
        return queryCamNum(deviceIp)
    }

    /**
     * Switches the RTSP stream to the given camera index (0-based).
     * Returns true if the camera responded with HTTP 200.
     */
    suspend fun switchCamera(deviceIp: String, index: Int): Boolean {
        val url = "http://$deviceIp/app/setparamvalue?param=switchcam&value=$index"
        val ok  = DashcamHttpClient.probe(url)
        Log.d(TAG, "switchcam $index → $ok")
        return ok
    }

    /**
     * Unregisters the client and tells the cam to resume SD recording.
     * The 500ms gap between `exitrecorder` and `rec=1` is intentional —
     * sending the resume immediately while the cam is still transitioning
     * out of recorder mode produces `{"result":1,"info":"set fail"}` and
     * has been observed to wedge the HTTP server entirely (firmware bug
     * on the HI3516CV610-class boards used by Trafy Tres Pro). Letting
     * the cam settle first avoids the lockup.
     */
    suspend fun exitLive(deviceIp: String) {
        if (EeasytechCleanupLock.ranRecently()) {
            Log.d(TAG, "exitLive: skipped (sibling cleanup ran recently)")
            return
        }
        EeasytechCleanupLock.mutex.withLock {
            if (EeasytechCleanupLock.ranRecently()) {
                Log.d(TAG, "exitLive: skipped inside lock")
                return@withLock
            }
            val exitOk = DashcamHttpClient.probe("http://$deviceIp/app/exitrecorder")
            Log.d(TAG, "exitrecorder → $exitOk")
            kotlinx.coroutines.delay(500)
            val resumeOk = DashcamHttpClient.probe("http://$deviceIp/app/setparamvalue?param=rec&value=1")
            Log.d(TAG, "resume rec → $resumeOk")
            EeasytechCleanupLock.markCompletion()
        }
    }

    /**
     * Idempotent post-handshake recovery for the cam-stuck-not-recording
     * state. When a user force-stops the app mid-`exitLive()`, the 500ms
     * delay between `exitrecorder` and `rec=1` is killed by process death —
     * cam ends up out of recorder mode without recording resumed. A raw
     * `rec=1` sent on the next launch returns `{"result":1,"info":"set fail"}`
     * because the cam's HTTP server only accepts `rec=1` shortly after an
     * `exitrecorder` transition.
     *
     * This re-primes that transition: `enterrecorder` puts the cam back into
     * recorder-mode, then `exitrecorder` triggers the legit exit (cam goes
     * back to its idle state-machine slot), then `rec=1` is accepted. Both
     * 500ms delays mirror the [exitLive] sequencing — without them the cam
     * returns `set fail` and (per the existing firmware-bug note) can wedge
     * its HTTP server entirely.
     *
     * We probe rec state via `/app/getparamvalue?param=rec` first so a
     * healthy cam (the common case — most relaunches happen with recording
     * already on) skips the recovery entirely and avoids a ~1s recording
     * interruption.
     */
    suspend fun ensureRecordingAfterRelaunch(deviceIp: String): Boolean {
        val recBefore = readRecState(deviceIp)
        if (recBefore == 1) {
            Log.d(TAG, "ensureRecording: cam already REC=1, no recovery needed")
            return true
        }
        Log.i(TAG, "ensureRecording: cam REC=$recBefore — running recovery sequence")
        runCatching {
            EeasytechCleanupLock.mutex.withLock {
                val enterOk = DashcamHttpClient.probe("http://$deviceIp:80/app/enterrecorder")
                Log.d(TAG, "recovery: enterrecorder → $enterOk")
                kotlinx.coroutines.delay(500)
                val exitOk = DashcamHttpClient.probe("http://$deviceIp/app/exitrecorder")
                Log.d(TAG, "recovery: exitrecorder → $exitOk")
                kotlinx.coroutines.delay(500)
                // `rec=1` body is unreliable here — on this firmware it can
                // return `{"result":1,"info":"set fail"}` even after the cam
                // has resumed recording (because rec is already 1). We send
                // it anyway as a belt-and-suspenders and rely on the
                // post-recovery `readRecState` for ground truth.
                val resumeBody = DashcamHttpClient.get("http://$deviceIp/app/setparamvalue?param=rec&value=1")
                Log.d(TAG, "recovery: rec=1 body=${resumeBody?.take(120)}")
                EeasytechCleanupLock.markCompletion()
            }
        }.onFailure {
            Log.w(TAG, "ensureRecording: recovery threw: ${it.message}")
        }
        val recAfter = readRecState(deviceIp)
        val ok = recAfter == 1
        Log.i(TAG, "ensureRecording: post-recovery REC=$recAfter (ok=$ok)")
        return ok
    }

    /**
     * Reads the cam's current `rec` parameter via
     * `/app/getparamvalue?param=rec`. Body shape on the firmware that ships
     * on Trafy Tres Pro is:
     *
     *     {"result":0,"info":{"value":0}}    // not recording
     *     {"result":0,"info":{"value":1}}    // recording
     *
     * Returns `1` if recording, `0` if not, `-1` on parse/transport failure
     * (callers should treat `-1` as "unknown" and run recovery defensively).
     */
    private suspend fun readRecState(deviceIp: String): Int {
        val body = DashcamHttpClient.get("http://$deviceIp/app/getparamvalue?param=rec") ?: return -1
        return try {
            val info = JSONObject(body).optJSONObject("info") ?: return -1
            info.optInt("value", -1)
        } catch (e: Exception) {
            Log.w(TAG, "readRecState parse error: ${e.message}")
            -1
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Detects the number of cameras using two strategies:
     *
     * Strategy 1 — GET /app/capability (older firmware returns a flat JSON with
     *   "camnum": N at the top level or inside the "info" object).
     *   The Trafy Tres Pro firmware returns {"result":0,"info":{"value":"30100110020"}}
     *   which has no "camnum" field, so this strategy falls through.
     *
     * Strategy 2 — GET /app/getparamitems?param=all and count the option entries
     *   for the "switchcam" key.  A 3-camera device lists 3 switchcam options;
     *   a single-camera device omits the key entirely (returns 1 implicitly).
     */
    private suspend fun queryCamNum(deviceIp: String): Int {
        // Strategy 1: capability endpoint
        val capBody = DashcamHttpClient.get("http://$deviceIp/app/capability")
        if (capBody != null) {
            try {
                val json = JSONObject(capBody)
                if (json.has("camnum")) {
                    val n = json.getInt("camnum").coerceAtLeast(1)
                    Log.d(TAG, "queryCamNum: capability (flat) camnum=$n")
                    return n
                }
                val info = json.optJSONObject("info")
                if (info != null && info.has("camnum")) {
                    val n = info.getInt("camnum").coerceAtLeast(1)
                    Log.d(TAG, "queryCamNum: capability (nested) camnum=$n")
                    return n
                }
                Log.d(TAG, "queryCamNum: capability response has no camnum — trying getparamitems")
            } catch (e: Exception) {
                Log.w(TAG, "queryCamNum: capability parse error: ${e.message}")
            }
        }

        // Strategy 2: count switchcam options from getparamitems
        val itemsBody = DashcamHttpClient.get("http://$deviceIp/app/getparamitems?param=all")
        if (itemsBody != null) {
            try {
                val info: JSONArray = JSONObject(itemsBody).optJSONArray("info") ?: return 1
                for (i in 0 until info.length()) {
                    val entry = info.optJSONObject(i) ?: continue
                    if (entry.optString("name") == "switchcam") {
                        val count = entry.optJSONArray("items")?.length() ?: 0
                        if (count > 0) {
                            Log.d(TAG, "queryCamNum: switchcam items count=$count")
                            return count
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryCamNum: getparamitems parse error: ${e.message}")
            }
        }

        Log.d(TAG, "queryCamNum: could not detect camera count, defaulting to 1")
        return 1
    }
}
