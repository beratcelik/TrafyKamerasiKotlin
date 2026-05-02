package com.example.trafykamerasikotlin.data.model

data class DeviceInfo(
    val protocol: ChipsetProtocol,
    val clientIp: String,
    val softwareVersion: String? = null,
    val model: String? = null,
    val product: String? = null,
    /**
     * The Wi-Fi SSID we connected to. Used as a secondary product-identifying
     * signal when the firmware itself doesn't expose a model string — Easytech
     * cams (Trafy Dos Pro at the time of writing) only advertise a feature
     * bitmask over HTTP, so SSID is the next-best signal.
     */
    val ssid: String? = null,
)
