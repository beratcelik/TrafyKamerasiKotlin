package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient

/**
 * Live-preview endpoints for MStar / Hime3 devices (Trafy Tres).
 *
 * MStar exposes each sensor as its own RTSP stream under `/liveRTSP/av{N}`:
 *   • av4 = camera 1 (front)
 *   • av5 = camera 2 (inside, on 2-/3-sensor units)
 *   • av6 = camera 3 (rear, on 3-sensor units)
 * (default RTSP port 554, H.264 over TCP).
 *
 * Unlike HiDVR, there is NO setup handshake: the OEM's registerClient /
 * togglePreview / previewToggleType are all no-ops, and the cam keeps recording
 * to the SD card while it streams — so we neither stop recording nor register a
 * client. Switching lenses is purely a URL change (av4 → av5 → av6), not a CGI
 * re-route.
 *
 * Reference: MstartDvrProtocol.getRtsps() in golook-jadx.
 */
class MstarLiveRepository {

    companion object {
        private const val TAG = "Trafy.MstarLive"

        /** RTSP URL for lens [index] (0 = front/av4, 1 = av5, 2 = av6). */
        fun rtspUrl(deviceIp: String, index: Int): String =
            "rtsp://$deviceIp/liveRTSP/av${4 + index.coerceIn(0, 2)}"
    }

    /**
     * Number of sensors, read from the `Camera.Menu.DevInfo.model` string
     * (e.g. `HY_3SENSOR_PARK_GPS`). Defaults to 1 if the cam doesn't answer —
     * av4 alone is valid for every MStar variant, so a single-camera fallback
     * still streams.
     */
    suspend fun cameraCount(deviceIp: String): Int {
        val body = DashcamHttpClient.get(
            "http://$deviceIp/cgi-bin/Config.cgi?action=get&property=Camera.Menu.DevInfo.*"
        )
        val count = when {
            body == null              -> 1
            body.contains("3SENSOR")  -> 3
            body.contains("2SENSOR")  -> 2
            else                      -> 1
        }
        Log.i(TAG, "cameraCount → $count")
        return count
    }
}
