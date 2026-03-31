package com.example.trafykamerasikotlin.data.model

data class DeviceInfo(
    val protocol: ChipsetProtocol,
    val clientIp: String,
    val softwareVersion: String? = null,
    val model: String? = null,
    val product: String? = null,
)
