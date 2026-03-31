package com.example.trafykamerasikotlin.data.handshake

import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient

/**
 * MStar — GET Config.cgi for device info.
 * Any non-null 200 response from the correct IP confirms the chipset.
 *
 * Reference: MstartDvrProtocol.java — Config.cgi pattern used throughout.
 * Default IP: 192.168.1.1 (or 192.72.1.1 for the HZ variant via [protocol] override).
 */
open class MstarHandshakeHandler(
    override val protocol: ChipsetProtocol = ChipsetProtocol.MSTAR,
) : HandshakeHandler {

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        val url = "http://${protocol.deviceIp}/cgi-bin/Config.cgi?action=get&property=System.DeviceInfo.*"
        val body = DashcamHttpClient.get(url) ?: return null
        return DeviceInfo(
            protocol        = protocol,
            clientIp        = clientIp,
            softwareVersion = extractValue(body, "System.DeviceInfo.SoftwareVersion"),
            model           = extractValue(body, "System.DeviceInfo.Model"),
        )
    }

    private fun extractValue(body: String, key: String): String? =
        body.lines()
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
