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

    // GeneralplusHandshakeHandler is first because it has a unique IP subnet (192.168.25.x)
    // and is the primary handler for that range. All other handlers use HTTP and fall back
    // naturally when their target IP is unreachable.
    private val allHandlers: List<HandshakeHandler> = listOf(
        GeneralplusHandshakeHandler(),
        HiDvrHandshakeHandler(),
        MstarHandshakeHandler(),
        MstarHzHandshakeHandler(),
        SigmaStarHandshakeHandler(),
        EeasytechHandshakeHandler(),
        NovatekHandshakeHandler(),
        AllwinnerV853HandshakeHandler(),
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

        // If the IP prefix unambiguously identifies a protocol, try ONLY that handler.
        // Fallbacks are pointless — every other camera IP is on a different subnet and
        // will be unreachable from the phone's current WiFi address.
        if (primaryProtocol != null) {
            if (primary != null) {
                Log.i(TAG, "Trying primary handler: ${primary.protocol}")
                val result = primary.handshake(clientIp)
                if (result != null) {
                    Log.i(TAG, "Primary handler SUCCESS: ${result.protocol.displayName} | model=${result.model} | version=${result.softwareVersion}")
                    return HandshakeResult.Success(result)
                }
                Log.w(TAG, "Primary handler FAILED: ${primary.protocol} — not trying fallbacks (protocol is known)")
            } else {
                Log.w(TAG, "No handler registered for detected protocol $primaryProtocol")
            }
            Log.e(TAG, "Known protocol $primaryProtocol failed — returning ALL_PROTOCOLS_FAILED")
            return HandshakeResult.Failure(FailureReason.ALL_PROTOCOLS_FAILED)
        }

        // Unknown IP prefix — try all handlers in order as a last resort
        Log.w(TAG, "Unknown IP prefix, trying all handlers as fallback")
        for (handler in allHandlers) {
            Log.i(TAG, "Trying handler: ${handler.protocol}")
            val result = handler.handshake(clientIp)
            if (result != null) {
                Log.i(TAG, "Handler SUCCESS: ${result.protocol.displayName}")
                return HandshakeResult.Success(result)
            }
            Log.w(TAG, "Handler FAILED: ${handler.protocol}")
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
        clientIp.startsWith("192.168.25.")                                 -> ChipsetProtocol.GENERALPLUS
        clientIp.startsWith("192.168.0.")                                  -> ChipsetProtocol.HI_DVR
        clientIp.startsWith("192.168.1.") && clientIp.endsWith(".254")     -> ChipsetProtocol.NOVATEK
        clientIp.startsWith("192.168.1.")                                  -> ChipsetProtocol.MSTAR
        clientIp.startsWith("192.72.1.")                                   -> ChipsetProtocol.MSTAR_HZ
        clientIp.startsWith("192.168.201.")                                -> ChipsetProtocol.SIGMA_STAR
        clientIp.startsWith("192.168.169.")                                -> ChipsetProtocol.EEASYTECH
        clientIp.startsWith("192.168.35.")                                 -> ChipsetProtocol.ALLWINNER_V853
        else                                                               -> null
    }
}
