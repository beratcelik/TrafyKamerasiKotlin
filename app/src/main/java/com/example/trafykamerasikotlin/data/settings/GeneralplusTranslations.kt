package com.example.trafykamerasikotlin.data.settings

/**
 * English translations for GeneralPlus setting IDs and value IDs.
 *
 * IDs and values sourced from PcapDroid capture of Viidure app against
 * HX2247-20241115 V1.0 firmware (camera: CarDV_YZJ_2ff4f / Trafy Uno).
 *
 * The camera's Menu.xml uses Chinese names (e.g., 分辨率 for Resolution).
 * For value labels not listed here, [valueLabel] falls back to the XML name
 * if it is ASCII (e.g., "1080FHD 1920x1080"), or to the numeric ID otherwise.
 */
object GeneralplusTranslations {

    // ── Setting titles keyed by setting ID (decimal) ───────────────────────
    // Confirmed from PcapDroid PCAP (HX2247-20241115 firmware).
    private val TITLES = mapOf(
        0   to "Video Resolution",        // 0x0000
        1   to "Exposure",                // 0x0001
        3   to "Loop Recording",          // 0x0003
        5   to "Audio Recording",         // 0x0005
        7   to "Collision Sensing",       // 0x0007
        8   to "Speaker Volume",          // 0x0008
        9   to "ACC-Off Behavior",        // 0x0009
        10  to "Time-Lapse Frame Rate",   // 0x000A
        515 to "Language",               // 0x0203
        519 to "Format SD Card",         // 0x0207 — action
        520 to "Restore Factory Settings", // 0x0208 — action
        521 to "Firmware Version",        // 0x0209 — read-only
        768 to "Wi-Fi Settings",          // 0x0300 — string (opens WiFi dialog)
        769 to "Wi-Fi Password",          // 0x0301 — string (handled by WiFi dialog)
    )

    // ── Value labels keyed by (settingId, valueId) ─────────────────────────
    // Confirmed from PcapDroid PCAP.
    private val VALUES: Map<Int, Map<Int, String>> = mapOf(
        // Collision Sensing (ID=7): 0=Off, 1=Low, 2=Med, 3=High
        7 to mapOf(0 to "Off", 1 to "Low", 2 to "Medium", 3 to "High"),
        // Speaker Volume (ID=8): 0=Mute, 1=Low, 2=Med, 3=High
        8 to mapOf(0 to "Mute", 1 to "Low", 2 to "Medium", 3 to "High"),
        // Audio Recording (ID=5): 0=Off, 1=On
        5 to mapOf(0 to "Off", 1 to "On"),
        // Loop Recording (ID=3): 0=1min, 1=2min, 2=3min
        3 to mapOf(0 to "1 min", 1 to "2 min", 2 to "3 min"),
        // ACC-Off Behavior (ID=9): 0=Shutdown, 1=12h, 2=24h, 3=48h
        9 to mapOf(0 to "Shutdown", 1 to "12 h", 2 to "24 h", 3 to "48 h"),
        // Time-Lapse Frame Rate (ID=10): 0=1fps/1s, 1=2fps/1s, 2=5fps/1s, 3=1fps/2s, 4=1fps/5s
        10 to mapOf(
            0 to "1 fps / 1 s",
            1 to "2 fps / 1 s",
            2 to "5 fps / 1 s",
            3 to "1 fps / 2 s",
            4 to "1 fps / 5 s",
        ),
        // Language (ID=515 = 0x0203): 0=English, 1=Traditional Chinese, 2=Simplified Chinese
        515 to mapOf(0 to "English", 1 to "Traditional Chinese", 2 to "Simplified Chinese"),
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns an English title for the given setting ID, or [fallback] if not mapped. */
    fun title(settingId: Int, fallback: String = ""): String =
        TITLES[settingId] ?: fallback

    /**
     * Returns an English label for a value option.
     * Priority: known mapping → ASCII XML name → numeric ID string.
     */
    fun valueLabel(settingId: Int, valueId: Int, fallback: String): String {
        VALUES[settingId]?.get(valueId)?.let { return it }
        return if (fallback.isNotBlank() && fallback.all { it.code < 128 }) fallback
        else valueId.toString()
    }
}
