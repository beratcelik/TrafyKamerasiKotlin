package com.example.trafykamerasikotlin.data.settings

/**
 * English display strings for Easytech/Allwinner camera settings.
 *
 * Key names sourced from EeasytechConst.java in golook-jadx.
 * Option values are the raw strings returned by /app/getparamitems.
 */
object EeasytechTranslations {

    private val TITLES = mapOf(
        "rec_resolution"    to "Video Resolution",
        "rec_split_duration" to "Clip Length",
        "mic"               to "Audio",
        "light_fre"         to "Anti-Flicker",
        "encodec"           to "Video Codec",
        "video_flip"        to "Flip & Mirror",
        "gsr_sensitivity"   to "G-Sensor Sensitivity",
        "parking_mode"      to "Parking Mode",
        "ev"                to "Exposure",
        "speaker"           to "Volume",
        "screen_standby"    to "Screen Sleep",
        "osd"               to "Date Stamp",
        "speed_unit"        to "Speed Unit",
        "lowfps_rec"        to "Timelapse Recording",
        "timelapse_rate"    to "Timelapse Rate",
        "park_gsr_sensitivity" to "Parking G-Sensor",
        "park_record_time"  to "Parking Record Time",
        "parking_monitor"   to "Parking Monitor",
        "lowbat_protect"    to "Low Battery Protection",
        "bat_vol"           to "Cutoff Voltage",
        "voice_control"     to "Voice Control",
        "audio_change"      to "Audio Output",
        "gps"               to "GPS",
    )

    // Generic option-value → label. Applied when no context-specific entry exists.
    private val GENERIC_OPTIONS = mapOf(
        "on"         to "On",
        "off"        to "Off",
        "high"       to "High",
        "middle"     to "Middle",
        "low"        to "Low",
        "very high"  to "Very High",
        "front"      to "Front",
        "back"       to "Back",
        "normrec"    to "Normal Recording",
        "timelapse"  to "Timelapse",
        // Time durations
        "1MIN"       to "1 min",
        "2MIN"       to "2 min",
        "3MIN"       to "3 min",
        "5MIN"       to "5 min",
        "15min"      to "15 min",
        "1h"         to "1 hour",
        "2h"         to "2 hours",
        "3h"         to "3 hours",
        "4h"         to "4 hours",
        "5h"         to "5 hours",
        "6h"         to "6 hours",
        "7h"         to "7 hours",
        "8h"         to "8 hours",
        "9h"         to "9 hours",
        "10h"        to "10 hours",
        "12h"        to "12 hours",
        "24h"        to "24 hours",
        "48h"        to "48 hours",
        "1hour"      to "1 hour",
        "2hour"      to "2 hours",
        "3hour"      to "3 hours",
        "4hour"      to "4 hours",
        "5hour"      to "5 hours",
        "6hour"      to "6 hours",
        "7hour"      to "7 hours",
        "8hour"      to "8 hours",
        "9hour"      to "9 hours",
        "10hour"     to "10 hours",
        "11hour"     to "11 hours",
        "12hour"     to "12 hours",
        "24hour"     to "24 hours",
        "48hour"     to "48 hours",
        // Frequency
        "50hz"       to "50 Hz",
        "60hz"       to "60 Hz",
        "50HZ"       to "50 Hz",
        "60HZ"       to "60 Hz",
        // EV
        "-3"         to "-3",
        "-2"         to "-2",
        "-1"         to "-1",
        "0"          to "0",
        "1"          to "+1",
        "2"          to "+2",
        "3"          to "+3",
        // Speed unit
        "kmh"        to "km/h",
        "mph"        to "mph",
        "KMH"        to "km/h",
        "MPH"        to "mph",
    )

    // Context-specific overrides: (paramKey to optionValue) → label
    private val CONTEXT_OPTIONS = mapOf(
        ("encodec" to "H264")       to "H.264",
        ("encodec" to "H265")       to "H.265",
        ("encodec" to "h264")       to "H.264",
        ("encodec" to "h265")       to "H.265",
        ("video_flip" to "normal")  to "Normal",
        ("video_flip" to "flip")    to "Flip",
        ("video_flip" to "mirror")  to "Mirror",
        ("video_flip" to "flip_mirror") to "Flip & Mirror",
        ("speaker" to "0")          to "Mute",
        ("speaker" to "1")          to "Low",
        ("speaker" to "2")          to "Medium",
        ("speaker" to "3")          to "High",
        ("osd" to "0")              to "Off",
        ("osd" to "1")              to "On",
        ("parking_mode" to "0")     to "Off",
        ("parking_mode" to "1")     to "On",
    )

    fun title(key: String, fallback: String = key): String =
        TITLES[key] ?: fallback

    fun optionLabel(key: String, value: String, fallback: String = value): String =
        CONTEXT_OPTIONS[key to value]
            ?: GENERIC_OPTIONS[value]
            ?: fallback
}
