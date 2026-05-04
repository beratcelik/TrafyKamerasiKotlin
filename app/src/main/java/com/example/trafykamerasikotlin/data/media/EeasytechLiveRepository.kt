package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
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
        val exitOk = DashcamHttpClient.probe("http://$deviceIp/app/exitrecorder")
        Log.d(TAG, "exitrecorder → $exitOk")
        kotlinx.coroutines.delay(500)
        val resumeOk = DashcamHttpClient.probe("http://$deviceIp/app/setparamvalue?param=rec&value=1")
        Log.d(TAG, "resume rec → $resumeOk")
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
