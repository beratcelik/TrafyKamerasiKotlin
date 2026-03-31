package com.example.trafykamerasikotlin.data.settings

/**
 * English translations for HiDVR setting keys and option value IDs.
 *
 * The camera's cammenu.xml uses Chinese for [title] and [content] attributes,
 * but the [id] attributes are always ASCII identifiers.  We map those IDs to
 * English so the UI never shows Chinese.
 *
 * Sources: HIDevUtil.getTranslateTitle() / getTranslateEntrie(), HiDevConst.java
 */
object HiDvrTranslations {

    // ── Setting titles keyed by menu id ────────────────────────────────────

    private val TITLES = mapOf(
        "MEDIAMODE"         to "Video Resolution",
        "ENC_PAYLOAD_TYPE"  to "Video Codec",
        "AUDIO"             to "Audio",
        "Rec_Split_Time"    to "Recording Clip Length",
        "GSR_SENSITIVITY"   to "G-Sensor Sensitivity",
        "GSR_PARKING"       to "Parking Mode",
        "MD_SENSITIVITY"    to "Motion Detection",
        "SCREEN_DORMANT"    to "Screen Sleep",
        "LOW_FPS_REC"       to "Low-Speed Recording",
        "LOW_FPS_REC_TIME"  to "Low-Speed Duration",
        "LOW_POWER_PROTECT" to "Low Voltage Protection",
        "VOLUME"            to "Volume",
        "ANTIFLICKER"       to "Anti-Flicker",
        "FLIP"              to "Flip",
        "MIRROR"            to "Mirror",
        "ENABLEWATERMARK"   to "Watermark",
        "WATERMARKID"       to "Watermark ID",
        "ADAS_EN"           to "ADAS",
        "AUTO_POWEROFF"     to "Auto Power Off",
        "SPEECH"            to "Voice Control",
        "SPEED_UNIT"        to "Speed Unit",
        "UTC"               to "Time Zone",
        "format"            to "Format SD Card",
        "reset.cgi?"        to "Restore Factory Settings",
        "getwifi.cgi?"      to "Wi-Fi Settings",
        "getwifissid.cgi?"  to "Change Wi-Fi Password",
        "getdeviceattr.cgi?" to "About Camera",
        "devlog"            to "Export Logs",
        "PAR_SUR_VID"       to "Parking Surveillance",
        "BACK_REC"          to "Rear Camera",
        "OSD"               to "OSD Display",
        "LDC"               to "Lens Distortion Correction",
        "TIME_OSD"          to "Timestamp Overlay",
        "SENSITIVITY"       to "Sensitivity",
    )

    // ── Option value labels keyed by entry id ──────────────────────────────
    // These are context-independent; see optionLabel() for context overrides.

    private val GENERIC_OPTIONS = mapOf(
        // On/Off variants
        "ON"      to "On",   "on"    to "On",
        "OFF"     to "Off",  "off"   to "Off",
        "OPEN"    to "On",   "open"  to "On",
        "CLOSE"   to "Off",  "close" to "Off",
        "NONE"    to "None",
        "MUTE"    to "Mute",
        // Levels
        "HIGH"    to "High",
        "MIDDLE"  to "Medium",
        "LOW"     to "Low",
        // Codecs
        "H264"    to "H.264",
        "H265"    to "H.265",
        "H.264"   to "H.264",
        "H.265"   to "H.265",
        // Speed
        "KMH"     to "km/h",
        "MPH"     to "mph",
        // Parking rec
        "PAR"     to "Parking",
        "LPR"     to "Low-Power Rec",
        // Time durations — pretty-printed
        "1MIN"  to "1 min",  "2MIN"  to "2 min",  "3MIN"  to "3 min",
        "4MIN"  to "4 min",  "5MIN"  to "5 min",  "6MIN"  to "6 min",
        "7MIN"  to "7 min",  "8MIN"  to "8 min",  "9MIN"  to "9 min",
        "10MIN" to "10 min", "15MIN" to "15 min",
        "1H"    to "1 h",    "2H"    to "2 h",    "3H"    to "3 h",
        "4H"    to "4 h",    "5H"    to "5 h",    "6H"    to "6 h",
        "7H"    to "7 h",    "8H"    to "8 h",    "9H"    to "9 h",
        "10H"   to "10 h",   "11H"   to "11 h",   "12H"   to "12 h",
        "24H"   to "24 h",   "48H"   to "48 h",
        // Voltage
        "11.8V" to "11.8 V", "12.0V" to "12.0 V", "12.2V" to "12.2 V",
    )

