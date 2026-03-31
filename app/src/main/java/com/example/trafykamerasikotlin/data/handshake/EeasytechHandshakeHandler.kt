package com.example.trafykamerasikotlin.data.handshake

import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Eeasytech / Allwinner — capability probe at /app/capability.
 * The reference implementation sleeps 500ms before the first request.
 * result=0 means success.
 *
 * Reference: EeasytechProtocol.java enterPreviewPageBefore().
 */
class EeasytechHandshakeHandler : HandshakeHandler {

    override val protocol = ChipsetProtocol.EEASYTECH

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        delay(500)
        val body = DashcamHttpClient.get("http://${protocol.deviceIp}/app/capability")
            ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("result", -1) != 0) return null
            DeviceInfo(protocol = protocol, clientIp = clientIp)
        } catch (e: Exception) {
            null
        }
    }
}
