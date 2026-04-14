package com.example.trafykamerasikotlin.data.settings

import android.util.Log
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSession
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import com.example.trafykamerasikotlin.data.model.SettingItem
import org.json.JSONObject

/**
 * Settings repository for Allwinner V853 (A19) devices.
 *
 * The device speaks length-prefixed JSON on TCP :8000. All per-device operations are
 * wrapped in a `relay` envelope with a `msg` sub-command. Reads use `relay:getsettings`;
 * writes use per-setting commands (e.g. `relay:setvol`, `relay:setaccnotify`).
 *
 * Session is owned by [AllwinnerSessionHolder] and is originally created by
 * [com.example.trafykamerasikotlin.data.handshake.AllwinnerV853HandshakeHandler].
 * If it's missing (e.g. process restart or network blip) we transparently reopen it.
 */
class AllwinnerSettingsRepository {

    companion object {
        private const val TAG = "Trafy.AllwinnerRepo"
    }

    /** Loads (or reuses) the session for [deviceIp]. Reopens if the previous one is gone. */
    private suspend fun requireSession(deviceIp: String): AllwinnerSession? {
        AllwinnerSessionHolder.current?.let { return it }
        Log.i(TAG, "No cached session; opening new one @ $deviceIp")
        val fresh = AllwinnerSession.open(deviceIp) ?: return null
        AllwinnerSessionHolder.replace(fresh)
        return fresh
    }

    suspend fun fetchAll(deviceIp: String): List<SettingItem>? {
        Log.i(TAG, "fetchAll deviceIp=$deviceIp")
        val session = requireSession(deviceIp) ?: return null
        val settings = try { session.getSettings() } catch (e: Exception) {
            Log.e(TAG, "getsettings failed: ${e.message}", e)
            return null
        } ?: return null

        val items = mutableListOf<SettingItem>()

        // Configurable items (drop-down options from AllwinnerTranslations.SPEC).
        for (spec in AllwinnerTranslations.SPEC) {
            if (!settings.has(spec.key)) continue
            val raw = settings.opt(spec.key)?.toString() ?: ""
            // fres is encoded as "1080P:0,1440P:1" — extract the active resolution (value == "1")
            val displayValue = if (spec.key == "fres") parseFres(raw) else raw
            val label = spec.options.find { it.value == displayValue }?.label ?: displayValue
            items.add(
                SettingItem(
                    key = if (spec.writable) spec.key else "${spec.key}__ro",
                    title = spec.title,
                    currentValue = displayValue,
                    currentValueLabel = label,
                    options = if (spec.writable) spec.options else emptyList(),
                )
            )
        }

        // Read-only informational fields (firmware, modem, etc.).
        for (info in AllwinnerTranslations.INFO) {
            if (!settings.has(info.key)) continue
            val raw = settings.opt(info.key)?.toString() ?: ""
            // Format fwver integer (e.g. 180 → "1.8.0"); skip other empty fields.
            val display = when (info.key) {
                "fwver" -> formatFwver(raw)
                else    -> raw.ifEmpty { continue }
            }
            items.add(
                SettingItem(
                    key = "${info.key}__info",
                    title = info.title,
                    currentValue = display,
                    currentValueLabel = display,
                    options = emptyList(),
                )
            )
        }

        // WiFi dialog trigger row — shows current SSID, tapped to open the WiFi dialog.
        val currentSsid = settings.optString("ssid", "")
        items.add(SettingItem(
            key = "getwifi.cgi?",
            title = "Wi-Fi Yapılandırması",
            currentValue = currentSsid,
            currentValueLabel = currentSsid,
            options = emptyList(),
        ))

        // APN dialog trigger row — shows current APN value, tapped to open the APN dialog.
        val currentApn = settings.optString("apn", ",,")
        items.add(SettingItem(
            key = "allwinner_apn",
            title = "APN Yapılandırması",
            currentValue = currentApn,
            currentValueLabel = currentApn,
            options = emptyList(),
        ))

        Log.i(TAG, "fetchAll → ${items.size} items")
        return items
    }

    /** Returns the current WiFi SSID and PSK from the cached getsettings blob. */
    fun getWifiSettingsFromCache(): Pair<String, String>? {
        val s = AllwinnerSessionHolder.current?.lastSettings() ?: return null
        val ssid = s.optString("ssid").takeIf { it.isNotEmpty() } ?: return null
        val psk  = s.optString("psk", "")
        return Pair(ssid, psk)
    }

