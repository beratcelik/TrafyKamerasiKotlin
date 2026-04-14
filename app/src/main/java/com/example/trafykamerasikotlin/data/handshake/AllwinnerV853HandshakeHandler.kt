package com.example.trafykamerasikotlin.data.handshake

import android.util.Log
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSession
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo

/**
 * Allwinner V853 (GST-A19-01-PL) handshake.
 *
 * Protocol: length-prefixed JSON over TCP :8000.
 *   1. TCP connect to 192.168.35.1:8000
 *   2. `login` with global app-id/key from the OEM YSJL app
 *   3. `bondlist` to discover the device's `deviceid` (needed as `peer` on every relay)
 *   4. `relay:getsettings` — pulls mod/fwid/product in one round trip
 *
 * The resulting [AllwinnerSession] is stashed in [AllwinnerSessionHolder] so the
 * settings repository can reuse it (avoiding a second login/race on the same socket).
 */
class AllwinnerV853HandshakeHandler : HandshakeHandler {

    companion object {
        private const val TAG = "Trafy.Allwinner"
    }

    override val protocol = ChipsetProtocol.ALLWINNER_V853

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        val deviceIp = protocol.deviceIp
        Log.i(TAG, "handshake() deviceIp=$deviceIp clientIp=$clientIp")

        val session = AllwinnerSession.open(deviceIp)
        if (session == null) {
            Log.w(TAG, "Handshake FAILED — session open returned null")
            return null
        }

        val settings = session.lastSettings()
        val model   = settings.optString("mod").ifEmpty { "A19-01" }
        val fwid    = settings.optString("fwid").ifEmpty { null }
        val mdver   = settings.optString("mdver").ifEmpty { null }
        val imei    = settings.optString("imei").ifEmpty { null }
        val product = listOfNotNull(mdver, imei?.let { "IMEI=$it" })
            .joinToString(" · ")
            .ifEmpty { null }

        AllwinnerSessionHolder.replace(session)

        val info = DeviceInfo(
            protocol        = protocol,
            clientIp        = clientIp,
            softwareVersion = fwid,
            model           = model,
            product         = product,
        )
        Log.i(TAG, "Handshake SUCCESS: $info")
        return info
    }
}
