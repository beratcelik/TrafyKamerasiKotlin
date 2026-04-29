package com.example.trafykamerasikotlin.data.model

/**
 * A single dashcam setting, ready to be displayed.
 *
 * [key]               – CGI parameter name, e.g. "MEDIAMODE"
 * [title]             – Human-readable label from cammenu.xml, e.g. "Video Resolution"
 * [currentValue]      – Raw current value, e.g. "1080P30"
 * [currentValueLabel] – Display label for current value, e.g. "1080P 30fps"
 * [options]           – Available choices; empty for action-type items (Format SD, etc.)
 * [description]       – Optional one-line explanation rendered below the title.
 *                       Null = no description; only set for settings whose meaning
 *                       isn't obvious from the title alone.
 */
data class SettingItem(
    val key: String,
    val title: String,
    val currentValue: String,
    val currentValueLabel: String,
    val options: List<SettingOption>,
    val description: String? = null,
)

data class SettingOption(
    val value: String,   // raw CGI value
    val label: String,   // display label
)
