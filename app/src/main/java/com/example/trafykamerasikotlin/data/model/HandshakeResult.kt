package com.example.trafykamerasikotlin.data.model

sealed class HandshakeResult {
    data class Success(val deviceInfo: DeviceInfo) : HandshakeResult()
    data class Failure(val reason: FailureReason) : HandshakeResult()
}

enum class FailureReason {
    WIFI_NOT_CONNECTED,
    IP_NOT_OBTAINED,
    ALL_PROTOCOLS_FAILED,
    NO_DASHCAM_FOUND,
    WIFI_PERMISSION_DENIED,
    WIFI_CONNECT_FAILED,
    CONNECTION_LOST,
}
