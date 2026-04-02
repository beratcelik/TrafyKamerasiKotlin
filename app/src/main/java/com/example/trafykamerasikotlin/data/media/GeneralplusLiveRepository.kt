package com.example.trafykamerasikotlin.data.media

import android.util.Log
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusProtocol
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession

/**
 * Manages live RTSP streaming for GeneralPlus dashcams.
 *
 * Protocol (confirmed from PCAP PCAPdroid_02_Nis_15_41_04.pcap):
 *  1. Open TCP session to 192.168.25.1:8081 (GPSOCKET)
 *  2. AuthDevice handshake
 *  3. Send RestartStreaming (CMD 0x04, MODE_GENERAL) — initialises RTSP server
 *  4. RTSP: rtsp://192.168.25.1:8080/?action=stream — MJPEG over RTP (payload 26)
 *  5. Keep TCP control session alive during streaming (camera drops RTSP when it closes)
 *  6. On exit: release held session
 *
 * Video codec: MJPEG (JPEG/90000), NOT H.264 — requires MjpegRtspPlayer, not IjkPlayer.
 */
class GeneralplusLiveRepository {

    companion object {
        private const val TAG = "Trafy.GPLive"

        /** RTSP URL served by the camera after RestartStreaming over GPSOCKET. */
        const val RTSP_URL = "rtsp://${GeneralplusSession.CAMERA_IP}:8080/?action=stream"
    }

    /**
     * Sends RestartStreaming to start the live RTSP server and holds the TCP session open.
     * Returns true if the command was acknowledged.
     */
    suspend fun enterLive(): Boolean {
        Log.i(TAG, "enterLive: sending RestartStreaming")
        val success = GeneralplusSession.withSession(holdOpen = true) { send, _, receive ->
            send(GeneralplusProtocol.MODE_GENERAL, GeneralplusProtocol.CMD_RESTART_STREAMING)
            val ack = receive(GeneralplusProtocol.CMD_RESTART_STREAMING)
            if (ack != null) Log.i(TAG, "enterLive: RestartStreaming ACK")
            else Log.w(TAG, "enterLive: no ACK — camera may already be streaming")
            true
        }
        return success == true
    }

    /**
     * Releases the held TCP control session. Call when leaving the Live screen.
     */
    fun exitLive() {
        Log.i(TAG, "exitLive: releasing held session")
        GeneralplusSession.releaseHeldSession()
    }
}
