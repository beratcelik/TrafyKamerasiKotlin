package com.example.trafykamerasikotlin.data.settings

import android.util.Log
import android.util.Xml
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.model.SettingOption
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Fetches and applies dashcam settings for HiSilicon (HiDVR) devices.
 *
 * Flow:
 *  1. GET /app/bin/cammenu.xml  → parse XML → list of items with options
 *  2. For each item, GET current value via getcamparam.cgi / getcommparam.cgi
 *  3. To apply a change:
 *       stop recording → setcamparam.cgi or setcommparam.cgi → start recording
 *
 * Reference: HiDvrProtocol.java, HiCapabilityManager.java
 */
class HiDvrSettingsRepository {

    companion object {
        private const val TAG     = "Trafy.SettingsRepo"
        private const val CGI     = "/cgi-bin/hisnet"
        private const val WORKMODE = "NORM_REC"

        /**
         * Keys that use the "cam" CGI path (requires &-workmode=).
         * All other keys use the "comm" CGI path.
         * Source: HiDvrProtocol.java getParamType()
         */
        private val CAM_TYPE_KEYS = setOf(
            "MIRROR", "WATERMARKID", "MD_SENSITIVITY", "ENC_PAYLOAD_TYPE",
            "FLIP", "Rec_Split_Time", "MEDIAMODE", "ENABLEWATERMARK", "ANTIFLICKER"
        )
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Fetches the full settings list from the camera, including current values.
     * Returns null if the camera is unreachable or the XML is unparseable.
     */
    suspend fun fetchAll(deviceIp: String): List<SettingItem>? {
        Log.i(TAG, "fetchAll deviceIp=$deviceIp")

        val xmlBody = DashcamHttpClient.get("http://$deviceIp/app/bin/cammenu.xml")
        if (xmlBody == null) {
            Log.e(TAG, "Failed to fetch cammenu.xml")
            return null
        }
        Log.d(TAG, "cammenu.xml received (${xmlBody.length} bytes)")

        val skeletons = parseMenuXml(xmlBody)
        if (skeletons.isEmpty()) {
            Log.e(TAG, "XML parsed but produced 0 items")
            return null
        }
        Log.i(TAG, "Parsed ${skeletons.size} menu items from XML")

        // Fetch current values in parallel for speed
        return coroutineScope {
            skeletons.map { item ->
                async { item.withCurrentValue(deviceIp) }
            }.awaitAll()
        }
    }

    /**
     * Stops recording, applies one setting, then resumes recording.
     * Returns true if the CGI set call succeeded (HTTP 200).
     */
    suspend fun applySetting(deviceIp: String, key: String, value: String): Boolean {
        Log.i(TAG, "applySetting key=$key value=$value")
        val base = "http://$deviceIp$CGI"

        // Stop recording so the camera accepts the parameter change
        val stopped = DashcamHttpClient.probe("$base/workmodecmd.cgi?&-cmd=stop")
        Log.d(TAG, "Stop recording → $stopped")

        val setUrl = if (isCamType(key)) {
            "$base/setcamparam.cgi?&-workmode=$WORKMODE&-type=$key&-value=$value"
        } else {
            "$base/setcommparam.cgi?&-type=$key&-value=$value"
        }
        val success = DashcamHttpClient.probe(setUrl)
        Log.i(TAG, "Set $key=$value → success=$success")

        // Always resume recording afterwards
        val started = DashcamHttpClient.probe("$base/workmodecmd.cgi?&-cmd=start")
        Log.d(TAG, "Start recording → $started")

        return success
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun isCamType(key: String) = key in CAM_TYPE_KEYS

    /**
     * Executes an action-type item (Format SD Card, Factory Reset, etc.).
     * Returns the raw CGI response body, or null on network failure.
     */
    suspend fun executeAction(deviceIp: String, key: String): String? {
        val base = "http://$deviceIp$CGI"
        val cgiName = if (key.endsWith(".cgi?")) key else "$key.cgi?"
        val url = "$base/$cgiName"
        Log.i(TAG, "executeAction: $url")
        return DashcamHttpClient.get(url)
    }

    /**
     * Fetches current WiFi SSID and password from the camera.
     * The response is a JS-style variable block: var ssid="..."; var password="...";
     */
    suspend fun getWifiSettings(deviceIp: String): WifiSettings? {
        val body = DashcamHttpClient.get("http://$deviceIp$CGI/getwifi.cgi?") ?: return null
        val ssid = parseVarValue(body, "ssid") ?: return null
        val password = parseVarValue(body, "password") ?: return null
        Log.i(TAG, "getWifiSettings: ssid=$ssid password=***")
        return WifiSettings(ssid, password)
    }

    /**
     * Sets a new WiFi password on the camera (keeps existing SSID).
     * Returns true on HTTP 200.
     */
    suspend fun setWifiPassword(deviceIp: String, ssid: String, newPassword: String): Boolean {
        val url = "http://$deviceIp$CGI/setwifissid.cgi?&-ssid=$ssid&-password=$newPassword"
        Log.i(TAG, "setWifiPassword: ssid=$ssid")
        return DashcamHttpClient.probe(url)
    }

    data class WifiSettings(val ssid: String, val password: String)

    /**
     * Fetches the current value for this item and returns a copy with it filled in.
     * Action items (empty options) skip the CGI fetch.
     */
    private suspend fun SettingItem.withCurrentValue(deviceIp: String): SettingItem {
        if (options.isEmpty()) return this   // action-type: no value to fetch
        val base = "http://$deviceIp$CGI"
        val url = if (isCamType(key)) {
            "$base/getcamparam.cgi?&-workmode=$WORKMODE&-type=$key"
        } else {
            "$base/getcommparam.cgi?&-type=$key"
        }
        val body = DashcamHttpClient.get(url)
        val rawValue = body?.let { parseVarValue(it, "value") } ?: ""
        val label = options.find { it.value == rawValue }?.label ?: rawValue
        Log.v(TAG, "  $key → value='$rawValue' label='$label'")
        return copy(currentValue = rawValue, currentValueLabel = label)
    }

    /**
     * Parses the XML served by the camera at /app/bin/cammenu.xml.
     *
     * Expected structure:
     *   <camera>
     *     <menu id="MEDIAMODE" title="Video Resolution">
     *       <item id="1080P30" content="1080P 30fps"/>
     *       <item id="720P60"  content="720P 60fps"/>
     *     </menu>
     *     <menu id="AUDIO" title="Audio">
     *       <item id="0" content="Off"/>
     *       <item id="1" content="On"/>
     *     </menu>
     *   </camera>
     *
     * Items with no <item> children are action-type (Format SD, etc.) and are skipped.
     */
    private fun parseMenuXml(xml: String): List<SettingItem> {
        val result = mutableListOf<SettingItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var menuKey   = ""
            var menuTitle = ""
            val menuOptions = mutableListOf<SettingOption>()
            var insideMenu  = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "menu" -> {
                            val rawId    = parser.getAttributeValue(null, "id")    ?: ""
                            val rawTitle = parser.getAttributeValue(null, "title") ?: rawId
                            menuKey     = rawId
                            menuTitle   = HiDvrTranslations.title(rawId, rawTitle)
                            menuOptions.clear()
                            insideMenu  = true
                        }
                        "item" -> if (insideMenu) {
                            val id      = parser.getAttributeValue(null, "id")      ?: ""
                            val content = parser.getAttributeValue(null, "content") ?: id
                            if (id.isNotEmpty()) {
                                val label = HiDvrTranslations.optionLabel(menuKey, id, content)
                                menuOptions.add(SettingOption(id, label))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "menu" && insideMenu) {
                        insideMenu = false
                        if (menuKey.isNotEmpty()) {
                            result.add(SettingItem(
                                key              = menuKey,
                                title            = menuTitle,
                                currentValue     = "",
                                currentValueLabel = "",
                                options          = menuOptions.toList(),
                            ))
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML parse error: ${e.message}")
        }
        Log.d(TAG, "parseMenuXml → ${result.size} items: ${result.map { it.key }}")
        return result
    }

    /**
     * Extracts a value from a JavaScript-style variable response:
     *   var value="1080P30";
     * Returns null if the key is not found.
     */
    private fun parseVarValue(body: String, key: String): String? {
        val marker = "var $key=\""
        val start  = body.indexOf(marker)
        if (start < 0) return null
        val valStart = start + marker.length
        val end      = body.indexOf('"', valStart)
        if (end < 0) return null
        return body.substring(valStart, end)
    }
}
