package com.example.trafykamerasikotlin.data.gps

import com.example.trafykamerasikotlin.data.model.ChipsetProtocol

/**
 * Single dispatch point that maps a connected chipset's [ChipsetProtocol]
 * to the [CamGpsProvider] that knows its GPS retrieval protocol.
 *
 * Most chipsets currently return [NullCamGpsProvider] — they ship without
 * GPS hardware OR we haven't yet reverse-engineered the retrieval API.
 * Adding GPS for a new chipset is "wire a new provider here" and nothing
 * else changes upstream.
 */
object CamGpsProviderRegistry {
    fun providerFor(protocol: ChipsetProtocol): CamGpsProvider = when (protocol) {
        ChipsetProtocol.HI_DVR          -> HiDvrCamGpsProvider
        ChipsetProtocol.EEASYTECH       -> EeasytechCamGpsProvider
        ChipsetProtocol.ALLWINNER_V853  -> AllwinnerCamGpsProvider
        ChipsetProtocol.GENERALPLUS     -> NullCamGpsProvider     // no GPS evidence on Trafy Uno
        ChipsetProtocol.NOVATEK,
        ChipsetProtocol.MSTAR,
        ChipsetProtocol.MSTAR_HZ,
        ChipsetProtocol.SIGMA_STAR      -> NullCamGpsProvider     // out of scope this round
    }
}
