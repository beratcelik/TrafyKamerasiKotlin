package com.example.trafykamerasikotlin.data.handshake

import android.util.Log
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.FailureReason
import com.example.trafykamerasikotlin.data.model.HandshakeResult
import com.example.trafykamerasikotlin.data.network.WifiIpProvider

/**
 * Orchestrates the full dashcam connection flow:
 *   1. Read the phone's current WiFi IP (assigned by the camera hotspot).
 *   2. Detect the primary chipset from the IP prefix.
 *   3. Try the primary protocol handler first.
 *   4. If it fails, try every remaining handler in fallback order.
 *
 * Mirrors the algorithm in HandShakeManager.java.
 */
class DashcamHandshakeManager(
    private val wifiIpProvider: WifiIpProvider,
) {
    companion object {
        private const val TAG = "Trafy.HandshakeMgr"
    }

    // Fallback order matches HandShakeManager.java int[] protocols = {1,2,3,4,5,6}
    private val allHandlers: List<HandshakeHandler> = listOf(
        HiDvrHandshakeHandler(),
        MstarHandshakeHandler(),
        MstarHzHandshakeHandler(),
        SigmaStarHandshakeHandler(),
        EeasytechHandshakeHandler(),
        NovatekHandshakeHandler(),
    )

    suspend fun connect(): HandshakeResult {
        Log.i(TAG, "=== connect() started ===")

        val clientIp = wifiIpProvider.getClientIp()
        if (clientIp == null) {
            Log.e(TAG, "No WiFi IP detected — returning WIFI_NOT_CONNECTED")
            return HandshakeResult.Failure(FailureReason.WIFI_NOT_CONNECTED)
        }

        if (clientIp == "0.0.0.0") {
            Log.e(TAG, "IP is 0.0.0.0 — returning IP_NOT_OBTAINED")
            return HandshakeResult.Failure(FailureReason.IP_NOT_OBTAINED)
        }

        Log.i(TAG, "Client IP: $clientIp")
        val primaryProtocol = detectPrimaryProtocol(clientIp)
        Log.i(TAG, "Primary protocol detected: $primaryProtocol")

        val primary = allHandlers.firstOrNull { it.protocol == primaryProtocol }

        // Try primary first
        if (primary != null) {
            Log.i(TAG, "Trying primary handler: ${primary.protocol}")
            val result = primary.handshake(clientIp)
            if (result != null) {
                Log.i(TAG, "Primary handler SUCCESS: ${result.protocol.displayName} | model=${result.model} | version=${result.softwareVersion}")
                return HandshakeResult.Success(result)
            }
            Log.w(TAG, "Primary handler FAILED: ${primary.protocol}")
        } else {
            Log.w(TAG, "No primary handler for protocol $primaryProtocol, going straight to fallback")
        }

        // Fallback: try remaining handlers in order
        for (handler in allHandlers) {
            if (handler.protocol == primaryProtocol) continue
            Log.i(TAG, "Trying fallback handler: ${handler.protocol}")
            val result = handler.handshake(clientIp)
            if (result != null) {
                Log.i(TAG, "Fallback handler SUCCESS: ${result.protocol.displayName}")
                return HandshakeResult.Success(result)
            }
            Log.w(TAG, "Fallback handler FAILED: ${handler.protocol}")
        }

        Log.e(TAG, "ALL protocols failed — returning ALL_PROTOCOLS_FAILED")
        return HandshakeResult.Failure(FailureReason.ALL_PROTOCOLS_FAILED)
    }

    /**
     * Maps client IP prefix → primary ChipsetProtocol.
     * Novatek check (ends with .254) must come before the plain MSTAR check
     * because both share the "192.168.1." prefix.
     *
     * Source: HandShakeManager.java lines 77–105.
     */
    private fun detectPrimaryProtocol(clientIp: String): ChipsetProtocol? = when {
        clientIp.startsWith("192.168.0.")                                  -> ChipsetProtocol.HI_DVR
        clientIp.startsWith("192.168.1.") && clientIp.endsWith(".254")     -> ChipsetProtocol.NOVATEK
        clientIp.startsWith("192.168.1.")                                  -> ChipsetProtocol.MSTAR
        clientIp.startsWith("192.72.1.")                                   -> ChipsetProtocol.MSTAR_HZ
        clientIp.startsWith("192.168.201.")                                -> ChipsetProtocol.SIGMA_STAR
        clientIp.startsWith("192.168.169.")                                -> ChipsetProtocol.EEASYTECH
        else                                                               -> null
    }
}
