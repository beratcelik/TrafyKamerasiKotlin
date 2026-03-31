package com.example.trafykamerasikotlin.data.handshake

import android.util.Log
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient

/**
 * HiSilicon DVR — two-step handshake:
 *   Step 1: Register the client (must return 2xx).
 *   Step 2: Fetch device attributes (key=value response body).
 *
 * Reference: HiDvrProtocol.java
 *   registerClient()  → GET http://{ip}/cgi-bin/hisnet/client.cgi?&-operation=register&-ip={clientIp}
 *   getDeviceAttr()   → GET http://{ip}/cgi-bin/hisnet/getdeviceattr.cgi?
 */
class HiDvrHandshakeHandler : HandshakeHandler {

    companion object {
        private const val TAG = "Trafy.HiDvr"
    }

    override val protocol = ChipsetProtocol.HI_DVR

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        val deviceIp = protocol.deviceIp
        Log.i(TAG, "handshake() deviceIp=$deviceIp clientIp=$clientIp")

        // Step 1 — register
        val registerUrl = "http://$deviceIp/cgi-bin/hisnet/client.cgi?&-operation=register&-ip=$clientIp"
        Log.d(TAG, "Step 1: register → $registerUrl")
        val registered = DashcamHttpClient.probe(registerUrl)
        if (!registered) {
            Log.w(TAG, "Step 1 FAILED — register returned false")
            return null
        }
        Log.i(TAG, "Step 1 OK — registered")

        // Step 2 — device attributes
        val attrUrl = "http://$deviceIp/cgi-bin/hisnet/getdeviceattr.cgi?"
        Log.d(TAG, "Step 2: getdeviceattr → $attrUrl")
        val body = DashcamHttpClient.get(attrUrl)
        if (body == null) {
            Log.w(TAG, "Step 2 FAILED — getdeviceattr returned null")
            return null
        }
        Log.i(TAG, "Step 2 raw body: $body")

        val attrs = parseKeyValue(body)
        Log.i(TAG, "Step 2 parsed attrs: $attrs")

        if (attrs["softversion"] == null && attrs["model"] == null) {
            Log.w(TAG, "Step 2 FAILED — neither 'softversion' nor 'model' found in response. Keys present: ${attrs.keys}")
            return null
        }

        val info = DeviceInfo(
            protocol        = protocol,
            clientIp        = clientIp,
            softwareVersion = attrs["softversion"],
            model           = attrs["model"],
            product         = attrs["product"],
        )
        Log.i(TAG, "Handshake SUCCESS: $info")
        return info
    }

    private fun parseKeyValue(body: String): Map<String, String> =
        body.lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val rawKey = line.substring(0, idx).trim()
            val rawVal = line.substring(idx + 1).trim()
            // Camera responds with JavaScript: var softversion="1.0.1.2"; — strip the "var " prefix
            // and strip surrounding quotes + trailing semicolon from the value.
            val key   = rawKey.removePrefix("var").trim()
            val value = rawVal.trim('"', ';', ' ')
            if (key.isNotEmpty()) key to value else null
        }.toMap()
}
