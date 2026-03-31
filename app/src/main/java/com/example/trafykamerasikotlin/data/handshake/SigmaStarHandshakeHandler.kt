package com.example.trafykamerasikotlin.data.handshake

import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import org.json.JSONObject

/**
 * SigmaStar / FVDVR — heartbeat probe on cmd=501.
 * status=0 means device is alive.
 *
 * Reference: SmDvrProtocol.java heartPackage(), uses cmd=501 pattern.
 */
class SigmaStarHandshakeHandler : HandshakeHandler {

    override val protocol = ChipsetProtocol.SIGMA_STAR

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        val body = DashcamHttpClient.get("http://${protocol.deviceIp}/?cmd=501")
            ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("status", -1) != 0) return null
            DeviceInfo(protocol = protocol, clientIp = clientIp)
        } catch (e: Exception) {
            null
        }
    }
}
