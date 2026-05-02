package com.example.trafykamerasikotlin.data.settings

import android.content.Context
import androidx.annotation.StringRes
import com.example.trafykamerasikotlin.R

/**
 * Localized labels and descriptions for Easytech camera settings
 * (Trafy Dos / Dos Pro / Tres family).
 *
 * Key names sourced from EeasytechConst.java in golook-jadx; option values
 * are the raw strings returned by `/app/getparamitems`.
 *
 * Reuses `hidvr_opt_*` resources where the option label is locale-agnostic
 * shared vocabulary (Açık / Kapalı / Yüksek / Düşük / Orta / Sessiz, plus
 * the minutes_fmt and hours_fmt format strings) so we don't duplicate them.
 */
object EeasytechTranslations {

    @StringRes
    private val TITLE_RES = mapOf(
        "rec_resolution"        to R.string.eeasy_title_rec_resolution,
        "rec_split_duration"    to R.string.eeasy_title_rec_split_duration,
        "mic"                   to R.string.eeasy_title_mic,
        "light_fre"             to R.string.eeasy_title_light_fre,
        "encodec"               to R.string.eeasy_title_encodec,
        "video_flip"            to R.string.eeasy_title_video_flip,
        "gsr_sensitivity"       to R.string.eeasy_title_gsr_sensitivity,
        "parking_mode"          to R.string.eeasy_title_parking_mode,
        "ev"                    to R.string.eeasy_title_ev,
        "speaker"               to R.string.eeasy_title_speaker,
        "screen_standby"        to R.string.eeasy_title_screen_standby,
        "osd"                   to R.string.eeasy_title_osd,
        "speed_unit"            to R.string.eeasy_title_speed_unit,
        "lowfps_rec"            to R.string.eeasy_title_lowfps_rec,
        "timelapse_rate"        to R.string.eeasy_title_timelapse_rate,
        "park_gsr_sensitivity"  to R.string.eeasy_title_park_gsr_sensitivity,
        "park_record_time"      to R.string.eeasy_title_park_record_time,
        "parking_monitor"       to R.string.eeasy_title_parking_monitor,
        "lowbat_protect"        to R.string.eeasy_title_lowbat_protect,
        "bat_vol"               to R.string.eeasy_title_bat_vol,
        "voice_control"         to R.string.eeasy_title_voice_control,
        "audio_change"          to R.string.eeasy_title_audio_change,
        "gps"                   to R.string.eeasy_title_gps,
        "adas"                  to R.string.eeasy_title_adas,
    )

    /** One-line explanations for non-obvious settings. */
    @StringRes
    private val DESC_RES = mapOf(
        "rec_split_duration"    to R.string.eeasy_desc_rec_split_duration,
        "mic"                   to R.string.eeasy_desc_mic,
        "light_fre"             to R.string.eeasy_desc_light_fre,
        "encodec"               to R.string.eeasy_desc_encodec,
        "gsr_sensitivity"       to R.string.eeasy_desc_gsr_sensitivity,
        "parking_mode"          to R.string.eeasy_desc_parking_mode,
        "ev"                    to R.string.eeasy_desc_ev,
        "screen_standby"        to R.string.eeasy_desc_screen_standby,
        "lowfps_rec"            to R.string.eeasy_desc_lowfps_rec,
        "timelapse_rate"        to R.string.eeasy_desc_timelapse_rate,
        "park_gsr_sensitivity"  to R.string.eeasy_desc_park_gsr_sensitivity,
        "park_record_time"      to R.string.eeasy_desc_park_record_time,
        "parking_monitor"       to R.string.eeasy_desc_parking_monitor,
        "lowbat_protect"        to R.string.eeasy_desc_lowbat_protect,
        "bat_vol"               to R.string.eeasy_desc_bat_vol,
        "voice_control"         to R.string.eeasy_desc_voice_control,
        "audio_change"          to R.string.eeasy_desc_audio_change,
        "gps"                   to R.string.eeasy_desc_gps,
        "video_flip"            to R.string.eeasy_desc_video_flip,
        "adas"                  to R.string.eeasy_desc_adas,
    )

    /** Generic on/off/high/low/etc — case-folded so "ON"/"on"/"On" all map. */
    @StringRes
    private val GENERIC_OPTION_RES = mapOf(
        "on"        to R.string.hidvr_opt_on,
        "off"       to R.string.hidvr_opt_off,
        "high"      to R.string.hidvr_opt_high,
        "middle"    to R.string.hidvr_opt_medium,
        "medium"    to R.string.hidvr_opt_medium,
        "low"       to R.string.hidvr_opt_low,
        "very high" to R.string.eeasy_opt_very_high,
        "front"     to R.string.eeasy_opt_front,
        "back"      to R.string.eeasy_opt_back,
        "normrec"   to R.string.eeasy_opt_normrec,
        "timelapse" to R.string.eeasy_opt_timelapse,
        "mute"      to R.string.hidvr_opt_mute,
    )

