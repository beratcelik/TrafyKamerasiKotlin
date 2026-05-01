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
        "MEDIAMODE"          to R.string.hidvr_title_mediamode,
        "AUDIO"              to R.string.hidvr_title_audio,
        "format"             to R.string.hidvr_title_format,
        "reset.cgi?"         to R.string.hidvr_title_reset,
        "getwifi.cgi?"       to R.string.hidvr_title_wifi_settings,
        "Rec_Split_Time"     to R.string.hidvr_title_rec_split_time,
        "GSR_SENSITIVITY"    to R.string.hidvr_title_gsr_sensitivity,
        "GSR_PARKING"        to R.string.hidvr_title_gsr_parking,
        "LOW_POWER_PROTECT"  to R.string.hidvr_title_low_power_protect,
        "VOLUME"             to R.string.hidvr_title_volume,
        "FLIP"               to R.string.hidvr_title_flip,
        "MIRROR"             to R.string.hidvr_title_mirror,
        // Migrated from legacy English-only TITLES map:
        "ENC_PAYLOAD_TYPE"   to R.string.hidvr_title_enc_payload_type,
        "MD_SENSITIVITY"     to R.string.hidvr_title_md_sensitivity,
        "SCREEN_DORMANT"     to R.string.hidvr_title_screen_dormant,
        "LOW_FPS_REC"        to R.string.hidvr_title_low_fps_rec,
        "LOW_FPS_REC_TIME"   to R.string.hidvr_title_low_fps_rec_time,
        "ENABLEWATERMARK"    to R.string.hidvr_title_enable_watermark,
        "WATERMARKID"        to R.string.hidvr_title_watermark_id,
        "ADAS_EN"            to R.string.hidvr_title_adas_en,
        "AUTO_POWEROFF"      to R.string.hidvr_title_auto_poweroff,
        "SPEECH"             to R.string.hidvr_title_speech,
        "SPEED_UNIT"         to R.string.hidvr_title_speed_unit,
        "UTC"                to R.string.hidvr_title_utc,
        "getwifissid.cgi?"   to R.string.hidvr_title_change_wifi_password,
        "getdeviceattr.cgi?" to R.string.hidvr_title_about_camera,
        "devlog"             to R.string.hidvr_title_export_logs,
        "PAR_SUR_VID"        to R.string.hidvr_title_par_sur_vid,
        "BACK_REC"           to R.string.hidvr_title_back_rec,
        "OSD"                to R.string.hidvr_title_osd,
        "LDC"                to R.string.hidvr_title_ldc,
        "TIME_OSD"           to R.string.hidvr_title_time_osd,
        "SENSITIVITY"        to R.string.hidvr_title_sensitivity,
    )

    /** Optional one-line explanations for non-obvious settings. */
    @StringRes
    private val DESC_RES = mapOf(
        "Rec_Split_Time"     to R.string.hidvr_desc_rec_split_time,
        "GSR_SENSITIVITY"    to R.string.hidvr_desc_gsr_sensitivity,
        "GSR_PARKING"        to R.string.hidvr_desc_gsr_parking,
        "LOW_POWER_PROTECT"  to R.string.hidvr_desc_low_power_protect,
        "ENC_PAYLOAD_TYPE"   to R.string.hidvr_desc_enc_payload_type,
        "MD_SENSITIVITY"     to R.string.hidvr_desc_md_sensitivity,
        "SCREEN_DORMANT"     to R.string.hidvr_desc_screen_dormant,
        "LOW_FPS_REC"        to R.string.hidvr_desc_low_fps_rec,
        "LOW_FPS_REC_TIME"   to R.string.hidvr_desc_low_fps_rec_time,
        "ENABLEWATERMARK"    to R.string.hidvr_desc_enable_watermark,
        "ADAS_EN"            to R.string.hidvr_desc_adas_en,
        "AUTO_POWEROFF"      to R.string.hidvr_desc_auto_poweroff,
        "PAR_SUR_VID"        to R.string.hidvr_desc_par_sur_vid,
        "LDC"                to R.string.hidvr_desc_ldc,
        "TIME_OSD"           to R.string.hidvr_desc_time_osd,
        "MIRROR"             to R.string.hidvr_desc_mirror,
        "FLIP"               to R.string.hidvr_desc_flip,
        "SPEECH"             to R.string.hidvr_desc_speech,
        "format"             to R.string.hidvr_desc_format,
        "reset.cgi?"         to R.string.hidvr_desc_reset,
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
        // SPEECH is a language picker, not on/off. Default firmware ships
        // with value 2 (Chinese). Confirmed values from log probing: 0/1/2
        // accepted by setcommparam. If the firmware ever flips the
        // English/Chinese mapping, swap the two res entries below.
        ("SPEECH"           to "0") to R.string.hidvr_opt_off,
        ("SPEECH"           to "1") to R.string.hidvr_opt_speech_english,
        ("SPEECH"           to "2") to R.string.hidvr_opt_speech_chinese,
        ("ADAS_EN"          to "0") to R.string.hidvr_opt_off,
        ("ADAS_EN"          to "1") to R.string.hidvr_opt_on,
        ("OSD"              to "0") to R.string.hidvr_opt_off,
        ("OSD"              to "1") to R.string.hidvr_opt_on,
        ("LDC"              to "0") to R.string.hidvr_opt_off,
        ("LDC"              to "1") to R.string.hidvr_opt_on,
        ("TIME_OSD"         to "0") to R.string.hidvr_opt_off,
        ("TIME_OSD"         to "1") to R.string.hidvr_opt_on,
        ("BACK_REC"         to "0") to R.string.hidvr_opt_off,
        ("BACK_REC"         to "1") to R.string.hidvr_opt_on,
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

    /**
     * Locale-neutral option labels. These are units / codec names / voltage
     * readings that mean the same thing in every language so we don't put
     * them in strings.xml.
     */
    private val NEUTRAL_OPTIONS = mapOf(
        "H264" to "H.264", "H265" to "H.265",
        "H.264" to "H.264", "H.265" to "H.265",
        "KMH" to "km/h", "MPH" to "mph",
        "11.8V" to "11.8 V", "12.0V" to "12.0 V", "12.2V" to "12.2 V",
    )

    /** Numeric duration ids — formatted via [hidvr_opt_minutes_fmt]/_hours_fmt. */
    private val MIN_OPTIONS = (1..15).associate { "${it}MIN" to it }
    private val HOUR_OPTIONS = (listOf(1..12) + listOf(listOf(24, 48))).flatten()
        .associate { "${it}H" to it }

    /** Static voltage / codec context entries that aren't locale-sensitive. */
    private val NEUTRAL_CONTEXT_OPTIONS = mapOf(
        ("LOW_POWER_PROTECT" to "1") to "11.8 V",
        ("LOW_POWER_PROTECT" to "2") to "12.0 V",
        ("ENC_PAYLOAD_TYPE"  to "0") to "H.264",
        ("ENC_PAYLOAD_TYPE"  to "1") to "H.265",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns a localized title for the given menu key, or the fallback. */
    fun title(ctx: Context, key: String, fallback: String): String {
        TITLE_RES[key]?.let { return ctx.getString(it) }
        return fallback
    }

    /** Returns a localized one-line description for [key], or null if none. */
    fun description(ctx: Context, key: String): String? =
        DESC_RES[key]?.let { ctx.getString(it) }

    /**
     * Returns a localized label for an option.
     * Priority: context-specific resource → generic resource → minutes/hours
     *   format → neutral (codec/voltage) → MEDIAMODE normalisation → fallback.
     */
    fun optionLabel(ctx: Context, menuKey: String, entryId: String, fallback: String): String {
        CONTEXT_OPTION_RES[menuKey to entryId]?.let { return ctx.getString(it) }
        GENERIC_OPTION_RES[entryId]?.let { return ctx.getString(it) }
        if (menuKey in MINUTES_FMT_CONTEXTS) {
            entryId.toIntOrNull()?.let { return ctx.getString(R.string.hidvr_opt_minutes_fmt, it) }
        }
        // Newer firmware contexts that map an integer entry id to a duration.
        when (menuKey) {
            "LOW_FPS_REC_TIME" -> when (entryId) {
                "0" -> return ctx.getString(R.string.hidvr_opt_seconds_fmt, 30)
                "1" -> return ctx.getString(R.string.hidvr_opt_minutes_fmt, 1)
                "2" -> return ctx.getString(R.string.hidvr_opt_minutes_fmt, 2)
                "3" -> return ctx.getString(R.string.hidvr_opt_minutes_fmt, 3)
            }
            "SCREEN_DORMANT" -> when (entryId) {
                "0" -> return ctx.getString(R.string.hidvr_opt_never)
                "1" -> return ctx.getString(R.string.hidvr_opt_minutes_fmt, 1)
                "2" -> return ctx.getString(R.string.hidvr_opt_minutes_fmt, 3)
                "3" -> return ctx.getString(R.string.hidvr_opt_minutes_fmt, 5)
            }
        }
        // Symbolic ASCII ids: codecs, units, parking modes, durations.
        when (entryId) {
            "NONE" -> return ctx.getString(R.string.hidvr_opt_none)
            "PAR"  -> return ctx.getString(R.string.hidvr_opt_parking)
            "LPR"  -> return ctx.getString(R.string.hidvr_opt_low_power_rec)
        }
        MIN_OPTIONS[entryId]?.let { return ctx.getString(R.string.hidvr_opt_minutes_fmt, it) }
        HOUR_OPTIONS[entryId]?.let { return ctx.getString(R.string.hidvr_opt_hours_fmt, it) }
        NEUTRAL_OPTIONS[entryId]?.let { return it }
        NEUTRAL_CONTEXT_OPTIONS[menuKey to entryId]?.let { return it }
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
