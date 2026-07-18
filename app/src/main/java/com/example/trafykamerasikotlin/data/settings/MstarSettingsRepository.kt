package com.example.trafykamerasikotlin.data.settings

import android.content.Context
import android.util.Log
import android.util.Xml
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.model.SettingOption
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Fetches and applies dashcam settings for MStar / Hime3 devices — the
 * "Waycam" OEM protocol used by the Trafy Tres (model HY_3SENSOR_PARK_GPS,
 * SSID `Hime3Dvr_…`, cam IP 192.168.1.1).
 *
 * This is a DIFFERENT protocol from both HiSilicon (HiDVR) and Easytech,
 * even though all three share the "cammenu.xml capability menu + current
 * values" shape. MStar speaks a single `Config.cgi` endpoint:
 *
 *  1. GET /cammenu.xml
 *       → capability menu. `<menu title id>` groups, each with `<item id>label</item>`
 *         children. Menus with no children are actions (Format SD, factory reset,
 *         Wi-Fi, About). NOTE: the option label is the element TEXT, not a
 *         `content=` attribute (that's the HiSilicon shape).
 *  2. GET /cgi-bin/Config.cgi?action=get&property=Camera.Menu.*
 *       → all current values as one flat blob:
 *         `0OK…Camera.Menu.VideoRes=1440PCamera.Menu.EncodeType=H264…0OK`
 *         (a leading `Usage ./CGI_PROCESS.sh …` noise line may precede the pairs).
 *  3. Merge menu + values into a List<SettingItem>.
 *
 * Apply:  GET /cgi-bin/Config.cgi?action=set&property=<key w/o "Camera.Menu.">&value=<id>
 *         The OEM strips the `Camera.Menu.` prefix on writes (but not reads).
 * Format: GET /cgi-bin/Config.cgi?action=set&property=SD0&value=format   (body must contain "0OK")
 * Reset:  GET /cgi-bin/Config.cgi?action=set&property=reset_to_default&value=1   (HTTP 200)
 * Wi-Fi:  GET /cgi-bin/Config.cgi?action=set&property=Net.WIFI_AP.SSID&value=…&property=Net.WIFI_AP.CryptoKey&value=…
 * About:  read the `Camera.Menu.DevInfo.*` fields out of the bulk-values blob.
 *
 * Reference: MstartDvrProtocol.java / MstarCapabilityManager.java / MstarDevConst.java
 * in golook-jadx, cross-checked against a live Waycam logcat trace of the Trafy Tres.
 */
class MstarSettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "Trafy.MstarRepo"
        private const val CGI = "/cgi-bin/Config.cgi"
        private const val MENU_PREFIX = "Camera.Menu."

        /**
         * Menu ids that are action-type (not value pickers). Each maps to the
         * sentinel key SettingsScreen already routes on, so the Wi-Fi dialog /
         * destructive-confirmation / About dialog light up without new UI branches:
         *   • SD0                  → "format"              (destructive)
         *   • reset_to_default     → "reset.cgi?"          (destructive)
         *   • Net.WIFI_AP.SSID     → "getwifi.cgi?"        (Wi-Fi dialog)
         *   • Camera.Menu.DevInfo.* → "getdeviceattr.cgi?" (About camera)
         * `Net.WIFI_AP.CryptoKey` is dropped — the Wi-Fi dialog edits SSID+password
         * together (mirrors MstarCapabilityManager, which removes it from the list).
         */
        private val ACTION_KEY_MAP = mapOf(
            "SD0"                   to "format",
            "reset_to_default"      to "reset.cgi?",
            "Net.WIFI_AP.SSID"      to "getwifi.cgi?",
            "Camera.Menu.DevInfo.*" to "getdeviceattr.cgi?",
        )
        private val DROPPED_KEYS = setOf("Net.WIFI_AP.CryptoKey")
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Fetches the full settings list (menu + current values) for display.
     * Returns null only if the camera is completely unreachable.
     */
    suspend fun fetchAll(deviceIp: String): List<SettingItem>? {
        Log.i(TAG, "fetchAll deviceIp=$deviceIp")
        val base = "http://$deviceIp"

        val xml = DashcamHttpClient.get("$base/cammenu.xml")
        if (xml == null) {
            Log.e(TAG, "cammenu.xml unreachable")
            return null
        }
        Log.d(TAG, "cammenu.xml received (${xml.length} bytes)")

        val entries = parseMenuXml(xml)
        if (entries.isEmpty()) {
            Log.e(TAG, "cammenu.xml parsed to zero entries")
            return null
        }

        // One bulk read gives every current value; no per-key round-trips needed.
        val bulk = DashcamHttpClient.get("$base$CGI?action=get&property=${MENU_PREFIX}*")
        val values = parseBulkValues(bulk)
        Log.d(TAG, "parsed ${values.size} current values")

        val items = entries.mapNotNull { entry ->
            val actionKey = ACTION_KEY_MAP[entry.id]
            when {
                actionKey != null -> SettingItem(
                    key               = actionKey,
                    title             = MstarTranslations.title(context, actionKey, entry.title),
                    currentValue      = "",
                    currentValueLabel = "",
                    options           = emptyList(),
                    description       = MstarTranslations.description(context, entry.id),
                )
                entry.id in DROPPED_KEYS -> null
                // A picker the firmware exposes with a single option is dead UX
                // (nothing to switch to) — hide it, matching the HiDvr/Easytech repos.
                entry.options.size < 2 -> {
                    Log.v(TAG, "  ${entry.id} → ${entry.options.size} option(s), skipping")
                    null
                }
                else -> {
                    val rawValue = values[entry.id] ?: ""
                    val label = entry.options.find { it.value == rawValue }?.label
                        ?: MstarTranslations.optionLabel(context, entry.id, rawValue, rawValue)
                    Log.v(TAG, "  ${entry.id} value='$rawValue' label='$label'")
                    SettingItem(
                        key               = entry.id,
                        title             = MstarTranslations.title(context, entry.id, entry.title),
                        currentValue      = rawValue,
                        currentValueLabel = label,
                        options           = entry.options,
                        description       = MstarTranslations.description(context, entry.id),
                    )
                }
            }
        }

        Log.i(TAG, "fetchAll → ${items.size} settings")
        return items
    }

    /**
     * Applies one setting. The OEM strips the `Camera.Menu.` prefix on writes.
     * Success = HTTP 200 with an `0OK`-framed body.
     */
    suspend fun applySetting(deviceIp: String, key: String, value: String): Boolean {
        val property = key.removePrefix(MENU_PREFIX)
        val url = "http://$deviceIp$CGI?action=set&property=$property&value=$value"
        Log.i(TAG, "applySetting $key=$value → $url")
        val body = DashcamHttpClient.get(url)
        val success = isOkResponse(body)
        Log.i(TAG, "applySetting $key=$value → success=$success (body=${body?.take(60)})")
        return success
    }

    /**
     * A successful Config.cgi write frames its body as `0`⏎`OK` … `0`⏎`OK`
     * (HTTP 200). The `0` and `OK` are on separate lines, so a literal `"0OK"`
     * substring check misses it — strip whitespace first. (MstartDvrProtocol
     * trusts HTTP 200 alone for non-JWD models; this is a slightly stricter
     * equivalent that still tolerates the newline framing.)
     */
    private fun isOkResponse(body: String?): Boolean =
        body != null && body.replace(Regex("\\s+"), "").contains("0OK")

    /**
     * Runs an action item. Endpoints mirror MstartDvrProtocol exactly:
     *   • format     → SD0&value=format   (body must contain "0OK")
     *   • reset.cgi? → reset_to_default&value=1
     * Returns "" on success, null on failure (matches the HiDvr/Easytech contract
     * SettingsViewModel expects).
     */
    suspend fun executeAction(deviceIp: String, key: String): String? {
        val base = "http://$deviceIp$CGI"
        return when (key) {
            "format" -> {
                val body = DashcamHttpClient.get("$base?action=set&property=SD0&value=format")
                val ok = isOkResponse(body)
                Log.i(TAG, "format SD → success=$ok")
                if (ok) "" else null
            }
            "reset.cgi?" -> {
                val ok = DashcamHttpClient.probe("$base?action=set&property=reset_to_default&value=1")
                Log.i(TAG, "factory reset → success=$ok")
                if (ok) "" else null
            }
            else -> {
                Log.w(TAG, "executeAction: unknown key $key")
                null
            }
        }
    }

    /**
     * Sets SSID and password in one request (both are required together; the
     * firmware has no separate get/set-wifi endpoint).
     *
     * The cam applies the new credentials and IMMEDIATELY restarts its AP,
     * which aborts this very HTTP connection before any response comes back —
     * a `SocketException: Software caused connection abort`, not a real failure.
     * [DashcamHttpClient.probeExpectingReset] treats that mid-response abort as
     * success (the request reached the cam) while still reporting failure when
     * the cam was never reachable, so a genuine no-op doesn't masquerade as a
     * saved password. Without this the write always looked "failed" ("Wi-Fi
     * ayarları yüklenemedi") even though the change took effect.
     */
    suspend fun setWifi(deviceIp: String, ssid: String, password: String): Boolean {
        val url = "http://$deviceIp$CGI?action=set&property=Net.WIFI_AP.SSID&value=$ssid" +
            "&property=Net.WIFI_AP.CryptoKey&value=$password"
        Log.i(TAG, "setWifi ssid=$ssid")
        val ok = DashcamHttpClient.probeExpectingReset(url)
        Log.i(TAG, "setWifi → success=$ok")
        return ok
    }

    /** Model + firmware version for the "About Camera" dialog. */
    data class DeviceInfoSummary(val model: String?, val softwareVersion: String?)

    /**
     * Reads the `Camera.Menu.DevInfo.*` fields. The bulk `Camera.Menu.*` blob
     * already carries them, so a single get suffices.
     */
    suspend fun fetchDeviceInfo(deviceIp: String): DeviceInfoSummary {
        val bulk = DashcamHttpClient.get("http://$deviceIp$CGI?action=get&property=${MENU_PREFIX}*")
        val values = parseBulkValues(bulk)
        val summary = DeviceInfoSummary(
            model           = values["${MENU_PREFIX}DevInfo.model"],
            softwareVersion = values["${MENU_PREFIX}DevInfo.ver"],
        )
        Log.i(TAG, "fetchDeviceInfo → $summary")
        return summary
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    private data class MenuEntry(
        val id: String,
        val title: String,
        val options: List<SettingOption>,
    )

    /**
     * Parses /cammenu.xml. Shape (MStar/Hime3):
     *   <camera>
     *     <menu title="Video format" id="Camera.Menu.EncodeType">
     *       <item id="H264">H264</item>
     *       <item id="H265">H265</item>
     *     </menu>
     *     <menu title="Format TF card" id="SD0"></menu>   <!-- action: no items -->
     *   </camera>
     * The option label is element text (not a `content=` attribute).
     */
    private fun parseMenuXml(xml: String): List<MenuEntry> {
        val result = mutableListOf<MenuEntry>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var menuId = ""
            var menuTitle = ""
            var options = mutableListOf<SettingOption>()
            var insideMenu = false
            var itemId: String? = null
            val itemText = StringBuilder()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "menu" -> {
                            menuId = parser.getAttributeValue(null, "id") ?: ""
                            menuTitle = parser.getAttributeValue(null, "title") ?: menuId
                            options = mutableListOf()
                            insideMenu = true
                        }
                        "item" -> if (insideMenu) {
                            itemId = parser.getAttributeValue(null, "id")
                            itemText.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> if (itemId != null) itemText.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "item" -> {
                            val id = itemId
                            if (insideMenu && !id.isNullOrEmpty()) {
                                val content = itemText.toString().trim().ifEmpty { id }
                                options.add(SettingOption(
                                    id,
                                    MstarTranslations.optionLabel(context, menuId, id, content),
                                ))
                            }
                            itemId = null
                        }
                        "menu" -> {
                            if (insideMenu && menuId.isNotEmpty()) {
                                result.add(MenuEntry(menuId, menuTitle, options.toList()))
                            }
                            insideMenu = false
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "cammenu.xml parse error: ${e.message}")
        }
        Log.d(TAG, "parseMenuXml → ${result.size} entries: ${result.map { it.id }}")
        return result
    }

    /**
     * Parses the `Camera.Menu.*` values blob into key → value.
     *
     * The cam returns newline-delimited pairs, framed by a leading `0`/`OK`
     * status and a trailing `0OK`:
     *   0
     *   OK
     *   Camera.Menu.VideoRes=1440P
     *   Camera.Menu.EncodeType=H264
     *   …
     *   Camera.Menu.SpeechSwitch=ON
     *   0OK
     * We first insert a break before every `Camera.Menu.` token so a firmware
     * variant that concatenates the pairs onto one line (as MstartDvrProtocol's
     * getValueForResult handles) parses identically, then read each line as
     * `key=value`, dropping any trailing `0OK` framing glued onto the last value.
     */
    private fun parseBulkValues(body: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (body.isNullOrEmpty()) return result
        val normalized = body.replace(Regex("(?=${MENU_PREFIX.replace(".", "\\.")})"), "\n")
        for (raw in normalized.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith(MENU_PREFIX)) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            var value = line.substring(eq + 1)
            val okIdx = value.indexOf("0OK")
            if (okIdx >= 0) value = value.substring(0, okIdx)
            result[key] = value.trim()
        }
        return result
    }
}