    // Context-specific overrides: Pair(menuKey, entryId) → label
    private val CONTEXT_OPTIONS = mapOf(
        ("ANTIFLICKER"  to "0") to "50 Hz",
        ("ANTIFLICKER"  to "1") to "60 Hz",
        ("AUDIO"        to "0") to "Off",
        ("AUDIO"        to "1") to "On",
        ("FLIP"         to "0") to "Off",
        ("FLIP"         to "1") to "On",
        ("MIRROR"       to "0") to "Off",
        ("MIRROR"       to "1") to "On",
        ("ENABLEWATERMARK" to "0") to "Off",
        ("ENABLEWATERMARK" to "1") to "On",
        ("LOW_FPS_REC"  to "0") to "Off",
        ("LOW_FPS_REC"  to "1") to "On",
        ("LOW_FPS_REC_TIME" to "0") to "30 s",
        ("LOW_FPS_REC_TIME" to "1") to "1 min",
        ("LOW_FPS_REC_TIME" to "2") to "2 min",
        ("LOW_FPS_REC_TIME" to "3") to "3 min",
        ("LOW_POWER_PROTECT" to "0") to "Off",
        ("LOW_POWER_PROTECT" to "1") to "11.8 V",
        ("LOW_POWER_PROTECT" to "2") to "12.0 V",
        ("SCREEN_DORMANT" to "0") to "Never",
        ("SCREEN_DORMANT" to "1") to "1 min",
        ("SCREEN_DORMANT" to "2") to "3 min",
        ("SCREEN_DORMANT" to "3") to "5 min",
        ("GSR_SENSITIVITY" to "0") to "Off",
        ("GSR_SENSITIVITY" to "1") to "Low",
        ("GSR_SENSITIVITY" to "2") to "Medium",
        ("GSR_SENSITIVITY" to "3") to "High",
        ("GSR_PARKING"  to "0") to "Off",
        ("GSR_PARKING"  to "1") to "On",
        ("MD_SENSITIVITY" to "0") to "Off",
        ("MD_SENSITIVITY" to "1") to "Low",
        ("MD_SENSITIVITY" to "2") to "Medium",
        ("MD_SENSITIVITY" to "3") to "High",
        ("Rec_Split_Time" to "1") to "1 min",
        ("Rec_Split_Time" to "2") to "2 min",
        ("Rec_Split_Time" to "3") to "3 min",
        ("Rec_Split_Time" to "5") to "5 min",
        ("VOLUME"       to "0") to "Mute",
        ("VOLUME"       to "1") to "Low",
        ("VOLUME"       to "2") to "Medium",
        ("VOLUME"       to "3") to "High",
        ("ENC_PAYLOAD_TYPE" to "0") to "H.264",
        ("ENC_PAYLOAD_TYPE" to "1") to "H.265",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns an English title for the given menu key, or the fallback (e.g. the Chinese string). */
    fun title(key: String, fallback: String): String =
        TITLES[key] ?: fallback

    /**
     * Returns an English label for an option.
     * Priority: context-specific override > generic map > raw id (pretty-printed).
     */
    fun optionLabel(menuKey: String, entryId: String, fallback: String): String {
        CONTEXT_OPTIONS[menuKey to entryId]?.let { return it }
        GENERIC_OPTIONS[entryId]?.let { return it }
        // Last resort: prettify the raw id if it looks like plain ASCII
        return if (fallback.all { it.code < 128 }) fallback else entryId
    }
}
