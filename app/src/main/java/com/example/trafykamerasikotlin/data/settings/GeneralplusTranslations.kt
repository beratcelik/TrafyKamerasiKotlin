package com.example.trafykamerasikotlin.data.settings

import android.content.Context
import com.example.trafykamerasikotlin.R

/**
 * Localized titles and value labels for GeneralPlus setting IDs.
 *
 * IDs and values sourced from PcapDroid capture of Viidure app against
 * HX2247-20241115 V1.0 firmware (camera: CarDV_YZJ_2ff4f / Trafy Uno).
 *
 * Strings live in `values/strings.xml` and `values-tr/strings.xml` so
 * Android's standard locale resolution picks the right translation. The
 * camera's Menu.xml uses Chinese names (e.g., 分辨率 for Resolution); for
 * value labels not listed here, [valueLabel] falls back to the XML name
 * if it is ASCII (e.g., "1080FHD 1920x1080"), or to the numeric ID otherwise.
 */
object GeneralplusTranslations {

    // ── Setting titles keyed by setting ID (decimal) → string resource ID ──
    private val TITLE_RES = mapOf(
        0   to R.string.gp_setting_title_0,    // 0x0000  Video Resolution
        1   to R.string.gp_setting_title_1,    // 0x0001  Exposure
        3   to R.string.gp_setting_title_3,    // 0x0003  Loop Recording
        5   to R.string.gp_setting_title_5,    // 0x0005  Audio Recording
        7   to R.string.gp_setting_title_7,    // 0x0007  Collision Sensing
        8   to R.string.gp_setting_title_8,    // 0x0008  Speaker Volume
        9   to R.string.gp_setting_title_9,    // 0x0009  ACC-Off Behavior
        10  to R.string.gp_setting_title_10,   // 0x000A  Time-Lapse Frame Rate
        515 to R.string.gp_setting_title_515,  // 0x0203  Language
        519 to R.string.gp_setting_title_519,  // 0x0207  Format SD Card
        520 to R.string.gp_setting_title_520,  // 0x0208  Restore Factory Settings
        521 to R.string.gp_setting_title_521,  // 0x0209  Firmware Version
        768 to R.string.gp_setting_title_768,  // 0x0300  Wi-Fi Settings
        769 to R.string.gp_setting_title_769,  // 0x0301  Wi-Fi Password
    )

    // ── Value labels keyed by (settingId, valueId) → string resource ID ────
    private val VALUE_RES: Map<Int, Map<Int, Int>> = mapOf(
        // Loop Recording (ID=3): 0=1min, 1=2min, 2=3min
        3 to mapOf(
            0 to R.string.gp_setting_value_3_0,
            1 to R.string.gp_setting_value_3_1,
            2 to R.string.gp_setting_value_3_2,
        ),
        // Audio Recording (ID=5): 0=Off, 1=On
        5 to mapOf(
            0 to R.string.gp_setting_value_5_0,
            1 to R.string.gp_setting_value_5_1,
        ),
        // Collision Sensing (ID=7): 0=Off, 1=Low, 2=Med, 3=High
        7 to mapOf(
            0 to R.string.gp_setting_value_7_0,
            1 to R.string.gp_setting_value_7_1,
            2 to R.string.gp_setting_value_7_2,
            3 to R.string.gp_setting_value_7_3,
        ),
        // Speaker Volume (ID=8): 0=Mute, 1=Low, 2=Med, 3=High
        8 to mapOf(
            0 to R.string.gp_setting_value_8_0,
            1 to R.string.gp_setting_value_8_1,
            2 to R.string.gp_setting_value_8_2,
            3 to R.string.gp_setting_value_8_3,
        ),
        // ACC-Off Behavior (ID=9): 0=Shutdown, 1=12h, 2=24h, 3=48h
        9 to mapOf(
            0 to R.string.gp_setting_value_9_0,
            1 to R.string.gp_setting_value_9_1,
            2 to R.string.gp_setting_value_9_2,
            3 to R.string.gp_setting_value_9_3,
        ),
        // Time-Lapse Frame Rate (ID=10): 0=1fps/1s, 1=2fps/1s, 2=5fps/1s, 3=1fps/2s, 4=1fps/5s
        10 to mapOf(
            0 to R.string.gp_setting_value_10_0,
            1 to R.string.gp_setting_value_10_1,
            2 to R.string.gp_setting_value_10_2,
            3 to R.string.gp_setting_value_10_3,
            4 to R.string.gp_setting_value_10_4,
        ),
        // Language (ID=515 = 0x0203): 0=English, 1=Traditional Chinese, 2=Simplified Chinese
        515 to mapOf(
            0 to R.string.gp_setting_value_515_0,
            1 to R.string.gp_setting_value_515_1,
            2 to R.string.gp_setting_value_515_2,
        ),
    )

    // ── One-line descriptions for non-obvious settings only ────────────────
    // Settings like Audio Recording, Speaker Volume, Wi-Fi SSID/Password,
    // Video Resolution and Language don't need explaining — leaving them out
    // here keeps the UI from looking cluttered.
    private val DESC_RES = mapOf(
        1   to R.string.gp_setting_desc_1,    // Exposure
        3   to R.string.gp_setting_desc_3,    // Loop Recording
        7   to R.string.gp_setting_desc_7,    // Collision Sensing
        9   to R.string.gp_setting_desc_9,    // ACC-Off Behavior
        10  to R.string.gp_setting_desc_10,   // Time-Lapse Frame Rate
        519 to R.string.gp_setting_desc_519,  // Format SD Card
        520 to R.string.gp_setting_desc_520,  // Restore Factory Settings
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns the localized title for the given setting ID, or [fallback] if not mapped. */
    fun title(context: Context, settingId: Int, fallback: String = ""): String =
        TITLE_RES[settingId]?.let { context.getString(it) } ?: fallback

    /** Returns the localized one-line description for [settingId], or null if none is defined. */
    fun description(context: Context, settingId: Int): String? =
        DESC_RES[settingId]?.let { context.getString(it) }

    /**
     * Returns the localized label for a value option.
     * Priority: known mapping → ASCII XML name → numeric ID string.
     */
    fun valueLabel(context: Context, settingId: Int, valueId: Int, fallback: String): String {
        VALUE_RES[settingId]?.get(valueId)?.let { return context.getString(it) }
        return if (fallback.isNotBlank() && fallback.all { it.code < 128 }) fallback
        else valueId.toString()
    }
}
