package com.example.trafykamerasikotlin.data.settings

import android.util.Log
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.model.SettingOption
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Fetches and applies dashcam settings for Easytech/Allwinner devices.
 *
 * Flow:
 *  1. delay 1 s + GET /app/setting?param=enter  (camera must enter settings mode)
 *  2. GET /app/getparamitems?param=all           → available option strings per key
 *  3. GET /app/getparamvalue?param=all           → current value per key
 *  4. Build List<SettingItem> filtering out action-type keys
 *
 * To apply: GET /app/setparamvalue?param=KEY&value=VALUE (HTTP 200 = success).
 * No recording stop/start required (unlike HiDVR).
 *
 * On screen leave: GET /app/setting?param=exit
 *
 * Reference: EeasytechProtocol.java, EeasytechConst.java in golook-jadx
 */
class EeasytechSettingsRepository {

    companion object {
        private const val TAG = "Trafy.EeasytechRepo"

        /** Keys that are action-type (format, reset, WiFi) — skipped in settings list. */
        private val EXCLUDED_KEYS = setOf(
            "format", "SD0", "reset_to_default",
            "Net.WIFI_AP", "Net.WIFI_AP.SSID", "Net.WIFI_AP.CryptoKey",
            "switchcam", "rec",
        )
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Enters settings mode, fetches all options and current values, and returns
     * a merged [List<SettingItem>] ready for display.
     * Returns null if the camera is unreachable.
     */
    suspend fun fetchAll(deviceIp: String): List<SettingItem>? {
        val base = "http://$deviceIp"
        Log.i(TAG, "fetchAll deviceIp=$deviceIp")

        // Reference app sleeps 1 s then calls setting?param=enter
        delay(1000)
        DashcamHttpClient.probe("$base/app/setting?param=enter")
            .also { Log.d(TAG, "setting?param=enter → $it") }

        // Fetch available options
        val itemsJson = DashcamHttpClient.get("$base/app/getparamitems?param=all")
        if (itemsJson == null) {
            Log.e(TAG, "getparamitems failed")
            return null
        }
        Log.d(TAG, "getparamitems received (${itemsJson.length} bytes)")
        val optionsMap = parseParamItems(itemsJson)
        if (optionsMap.isEmpty()) {
            Log.e(TAG, "getparamitems parsed to empty map")
            return null
        }

        // Fetch current values
        val valuesJson = DashcamHttpClient.get("$base/app/getparamvalue?param=all")
        if (valuesJson == null) {
            Log.e(TAG, "getparamvalue failed")
            return null
        }
        Log.d(TAG, "getparamvalue received (${valuesJson.length} bytes)")
        val valuesMap = parseParamValues(valuesJson)

        // Build SettingItem list
        val items = optionsMap
            .filter { (key, _) -> key !in EXCLUDED_KEYS }
            .map { (key, options) ->
                val currentValue = valuesMap[key] ?: ""
                val currentLabel = options.find { it.value == currentValue }?.label ?: currentValue
                Log.v(TAG, "  $key value='$currentValue' label='$currentLabel'")
                SettingItem(
                    key               = key,
                    title             = EeasytechTranslations.title(key),
                    currentValue      = currentValue,
                    currentValueLabel = currentLabel,
                    options           = options,
                )
            }

        Log.i(TAG, "fetchAll → ${items.size} settings")
        return items
    }

    /**
     * Sends a single parameter change to the camera.
     * Returns true if the camera responded with HTTP 200.
     */
    suspend fun applySetting(deviceIp: String, key: String, value: String): Boolean {
        val url = "http://$deviceIp/app/setparamvalue?param=$key&value=$value"
        Log.i(TAG, "applySetting $key=$value → $url")
        val success = DashcamHttpClient.probe(url)
        Log.i(TAG, "applySetting $key=$value → success=$success")
        return success
    }

    /**
     * Tells the camera to exit settings mode.
     * Called when the user leaves the Settings screen.
     */
    suspend fun exitSettings(deviceIp: String) {
        val ok = DashcamHttpClient.probe("http://$deviceIp/app/setting?param=exit")
        Log.d(TAG, "setting?param=exit → $ok")
    }

    // ── JSON parsers ───────────────────────────────────────────────────────

    /**
     * Parses the /app/getparamitems response.
     *
     * Actual shape (confirmed from EeasytechCapabilityManager.java):
     * {
     *   "info": [
     *     { "name": "rec_resolution", "items": ["1920x1080x30","1280x720x30"], "index": [0,1] }
     *   ]
     * }
     *
     * GoLook stores entryValues = string-converted index integers ("0","1"…) and
     * entries = display strings from items. The index value is what gets sent to
     * setparamvalue and what getparamvalue returns as the current selection.
     *
     * Returns an ordered map of key → List<SettingOption> where option.value = index string.
     */
    private fun parseParamItems(json: String): LinkedHashMap<String, List<SettingOption>> {
        val result = LinkedHashMap<String, List<SettingOption>>()
        try {
            val root = JSONObject(json)
            val info = root.getJSONArray("info")
            for (i in 0 until info.length()) {
                val entry = info.getJSONObject(i)
                val name = entry.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val itemsArr  = entry.optJSONArray("items") ?: continue
                val indexArr  = entry.optJSONArray("index") ?: continue
                val options = mutableListOf<SettingOption>()
                for (j in 0 until itemsArr.length()) {
                    val displayStr = itemsArr.optString(j).takeIf { it.isNotEmpty() } ?: continue
                    // index value is what the camera uses as the current/set value
                    val indexVal = "${indexArr.optInt(j, j)}"
                    options.add(SettingOption(
                        value = indexVal,
                        label = EeasytechTranslations.optionLabel(name, displayStr, displayStr),
                    ))
                }
                if (options.isNotEmpty()) result[name] = options
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseParamItems error: ${e.message}")
        }
        Log.d(TAG, "parseParamItems → ${result.size} keys: ${result.keys.toList()}")
        return result
    }

    /**
     * Parses the /app/getparamvalue?param=all response.
     *
     * Actual shape (confirmed from EeasytechProtocol.java getSetItemParameter()):
     * {
     *   "result": 0,
     *   "info": [
     *     {"name": "rec_resolution", "value": "1"},
     *     {"name": "mic",            "value": "0"}
     *   ]
     * }
     *
     * The value is the index string (matching the index array from getparamitems).
     * Returns a map of key → index string.
     */
    private fun parseParamValues(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val root = JSONObject(json)
            val info = root.optJSONArray("info") ?: run {
                Log.e(TAG, "parseParamValues: no 'info' array in response")
                return result
            }
            for (i in 0 until info.length()) {
                val obj  = info.optJSONObject(i) ?: continue
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val value = obj.optString("value")
                result[name] = value
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseParamValues error: ${e.message}")
        }
        Log.d(TAG, "parseParamValues → ${result.size} values: $result")
        return result
    }
}
