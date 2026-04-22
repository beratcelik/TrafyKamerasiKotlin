package com.example.trafykamerasikotlin.data.settings

import android.content.Context
import androidx.annotation.StringRes
import com.example.trafykamerasikotlin.R

/**
 * Localized labels for HiDVR device-sourced setting keys and option value IDs.
 *
 * The camera's cammenu.xml serves Chinese labels, but the `id` attributes are
 * ASCII identifiers that stay stable across firmware variants. We map those IDs
 * to localized strings so the UI never shows Chinese — and so the app can add a
 * new language by dropping a file into values-XX/ rather than editing Kotlin.
 *
 * **Migration pattern (prototype):** entries in the *_RES maps win over the
 * legacy hardcoded English maps; keys not yet in *_RES fall through to the old
 * English strings. This lets us migrate incrementally without breaking
 * unmigrated keys.
 *
 * Sources: HIDevUtil.getTranslateTitle() / getTranslateEntrie(), HiDevConst.java
 */
object HiDvrTranslations {

    // ── Resource-backed translations (preferred) ───────────────────────────

    @StringRes
    private val TITLE_RES = mapOf(
        "MEDIAMODE"         to R.string.hidvr_title_mediamode,
        "AUDIO"             to R.string.hidvr_title_audio,
        "format"            to R.string.hidvr_title_format,
        "reset.cgi?"        to R.string.hidvr_title_reset,
        "getwifi.cgi?"      to R.string.hidvr_title_wifi_settings,
        "Rec_Split_Time"    to R.string.hidvr_title_rec_split_time,
        "GSR_SENSITIVITY"   to R.string.hidvr_title_gsr_sensitivity,
        "GSR_PARKING"       to R.string.hidvr_title_gsr_parking,
        "LOW_POWER_PROTECT" to R.string.hidvr_title_low_power_protect,
        "VOLUME"            to R.string.hidvr_title_volume,
        "FLIP"              to R.string.hidvr_title_flip,
        "MIRROR"            to R.string.hidvr_title_mirror,
    )

    @StringRes
    private val GENERIC_OPTION_RES = mapOf(
        "ON"     to R.string.hidvr_opt_on,     "on"     to R.string.hidvr_opt_on,
        "OPEN"   to R.string.hidvr_opt_on,     "open"   to R.string.hidvr_opt_on,
        "OFF"    to R.string.hidvr_opt_off,    "off"    to R.string.hidvr_opt_off,
        "CLOSE"  to R.string.hidvr_opt_off,    "close"  to R.string.hidvr_opt_off,
        "HIGH"   to R.string.hidvr_opt_high,
        "MIDDLE" to R.string.hidvr_opt_medium,
        "LOW"    to R.string.hidvr_opt_low,
        "MUTE"   to R.string.hidvr_opt_mute,
    )

    @StringRes
    private val CONTEXT_OPTION_RES = mapOf(
        // On/Off toggles keyed by numeric value
        ("AUDIO"            to "0") to R.string.hidvr_opt_off,
        ("AUDIO"            to "1") to R.string.hidvr_opt_on,
        ("FLIP"             to "0") to R.string.hidvr_opt_off,
        ("FLIP"             to "1") to R.string.hidvr_opt_on,
        ("MIRROR"           to "0") to R.string.hidvr_opt_off,
        ("MIRROR"           to "1") to R.string.hidvr_opt_on,
        ("ENABLEWATERMARK"  to "0") to R.string.hidvr_opt_off,
        ("ENABLEWATERMARK"  to "1") to R.string.hidvr_opt_on,
        ("LOW_FPS_REC"      to "0") to R.string.hidvr_opt_off,
        ("LOW_FPS_REC"      to "1") to R.string.hidvr_opt_on,
        ("GSR_PARKING"      to "0") to R.string.hidvr_opt_off,
        ("GSR_PARKING"      to "1") to R.string.hidvr_opt_on,
        // Four-level sensitivity ladders
        ("GSR_SENSITIVITY"  to "0") to R.string.hidvr_opt_off,
        ("GSR_SENSITIVITY"  to "1") to R.string.hidvr_opt_low,
        ("GSR_SENSITIVITY"  to "2") to R.string.hidvr_opt_medium,
        ("GSR_SENSITIVITY"  to "3") to R.string.hidvr_opt_high,
        ("MD_SENSITIVITY"   to "0") to R.string.hidvr_opt_off,
        ("MD_SENSITIVITY"   to "1") to R.string.hidvr_opt_low,
        ("MD_SENSITIVITY"   to "2") to R.string.hidvr_opt_medium,
        ("MD_SENSITIVITY"   to "3") to R.string.hidvr_opt_high,
        ("VOLUME"           to "0") to R.string.hidvr_opt_mute,
        ("VOLUME"           to "1") to R.string.hidvr_opt_low,
        ("VOLUME"           to "2") to R.string.hidvr_opt_medium,
        ("VOLUME"           to "3") to R.string.hidvr_opt_high,
        // Low voltage: 0 is Off; 1/2 remain voltage strings in the legacy map
        ("LOW_POWER_PROTECT" to "0") to R.string.hidvr_opt_off,
    )

