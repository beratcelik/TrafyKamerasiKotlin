package com.example.trafykamerasikotlin.data.settings

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusMenuParser
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusProtocol
import com.example.trafykamerasikotlin.data.generalplus.GeneralplusSession
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.model.SettingOption

/**
 * Fetches and applies dashcam settings for GeneralPlus devices (GPSOCKET TCP protocol).
 *
 * Setting types (from XML <Type> element):
 *   0 = enum   — GetParameter/SetParameter with single-byte value index
 *   1 = action — SetParameter(settingId, 0x00) to trigger (Format SD, Factory Reset)
 *   2 = string — GetParameter returns zero-padded string; SetParameterString to write
 *
 * Fetch flow (single TCP session):
 *   1. GetParameterFile → collect XML chunks until </Menu>
 *   2. Parse all settings from XML
 *   3. For each setting: GetParameter → current value
 *
 * Apply flow (new TCP session per call — protocol is NOT stateful):
 *   SetParameter(settingId, newValueIdx) — settingId is embedded in the 17-byte packet.
 *
 * Protocol confirmed from PcapDroid captures.
 */
class GeneralplusSettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "Trafy.GPRepo"

        // Setting type constants (from XML <Type>)
        private const val GP_TYPE_ENUM   = 0
        private const val GP_TYPE_ACTION = 1
        private const val GP_TYPE_STRING = 2

        // Setting IDs for action and string settings (from camera Menu.xml)
        private const val ID_FORMAT    = 0x0207  // 519 — Format SD card
        private const val ID_RESET     = 0x0208  // 520 — Factory reset
        private const val ID_WIFI_SSID = 0x0300  // 768 — WiFi SSID (string, read-only display)
        private const val ID_WIFI_PASS = 0x0301  // 769 — WiFi password (string, write-only via dialog)
        // Cam's own display-language setting. Firmware only supports English /
        // Traditional Chinese / Simplified Chinese — none useful for Turkish
        // users — and Trafy Uno has no built-in screen anyway, so this only
        // affects timestamp overlay format. Hidden from the settings list.
        private const val ID_LANGUAGE  = 0x0203  // 515
        // ACC-Off Behavior. Cam advertises it as writable but the firmware
        // ACKs SetParameter and silently drops the value (verify-after-set
        // confirmed the readback never changes). The OEM Viidure app also
        // hides this setting, which suggests the firmware just doesn't
        // expose a working write path for it. Hidden to match Viidure.
        private const val ID_ACC_OFF   = 0x0009  // 9

        // Semantic keys used by SettingsScreen/SettingsViewModel for special item types.
        // Format/Reset use HiDVR's existing DESTRUCTIVE_KEYS so the confirmation dialog fires.
        // WiFi uses HiDVR's "getwifi.cgi?" key so the WiFi password dialog opens.
        const val KEY_FORMAT = "format"
        const val KEY_RESET  = "reset.cgi?"
        const val KEY_WIFI   = "getwifi.cgi?"
    }

    data class WifiSettings(val ssid: String, val password: String)

    // Values cached during fetchAll so the WiFi dialog doesn't need a second session.
    private var cachedWifiSsid     = ""
    private var cachedWifiPassword = ""

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Fetches all settings from the camera's Menu.xml, reads current values,
     * and returns a list ready for the settings screen.
     * Returns null if the session fails or XML is missing.
     */
    suspend fun fetchAll(deviceIp: String): List<SettingItem>? {
        Log.i(TAG, "fetchAll: $deviceIp")

        return GeneralplusSession.withSession { send, sendPacket, receive ->
            // ── Step 1: GetParameterFile → collect XML ────────────────────────
            send(GeneralplusProtocol.MODE_GENERAL, GeneralplusProtocol.CMD_GET_PARAMETER_FILE)
            Log.d(TAG, "GetParameterFile sent")

            val xmlBuilder = StringBuilder()
            var chunkCount = 0
            while (true) {
                val chunk = receive(GeneralplusProtocol.CMD_GET_PARAMETER_FILE)
                if (chunk == null) {
                    Log.e(TAG, "GetParameterFile: null chunk at #$chunkCount")
                    break
                }
                xmlBuilder.append(String(chunk.data, Charsets.UTF_8))
                chunkCount++
                Log.v(TAG, "XML chunk #$chunkCount: +${chunk.data.size} B, total=${xmlBuilder.length} B")
                if (xmlBuilder.contains("</Menu>")) break
            }

            if (!xmlBuilder.contains("</Menu>")) {
                Log.e(TAG, "GetParameterFile: never saw </Menu> after $chunkCount chunks")
                return@withSession null
            }
            Log.i(TAG, "XML received: $chunkCount chunks, ${xmlBuilder.length} bytes")

            // Trim trailing garbage after </Menu> (last chunk may have padding bytes)
            val endTag = "</Menu>"
            val xmlEnd = xmlBuilder.indexOf(endTag)
            val xmlString = if (xmlEnd >= 0) xmlBuilder.substring(0, xmlEnd + endTag.length)
                            else xmlBuilder.toString()

            // ── Step 2: Parse XML ─────────────────────────────────────────────
            val allSettings = GeneralplusMenuParser.parse(xmlString.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "Parsed ${allSettings.size} settings: ${allSettings.map { "(${it.id},t=${it.type})" }}")

            // ── Step 3: Build SettingItems ─────────────────────────────────────
            val items = mutableListOf<SettingItem>()
            for (gpSetting in allSettings) {
                val id = gpSetting.id

                // Skip the cam's display-language setting (see ID_LANGUAGE comment).
                if (id == ID_LANGUAGE) continue
                // Skip ACC-Off Behavior — firmware advertises it but won't apply writes.
                if (id == ID_ACC_OFF) continue

                // WiFi password: cache it for the WiFi dialog but don't add to the settings list
                if (id == ID_WIFI_PASS) {
                    if (gpSetting.type == GP_TYPE_STRING) {
                        sendPacket(GeneralplusProtocol.buildGetParameter(0, id))
                        val resp = receive(GeneralplusProtocol.CMD_MENU_GET_PARAMETER)
                        if (resp != null && resp.data.isNotEmpty()) {
                            cachedWifiPassword = String(resp.data, Charsets.UTF_8).trimEnd('\u0000')
                            Log.d(TAG, "Cached WiFi password (${cachedWifiPassword.length} chars)")
                        }
                    }
                    continue
                }

                when (gpSetting.type) {
                    GP_TYPE_ENUM -> {
                        val options = gpSetting.values.map { v ->
                            SettingOption(
                                value = v.id.toString(),
                                label = GeneralplusTranslations.valueLabel(context, id, v.id, v.name),
                            )
                        }
                        if (options.isEmpty()) {
                            // No values in XML — show as read-only display item
                            items.add(SettingItem(id.toString(),
                                GeneralplusTranslations.title(context, id, gpSetting.name), "", "", emptyList()))
                            continue
                        }
                        sendPacket(GeneralplusProtocol.buildGetParameter(0, id))
                        val resp = receive(GeneralplusProtocol.CMD_MENU_GET_PARAMETER)
                        if (resp == null) {
                            Log.w(TAG, "GetParameter($id) no ACK — skipping")
                            continue
                        }
                        val currentIdx = if (resp.data.isNotEmpty()) resp.data[0].toInt() and 0xFF else 0
                        val currentOption = options.find { it.value == currentIdx.toString() }
                        Log.d(TAG, "Setting $id: idx=$currentIdx label=${currentOption?.label}")
                        items.add(SettingItem(
                            key               = id.toString(),
                            title             = GeneralplusTranslations.title(context, id, gpSetting.name),
                            currentValue      = currentIdx.toString(),
                            currentValueLabel = currentOption?.label ?: currentIdx.toString(),
                            options           = options,
                            description       = GeneralplusTranslations.description(context, id),
                        ))
                    }

                    GP_TYPE_ACTION -> {
                        val key = when (id) {
                            ID_FORMAT -> KEY_FORMAT
                            ID_RESET  -> KEY_RESET
                            else      -> id.toString()
                        }
                        items.add(SettingItem(
                            key               = key,
                            title             = GeneralplusTranslations.title(context, id, gpSetting.name),
                            currentValue      = "",
                            currentValueLabel = "",
                            options           = emptyList(),
                            description       = GeneralplusTranslations.description(context, id),
                        ))
                        Log.d(TAG, "Action setting $id key=$key")
                    }

                    GP_TYPE_STRING -> {
                        // WiFi SSID: use KEY_WIFI so the WiFi dialog opens on tap
                        val key = if (id == ID_WIFI_SSID) KEY_WIFI else id.toString()
                        sendPacket(GeneralplusProtocol.buildGetParameter(0, id))
                        val resp = receive(GeneralplusProtocol.CMD_MENU_GET_PARAMETER)
                        val currentStr = if (resp != null && resp.data.isNotEmpty()) {
                            String(resp.data, Charsets.UTF_8).trimEnd('\u0000')
                        } else ""
                        if (id == ID_WIFI_SSID) cachedWifiSsid = currentStr
                        Log.d(TAG, "String setting $id key=$key value='$currentStr'")
                        items.add(SettingItem(
                            key               = key,
                            title             = GeneralplusTranslations.title(context, id, gpSetting.name),
                            currentValue      = currentStr,
                            currentValueLabel = currentStr,
                            options           = emptyList(),  // no dropdown — tap opens WiFi dialog
                        ))
                    }

                    else -> Log.d(TAG, "Setting $id has unknown type ${gpSetting.type} — skipping")
                }
            }

            Log.i(TAG, "fetchAll complete: ${items.size} items")
            items
        }
    }

    /**
     * Applies a new enum value. key is settingId.toString().
     * String and action settings are NOT handled here (they use dedicated methods).
     * Returns true on ACK from camera.
     */
    /** Fine-grained outcome of an apply attempt — lets the VM show the right error. */
    enum class ApplyOutcome {
        SUCCESS,         // ACK received and verify GetParameter matches
        NO_CONNECTION,   // TCP session couldn't be established
        NAK,             // Camera explicitly rejected the write
        VERIFY_FAILED,   // Camera ACKed but readback shows old value — firmware silently dropped
    }

    /**
     * Variant of [applySetting] that returns a fine-grained outcome so the
     * caller can distinguish a transient connection problem (worth retrying)
     * from a firmware refusal (no point retrying — needs a different command
     * or just isn't supported).
     */
    suspend fun applySettingWithOutcome(deviceIp: String, key: String, value: String): ApplyOutcome {
        val settingId   = key.toIntOrNull()  ?: return ApplyOutcome.NAK
        val newValueIdx = value.toIntOrNull() ?: return ApplyOutcome.NAK
        Log.i(TAG, "applySetting settingId=$settingId newValueIdx=$newValueIdx")

        val outcome = GeneralplusSession.withSession { _, sendPacket, receive ->
            sendPacket(GeneralplusProtocol.buildSetParameter(0, settingId, newValueIdx))
            val resp = receive(GeneralplusProtocol.CMD_MENU_SET_PARAMETER)
            if (resp == null) {
                Log.e(TAG, "applySetting: no response for settingId=$settingId")
                return@withSession ApplyOutcome.NAK
            }
            if (!resp.isAck) {
                Log.e(TAG, "applySetting: NAK for settingId=$settingId (type=0x%02x)".format(resp.type))
                return@withSession ApplyOutcome.NAK
            }
            Log.i(TAG, "applySetting: ACK received — verifying with GetParameter")
            sendPacket(GeneralplusProtocol.buildGetParameter(0, settingId))
            val getResp = receive(GeneralplusProtocol.CMD_MENU_GET_PARAMETER)
            if (getResp == null || !getResp.isAck || getResp.data.isEmpty()) {
                Log.w(TAG, "applySetting: verify read failed for $settingId — trusting ACK")
                return@withSession ApplyOutcome.SUCCESS
            }
            val readback = getResp.data[0].toInt() and 0xFF
            if (readback != newValueIdx) {
                Log.e(TAG, "applySetting: verify mismatch for $settingId — wrote=$newValueIdx readback=$readback (firmware ignored write)")
                return@withSession ApplyOutcome.VERIFY_FAILED
            }
            Log.i(TAG, "applySetting: verify OK (readback=$readback)")
            ApplyOutcome.SUCCESS
        }
        return outcome ?: ApplyOutcome.NO_CONNECTION
    }

    /** Boolean wrapper retained for non-GP-aware callers. */
    suspend fun applySetting(deviceIp: String, key: String, value: String): Boolean =
        applySettingWithOutcome(deviceIp, key, value) == ApplyOutcome.SUCCESS

    /**
     * Triggers an action setting (Format SD card or Factory Reset).
     * [key] must be [KEY_FORMAT] or [KEY_RESET].
     * Returns true on ACK.
     */
    suspend fun triggerAction(deviceIp: String, key: String): Boolean {
        val settingId = when (key) {
            KEY_FORMAT -> ID_FORMAT
            KEY_RESET  -> ID_RESET
            else       -> { Log.w(TAG, "triggerAction: unknown key $key"); return false }
        }
        Log.i(TAG, "triggerAction key=$key settingId=$settingId")

        val success = GeneralplusSession.withSession { _, sendPacket, receive ->
            sendPacket(GeneralplusProtocol.buildSetParameter(0, settingId, 0x00))
            val ack = receive(GeneralplusProtocol.CMD_MENU_SET_PARAMETER)
            // No ACK is expected for Format (camera busy formatting) and Factory Reset
            // (camera reboots immediately). As long as the session connected and the
            // command was sent, treat it as success.
            if (ack == null) Log.w(TAG, "triggerAction: no ACK — command sent, camera may be processing")
            else             Log.i(TAG, "triggerAction: ACK received")
            true
        }
        return success == true
    }

    /**
     * Returns the WiFi SSID and password.
     * Uses values cached during [fetchAll] to avoid a second TCP session
     * (standalone GetParameter sessions may not work on all firmware).
     */
    suspend fun getWifiSettings(deviceIp: String): WifiSettings? {
        Log.i(TAG, "getWifiSettings (cached ssid='$cachedWifiSsid')")
        if (cachedWifiSsid.isNotEmpty() || cachedWifiPassword.isNotEmpty()) {
            return WifiSettings(cachedWifiSsid, cachedWifiPassword)
        }
        // Fall back to a live session if fetchAll hasn't run yet.
        return GeneralplusSession.withSession { _, sendPacket, receive ->
            sendPacket(GeneralplusProtocol.buildGetParameter(0, ID_WIFI_SSID))
            val ssidResp = receive(GeneralplusProtocol.CMD_MENU_GET_PARAMETER) ?: return@withSession null
            val ssid = String(ssidResp.data, Charsets.UTF_8).trimEnd('\u0000')

            sendPacket(GeneralplusProtocol.buildGetParameter(0, ID_WIFI_PASS))
            val passResp = receive(GeneralplusProtocol.CMD_MENU_GET_PARAMETER) ?: return@withSession null
            val password = String(passResp.data, Charsets.UTF_8).trimEnd('\u0000')

            Log.d(TAG, "getWifiSettings (live): ssid=$ssid password=***")
            WifiSettings(ssid, password)
        }
    }

    /**
     * Sets a new WiFi password on the camera.
     * Returns true on ACK.
     */
    suspend fun setWifiPassword(deviceIp: String, newPassword: String): Boolean {
        Log.i(TAG, "setWifiPassword")

        val success = GeneralplusSession.withSession { _, sendPacket, receive ->
            sendPacket(GeneralplusProtocol.buildSetParameterString(0, ID_WIFI_PASS, newPassword))
            val ack = receive(GeneralplusProtocol.CMD_MENU_SET_PARAMETER)
            // Changing the WiFi password may cause the camera to drop the connection
            // before sending ACK. Treat "command sent" as success regardless.
            if (ack == null) Log.w(TAG, "setWifiPassword: no ACK — camera may have applied and dropped connection")
            else             Log.i(TAG, "setWifiPassword: ACK received")
            true
        }
        if (success == true) cachedWifiPassword = newPassword
        return success == true
    }
}
