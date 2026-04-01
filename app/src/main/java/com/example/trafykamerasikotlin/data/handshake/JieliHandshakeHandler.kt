package com.example.trafykamerasikotlin.data.handshake

import android.util.Log
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusProtocol
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo

/**
 * GeneralPlus (GP) dashcam — GPSOCKET TCP protocol on port 8081.
 *
 * Detection:
 *   1. The phone's WiFi client IP is in the 192.168.25.x range (camera is always
 *      at 192.168.25.1 per CamWrapper.COMMAND_URL).
 *   2. This handler is set as the PRIMARY handler for 192.168.25.x clients in
 *      DashcamHandshakeManager, so it runs before any HTTP-based fallbacks.
 *
 * Handshake:
 *   Open TCP → 192.168.25.1:8081
 *   Send 16-byte AuthDevice packet: "GPSOCKET" + type=CMD + idx=0 + mode=General + cmd=AuthDevice + token
 *   Expect response starting with "GPSOCKET" with type=ACK (0x02).
 *
 * Reference: Q6/h.java (GPlusMgr.d()) + CamWrapper.java in viidure-jadx.
 */
class GeneralplusHandshakeHandler : HandshakeHandler {

    companion object {
        private const val TAG = "Trafy.GPHandshake"
    }

    override val protocol = ChipsetProtocol.GENERALPLUS

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        Log.i(TAG, "Attempting GeneralPlus GPSOCKET handshake, clientIp=$clientIp")
        return GeneralplusSession.withSession { _, _, _ ->
            // withSession already performed and verified the AuthDevice exchange.
            // Reaching here means ACK was received → confirmed GeneralPlus device.
            Log.i(TAG, "GeneralPlus handshake SUCCESS")
            DeviceInfo(protocol = protocol, clientIp = clientIp)
        }
    }
}