    // Context+entry pairs that format a numeric minutes value. The entryId
    // (e.g. "1", "2") is parsed as an integer and plugged into the format string.
    private val MINUTES_FMT_CONTEXTS = setOf("Rec_Split_Time")

    // ── Legacy hardcoded English maps (fallback) ───────────────────────────
    //
    // Anything not migrated to the *_RES maps above still resolves via these
    // English strings. Incremental migration: move an entry up, delete it here.

    private val TITLES = mapOf(
        "ENC_PAYLOAD_TYPE"  to "Video Codec",
        "MD_SENSITIVITY"    to "Motion Detection",
        "SCREEN_DORMANT"    to "Screen Sleep",
        "LOW_FPS_REC"       to "Low-Speed Recording",
        "LOW_FPS_REC_TIME"  to "Low-Speed Duration",
        "ENABLEWATERMARK"   to "Watermark",
        "WATERMARKID"       to "Watermark ID",
        "ADAS_EN"           to "ADAS",
        "AUTO_POWEROFF"     to "Auto Power Off",
        "SPEECH"            to "Voice Control",
        "SPEED_UNIT"        to "Speed Unit",
        "UTC"               to "Time Zone",
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

    private val GENERIC_OPTIONS = mapOf(
        "NONE"    to "None",
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

    private val CONTEXT_OPTIONS = mapOf(
        ("LOW_FPS_REC_TIME" to "0") to "30 s",
        ("LOW_FPS_REC_TIME" to "1") to "1 min",
        ("LOW_FPS_REC_TIME" to "2") to "2 min",
        ("LOW_FPS_REC_TIME" to "3") to "3 min",
        ("LOW_POWER_PROTECT" to "1") to "11.8 V",
        ("LOW_POWER_PROTECT" to "2") to "12.0 V",
        ("SCREEN_DORMANT" to "0") to "Never",
        ("SCREEN_DORMANT" to "1") to "1 min",
        ("SCREEN_DORMANT" to "2") to "3 min",
        ("SCREEN_DORMANT" to "3") to "5 min",
        ("ENC_PAYLOAD_TYPE" to "0") to "H.264",
        ("ENC_PAYLOAD_TYPE" to "1") to "H.265",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns a localized title for the given menu key, or the fallback. */
    fun title(ctx: Context, key: String, fallback: String): String {
        TITLE_RES[key]?.let { return ctx.getString(it) }
        TITLES[key]?.let { return it }
        return fallback
    }

    /**
     * Returns a localized label for an option.
     * Priority: context-specific resource > generic resource > minutes format
     *   > legacy context map > legacy generic map > raw ASCII id > entry id.
     */
    fun optionLabel(ctx: Context, menuKey: String, entryId: String, fallback: String): String {
        CONTEXT_OPTION_RES[menuKey to entryId]?.let { return ctx.getString(it) }
        GENERIC_OPTION_RES[entryId]?.let { return ctx.getString(it) }
        if (menuKey in MINUTES_FMT_CONTEXTS) {
            entryId.toIntOrNull()?.let { return ctx.getString(R.string.hidvr_opt_minutes_fmt, it) }
        }
        CONTEXT_OPTIONS[menuKey to entryId]?.let { return it }
        GENERIC_OPTIONS[entryId]?.let { return it }
        val asciiFallback = if (fallback.all { it.code < 128 }) fallback else entryId
        return if (menuKey == "MEDIAMODE") normalizeMediamode(asciiFallback) else asciiFallback
    }

    /**
     * Human-readable form of a MEDIAMODE value.
     *
     * Old-HiSilicon capability CGI returns raw dual-channel encodings like
     * `1080P_1080P` (front_rear). Newer cammenu.xml usually provides a pretty
     * `content` attribute, but some firmware variants leave it equal to the id.
     *
     * Rules:
     *  - `1080P_1080P` (both halves equal) → `1080p`
     *  - `1080P_720P`                      → `1080p + 720p`
     *  - `1080P30`                         → `1080p30`
     *  - Anything else                     → lowercase-`P` only
     */
    private fun normalizeMediamode(raw: String): String {
        val parts = raw.split("_")
        return when {
            parts.size == 2 && parts[0] == parts[1] -> parts[0].lowercasePSuffix()
            parts.size == 2 -> "${parts[0].lowercasePSuffix()} + ${parts[1].lowercasePSuffix()}"
            else -> raw.lowercasePSuffix()
        }
    }

    private fun String.lowercasePSuffix(): String = replace("P", "p")
}
