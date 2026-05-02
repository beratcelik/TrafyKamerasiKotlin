package com.example.trafykamerasikotlin.data.model

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
    ALLWINNER_V853(deviceIp = "192.168.35.1", displayName = "Allwinner V853 (A19)"),
}