    @StringRes
    private val CONTEXT_OPTION_RES = mapOf(
        ("video_flip" to "normal")      to R.string.eeasy_opt_flip_normal,
        ("video_flip" to "flip")        to R.string.eeasy_opt_flip_flip,
        ("video_flip" to "mirror")      to R.string.eeasy_opt_flip_mirror,
        ("video_flip" to "flip_mirror") to R.string.eeasy_opt_flip_flip_mirror,
        ("speaker"    to "0")           to R.string.hidvr_opt_mute,
        ("speaker"    to "1")           to R.string.hidvr_opt_low,
        ("speaker"    to "2")           to R.string.hidvr_opt_medium,
        ("speaker"    to "3")           to R.string.hidvr_opt_high,
        ("osd"          to "0")         to R.string.hidvr_opt_off,
        ("osd"          to "1")         to R.string.hidvr_opt_on,
        ("parking_mode" to "0")         to R.string.hidvr_opt_off,
        ("parking_mode" to "1")         to R.string.hidvr_opt_on,
        ("mic"          to "0")         to R.string.hidvr_opt_off,
        ("mic"          to "1")         to R.string.hidvr_opt_on,
        ("gps"          to "0")         to R.string.hidvr_opt_off,
        ("gps"          to "1")         to R.string.hidvr_opt_on,
        ("voice_control" to "0")        to R.string.hidvr_opt_off,
        ("voice_control" to "1")        to R.string.hidvr_opt_on,
        ("lowbat_protect" to "0")       to R.string.hidvr_opt_off,
        ("lowbat_protect" to "1")       to R.string.hidvr_opt_on,
        ("lowfps_rec"   to "0")         to R.string.hidvr_opt_off,
        ("lowfps_rec"   to "1")         to R.string.hidvr_opt_on,
    )

    /** Locale-neutral option labels (codecs / units / voltages). */
    private val NEUTRAL_OPTIONS = mapOf(
        "H264" to "H.264", "H265" to "H.265",
        "h264" to "H.264", "h265" to "H.265",
        "kmh"  to "km/h",  "mph"  to "mph",
        "KMH"  to "km/h",  "MPH"  to "mph",
        "50hz" to "50 Hz", "60hz" to "60 Hz",
        "50HZ" to "50 Hz", "60HZ" to "60 Hz",
    )

    /** Numeric duration ids — "1MIN", "5MIN", "1h", "12hour" → formatted strings. */
    private val MIN_ID_REGEX  = Regex("""^(\d+)\s*MIN$""", RegexOption.IGNORE_CASE)
    private val HOUR_ID_REGEX = Regex("""^(\d+)\s*(?:H|HOUR)$""", RegexOption.IGNORE_CASE)

    // ── Public API ─────────────────────────────────────────────────────────

    fun title(ctx: Context, key: String, fallback: String = key): String =
        TITLE_RES[key]?.let { ctx.getString(it) } ?: fallback

    fun description(ctx: Context, key: String): String? =
        DESC_RES[key]?.let { ctx.getString(it) }

    /**
     * Returns a localized label for an option.
     * Priority: context-specific resource → generic resource (case-folded) →
     * minutes/hours format → neutral (codec/voltage/Hz) → EV signed → fallback.
     */
    fun optionLabel(ctx: Context, menuKey: String, value: String, fallback: String = value): String {
        CONTEXT_OPTION_RES[menuKey to value]?.let { return ctx.getString(it) }
        GENERIC_OPTION_RES[value.lowercase()]?.let { return ctx.getString(it) }

        MIN_ID_REGEX.matchEntire(value)?.let {
            return ctx.getString(R.string.hidvr_opt_minutes_fmt, it.groupValues[1].toInt())
        }
        HOUR_ID_REGEX.matchEntire(value)?.let {
            return ctx.getString(R.string.hidvr_opt_hours_fmt, it.groupValues[1].toInt())
        }
        NEUTRAL_OPTIONS[value]?.let { return it }

        // EV: cam emits "-3".."3"; render with a leading + for positives so the
        // direction reads at a glance ("+1" vs "1").
        if (menuKey == "ev") {
            value.toIntOrNull()?.let { iv ->
                return when {
                    iv > 0 -> "+$iv"
                    else   -> iv.toString()
                }
            }
        }
        return fallback
    }
}
