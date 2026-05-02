package com.example.trafykamerasikotlin.data.handshake

import android.util.Log
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Easytech (a.k.a. Allwinner) — capability probe at /app/capability.
 * The reference implementation sleeps 500ms before the first request.
 * `result=0` means success.
 *
 * Beyond the success check we now scrape any model / version / device-name
 * field from both `/app/capability` and `/app/getversion` so the rest of
 * the app (TrafyModelIdentifier, About dialog, …) can tell different
 * Easytech-based Trafy SKUs apart.
 *
 * Reference: EeasytechProtocol.java enterPreviewPageBefore().
 */
class EeasytechHandshakeHandler : HandshakeHandler {

    override val protocol = ChipsetProtocol.EEASYTECH

    override suspend fun handshake(clientIp: String): DeviceInfo? {
        delay(500)
        val capBody = DashcamHttpClient.get("http://${protocol.deviceIp}/app/capability")
            ?: return null
        Log.d(TAG, "capability body: ${capBody.take(800)}")
        val capJson = try { JSONObject(capBody) } catch (_: Exception) { return null }
        if (capJson.optInt("result", -1) != 0) return null

        // Best-effort: try a few common JSON keys from the capability body and
        // a follow-up /app/getversion call. Whichever firmware path happens to
        // expose a unique identifier (model, device, name…) wins.
        var model    = pickFirstNonEmpty(capJson, "model", "device", "name", "device_name", "product")
        var version  = pickFirstNonEmpty(capJson, "version", "fw_version", "firmware", "soft_ver", "softversion")

        if (model == null || version == null) {
            val verBody = DashcamHttpClient.get("http://${protocol.deviceIp}/app/getversion")
            if (verBody != null) {
                Log.d(TAG, "getversion body: ${verBody.take(400)}")
                runCatching { JSONObject(verBody) }.getOrNull()?.let { vj ->
                    model    = model    ?: pickFirstNonEmpty(vj, "model", "device", "name", "device_name", "product")
                    version  = version  ?: pickFirstNonEmpty(vj, "version", "fw_version", "firmware", "soft_ver", "softversion")
                }
            }
        }
        Log.i(TAG, "handshake: model=$model version=$version")
        return DeviceInfo(
            protocol        = protocol,
            clientIp        = clientIp,
            model           = model,
            softwareVersion = version,
        )
    }

    private fun pickFirstNonEmpty(json: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = json.optString(k, "").takeIf { it.isNotBlank() }
            if (v != null) return v
        }
        return null
    }

    companion object { private const val TAG = "Trafy.EasyHandshake" }
}
