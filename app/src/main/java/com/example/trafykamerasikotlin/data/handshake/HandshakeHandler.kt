package com.example.trafykamerasikotlin.data.handshake

import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo

/**
 * One implementation per chipset. Each handler knows its own device IP,
 * the handshake HTTP sequence, and how to parse the response into DeviceInfo.
 */
interface HandshakeHandler {
    val protocol: ChipsetProtocol

    /**
     * Execute the handshake sequence for this protocol.
     * @param clientIp  The phone's WiFi-assigned IP address.
     * @return          DeviceInfo on success, null on failure.
     */
    suspend fun handshake(clientIp: String): DeviceInfo?
}
