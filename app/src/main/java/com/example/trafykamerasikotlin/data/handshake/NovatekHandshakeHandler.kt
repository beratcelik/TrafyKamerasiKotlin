package com.example.trafykamerasikotlin.data.handshake

import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import org.json.JSONObject

/**
 * Novatek — single GET: http://192.168.1.254/?custom=1&cmd=3012
 * Returns JSON. rval=0 means success.
 *
 * Reference: NovatekDvrProtocol.java getDeviceAttr(), line 397.
 */
class NovatekHandshakeHandler : HandshakeHandler {

    override val protocol = ChipsetProtocol.NOVATEK

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        val body = DashcamHttpClient.get("http://${protocol.deviceIp}/?custom=1&cmd=3012")
            ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("rval", -1) != 0) return null
            DeviceInfo(
                protocol        = protocol,
                clientIp        = clientIp,
                softwareVersion = json.optString("ver_info").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }
}
