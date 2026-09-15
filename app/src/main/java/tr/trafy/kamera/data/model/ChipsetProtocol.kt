package tr.trafy.kamera.data.model

enum class ChipsetProtocol(
    val deviceIp: String,
    val displayName: String,
) {
    HI_DVR(     deviceIp = "192.168.0.1",   displayName = "HiSilicon DVR"),
    NOVATEK(    deviceIp = "192.168.1.254",  displayName = "Novatek"),
    MSTAR(      deviceIp = "192.168.1.1",    displayName = "MStar"),
    MSTAR_HZ(   deviceIp = "192.72.1.1",     displayName = "MStar HZ"),
    SIGMA_STAR( deviceIp = "192.168.201.1",  displayName = "SigmaStar"),
    EEASYTECH(  deviceIp = "192.168.169.1",  displayName = "Easytech"),
    GENERALPLUS(deviceIp = "192.168.25.1",   displayName = "Trafy Uno"),
    ALLWINNER_V853(deviceIp = "192.168.35.1", displayName = "Allwinner V853 (A19)");

    companion object {
        /**
         * Maps the phone's Wi-Fi client IP (handed out by the cam hotspot's DHCP)
         * to the chipset whose hotspot uses that subnet, or null for any other network.
         * Novatek check (ends with .254) must come before the plain MSTAR check
         * because both share the "192.168.1." prefix.
         *
         * Source: HandShakeManager.java lines 77–105.
         */
        fun forClientIp(clientIp: String): ChipsetProtocol? = when {
            clientIp.startsWith("192.168.25.")                                 -> GENERALPLUS
            clientIp.startsWith("192.168.0.")                                  -> HI_DVR
            clientIp.startsWith("192.168.1.") && clientIp.endsWith(".254")     -> NOVATEK
            clientIp.startsWith("192.168.1.")                                  -> MSTAR
            clientIp.startsWith("192.72.1.")                                   -> MSTAR_HZ
            clientIp.startsWith("192.168.201.")                                -> SIGMA_STAR
            clientIp.startsWith("192.168.169.")                                -> EEASYTECH
            clientIp.startsWith("192.168.35.")                                 -> ALLWINNER_V853
            else                                                               -> null
        }
    }
}
