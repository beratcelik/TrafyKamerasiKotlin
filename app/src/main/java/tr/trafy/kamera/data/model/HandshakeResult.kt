package tr.trafy.kamera.data.model

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
    /**
     * The phone's Location toggle is off. Android then returns an empty Wi-Fi
     * scan and hides the connected SSID from apps, so the cam can't be
     * discovered by name.
     */
    LOCATION_SERVICES_OFF,
    /** The phone's Wi-Fi radio is off — nothing to scan or join with. */
    WIFI_DISABLED,
}