    /** Sends `relay:setap` to update the dashcam's WiFi AP SSID and password. */
    suspend fun setWifiAp(deviceIp: String, ssid: String, psk: String): Boolean {
        val session = requireSession(deviceIp) ?: return false
        val payload = JSONObject().apply {
            put("ssid", ssid)
            put("psk", psk)
        }
        Log.i(TAG, "setap → ssid=$ssid psk=***")
        val resp = try {
            session.relay("setap", payload)
        } catch (e: Exception) {
            Log.e(TAG, "setap threw: ${e.message}", e)
            return false
        }
        return resp.optInt("ret", -1) == 0
    }

    /** Returns the current APN fields (apn, user, password) from the cached getsettings blob. */
    fun getApnFromCache(): Triple<String, String, String> {
        val raw = AllwinnerSessionHolder.current?.lastSettings()?.optString("apn") ?: ",,"
        val parts = raw.split(",")
        return Triple(
            parts.getOrElse(0) { "" },
            parts.getOrElse(1) { "" },
            parts.getOrElse(2) { "" },
        )
    }

    /** Sends `relay:setapn` with the three APN fields joined as "apn,user,password". */
    suspend fun setApn(deviceIp: String, apn: String, user: String, password: String): Boolean {
        val session = requireSession(deviceIp) ?: return false
        val payload = JSONObject().apply {
            put("apn", "$apn,$user,$password")
        }
        Log.i(TAG, "setapn → apn=$apn user=$user")
        val resp = try {
            session.relay("setapn", payload)
        } catch (e: Exception) {
            Log.e(TAG, "setapn threw: ${e.message}", e)
            return false
        }
        val ret = resp.optInt("ret", -1)
        if (ret != 0) Log.w(TAG, "setapn ret=$ret err=${resp.optString("err")}")
        if (ret == 0) session.getSettings()
        return ret == 0
    }

    /**
     * Applies a single setting via a per-setting relay sub-command.
     *
     * The Allwinner protocol does NOT have a generic `setsettings` — each setting has its
     * own relay msg (e.g. `setvol`, `setaccnotify`, `colwake`). The mapping from settings
     * key to relay command + param name lives in [AllwinnerTranslations.Spec].
     */
    suspend fun applySetting(deviceIp: String, key: String, value: String): Boolean {
        val spec = AllwinnerTranslations.findSpec(key)
        if (spec == null || !spec.writable || spec.writeCmd == null) {
            Log.w(TAG, "applySetting refused: $key is not writable")
            return false
        }
        val session = requireSession(deviceIp) ?: return false
        val payload = JSONObject().apply {
            put(spec.writeParam ?: key, value.toIntOrNull() ?: value)
            // setres requires a second param: camid="F" (front camera)
            if (key == "fres") put("camid", "F")
        }
        Log.i(TAG, "${spec.writeCmd} → $payload")
        val resp = try {
            session.relay(spec.writeCmd, payload)
        } catch (e: Exception) {
            Log.e(TAG, "${spec.writeCmd} threw: ${e.message}", e)
            return false
        }
        val ret = resp.optInt("ret", -1)
        if (ret != 0) {
            Log.w(TAG, "${spec.writeCmd} returned ret=$ret err=${resp.optString("err")}")
            return false
        }
        session.getSettings()
        return true
    }

    /**
     * Formats the `fwver` integer (e.g. 180) as a dotted version string "1.8.0".
     * Falls back to the raw string if it's not a valid integer.
     */
    private fun formatFwver(raw: String): String {
        val v = raw.toIntOrNull() ?: return raw
        return "${v / 100}.${(v % 100) / 10}.${v % 10}"
    }

    /**
     * Parses the `fres` field from getsettings into the active resolution string.
     * Format: "1080P:0,1440P:1" — entry with value "1" is the current resolution.
     * Falls back to the raw string if parsing fails.
     */
    private fun parseFres(raw: String): String {
        for (entry in raw.split(",")) {
            val parts = entry.trim().split(":")
            if (parts.size == 2 && parts[1].trim() == "1") return parts[0].trim()
        }
        return raw
    }

    /** Closes the session when the user leaves the settings screen / disconnects. */
    fun exitSettings() {
        AllwinnerSessionHolder.clear()
    }
}
