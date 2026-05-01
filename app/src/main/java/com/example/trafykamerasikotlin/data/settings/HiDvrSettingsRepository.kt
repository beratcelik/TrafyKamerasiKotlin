package com.example.trafykamerasikotlin.data.settings

import android.content.Context
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
class HiDvrSettingsRepository(private val context: Context) {

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
            "FLIP", "Rec_Split_Time", "MEDIAMODE", "ENABLEWATERMARK"
        )

        /**
         * Fallback menu used when the camera does not serve cammenu.xml.
         * Applies to "old HiSilicon" firmware variants (e.g. Trafy Dos / HiCV610)
         * whose SDK exposes capability CGIs per key but no aggregate menu XML.
         * Source: HiCapabilityManager.getDefultCapabilityList() in the reference app.
         */
        private val DEFAULT_DROPDOWN_KEYS = listOf(
            "MEDIAMODE", "ENC_PAYLOAD_TYPE", "AUDIO", "Rec_Split_Time",
            "GSR_SENSITIVITY", "GSR_PARKING", "LOW_POWER_PROTECT",
            "VOLUME", "FLIP", "MIRROR",
        )

        /**
         * Keys we hide from the UI even if the camera advertises them.
         * `ANTIFLICKER` is rejected by the firmware (writes are no-ops) and the OEM
         * GoLook app never shows it — keeping it would surface a dead control.
         */
        private val EXCLUDED_KEYS = setOf("ANTIFLICKER")

        /** Always-present action items for old-HiSilicon devices. */
        private val DEFAULT_ACTION_KEYS = listOf(
            "getwifi.cgi?",   // Wi-Fi dialog (intercepted in SettingsScreen)
            "format",         // Destructive: format SD card
            "reset.cgi?",     // Destructive: factory reset
        )

        /**
         * Keys whose capability CGI is unreliable on old HiSilicon firmware;
         * we hardcode the valid values instead. Source: HiDvrProtocol.getCapability().
         */
        private val HARDCODED_CAPABILITY = mapOf(
            "AUDIO"             to "0,1",
            "LOW_FPS_REC"       to "0,1",
            "LOW_FPS_REC_TIME"  to "0,1,2,3",
            "LOW_POWER_PROTECT" to "0,1,2",
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
        val skeletons: List<SettingItem> = if (xmlBody != null) {
            Log.d(TAG, "cammenu.xml received (${xmlBody.length} bytes)")
            val parsed = parseMenuXml(xmlBody).filter { it.key !in EXCLUDED_KEYS }
            Log.i(TAG, "Parsed ${parsed.size} menu items from XML")
            parsed
        } else {
            Log.w(TAG, "cammenu.xml not available — falling back to default capability list")
            emptyList()
        }

        // Old-HiSilicon fallback: no cammenu.xml, probe each default key's capability CGI.
        val items = if (skeletons.isEmpty()) fetchDefaultItems(deviceIp) else skeletons
        if (items.isEmpty()) {
            Log.e(TAG, "No menu items — neither cammenu.xml nor capability CGIs returned data")
            return null
        }

        // Fetch current values in parallel for speed
        return coroutineScope {
            items.map { item ->
                async { item.withCurrentValue(deviceIp) }
            }.awaitAll()
        }
    }

    /**
     * Builds the settings list for old-HiSilicon firmware that doesn't serve
     * cammenu.xml (e.g. Trafy Dos / HiCV610_*).
     *
     * For each dropdown key, probes getcamparamcapability.cgi / getcommparamcapability.cgi
     * to discover valid option values; items whose capability fetch returns empty are
     * dropped so the UI never renders un-configurable rows. Action items (Format, Reset,
     * Wi-Fi) are always included.
     */
    private suspend fun fetchDefaultItems(deviceIp: String): List<SettingItem> = coroutineScope {
        val base = "http://$deviceIp$CGI"

        val dropdowns = DEFAULT_DROPDOWN_KEYS.map { key ->
            async {
                val raw = HARDCODED_CAPABILITY[key] ?: run {
                    val url = if (isCamType(key)) {
                        "$base/getcamparamcapability.cgi?&-workmode=$WORKMODE&-type=$key"
                    } else {
                        "$base/getcommparamcapability.cgi?&-type=$key"
                    }
                    DashcamHttpClient.get(url)?.let { parseVarValue(it, "capability") }
                }
                if (raw.isNullOrEmpty()) {
                    Log.v(TAG, "  $key → capability empty, skipping")
                    null
                } else {
                    val options = raw.split(",")
                        .filter { it.isNotEmpty() }
                        .map { value -> SettingOption(value, HiDvrTranslations.optionLabel(context, key, value, value)) }
                    SettingItem(
                        key               = key,
                        title             = HiDvrTranslations.title(context, key, key),
                        currentValue      = "",
                        currentValueLabel = "",
                        options           = options,
                        description       = HiDvrTranslations.description(context, key),
                    )
                }
            }
        }.awaitAll().filterNotNull()

        val actions = DEFAULT_ACTION_KEYS.map { key ->
            SettingItem(
                key               = key,
                title             = HiDvrTranslations.title(context, key, key),
                currentValue      = "",
                currentValueLabel = "",
                options           = emptyList(),
                description       = HiDvrTranslations.description(context, key),
            )
        }

        Log.i(TAG, "fetchDefaultItems: ${dropdowns.size} dropdowns + ${actions.size} actions = ${dropdowns.size + actions.size} items")
        dropdowns + actions
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
        // Some HiSilicon firmware returns HTTP 200 with a negative SvrFuncResult when
        // the device logically rejects the request (unsupported value, wrong mode, etc).
        // probe() would treat that as success; inspect the body to catch the rejection.
        val body = DashcamHttpClient.get(setUrl)
        val success = body != null && !isCgiFailure(body)
        Log.i(TAG, "Set $key=$value → success=$success (body=${body?.take(80)})")

        // Always resume recording afterwards
        val started = DashcamHttpClient.probe("$base/workmodecmd.cgi?&-cmd=start")
        Log.d(TAG, "Start recording → $started")

        return success
    }

    /**
     * Detects HiSilicon CGI logical-error responses. Example body:
     *   SvrFuncResult="-2222"
     *   SvrFuncResult="-1"
     * A negative result on any `SvrFuncResult` line signals device rejection.
     */
    private fun isCgiFailure(body: String): Boolean {
        val marker = "SvrFuncResult=\""
        var idx = body.indexOf(marker)
        while (idx >= 0) {
            val start = idx + marker.length
            val end = body.indexOf('"', start)
            if (end < 0) break
            if (body.substring(start, end).startsWith("-")) return true
            idx = body.indexOf(marker, end)
        }
        return false
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
        // Newer HiSilicon firmware exposes `var ssid="…"`; old variants (Trafy Dos /
        // HiCV610) expose `var wifissid="…"` and omit password entirely.
        val ssid = parseVarValue(body, "ssid")
            ?: parseVarValue(body, "wifissid")
            ?: return null
        val password = parseVarValue(body, "password") ?: ""
        Log.i(TAG, "getWifiSettings: ssid=$ssid password=${if (password.isEmpty()) "<not returned>" else "***"}")
        return WifiSettings(ssid, password)
    }

    /**
     * Sets a new WiFi password on the camera (keeps existing SSID).
     * Tries the newer `setwifissid.cgi` endpoint first; falls back to the old
     * HiSilicon `setwifi.cgi` form for firmware that rejects the newer path.
     */
    suspend fun setWifiPassword(deviceIp: String, ssid: String, newPassword: String): Boolean {
        val base = "http://$deviceIp$CGI"
        Log.i(TAG, "setWifiPassword: ssid=$ssid")
        if (DashcamHttpClient.probe("$base/setwifissid.cgi?&-ssid=$ssid&-password=$newPassword")) {
            return true
        }
        Log.w(TAG, "setwifissid.cgi failed — trying old-HiSilicon setwifi.cgi")
        return DashcamHttpClient.probe("$base/setwifi.cgi?&-wifissid=$ssid&-wifikey=$newPassword")
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
                            menuTitle   = HiDvrTranslations.title(context, rawId, rawTitle)
                            menuOptions.clear()
                            insideMenu  = true
                        }
                        "item" -> if (insideMenu) {
                            val id      = parser.getAttributeValue(null, "id")      ?: ""
                            val content = parser.getAttributeValue(null, "content") ?: id
                            if (id.isNotEmpty()) {
                                val label = HiDvrTranslations.optionLabel(context, menuKey, id, content)
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
                                description      = HiDvrTranslations.description(context, menuKey),
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
