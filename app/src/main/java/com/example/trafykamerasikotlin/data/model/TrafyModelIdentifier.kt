package com.example.trafykamerasikotlin.data.model

/**
 * Maps an [DeviceInfo] to the marketing name of the Trafy product.
 *
 * Why we need this: Trafy's HiSilicon-based cams (Trafy Uno Pro, Trafy Dos,
 * Trafy Dos Pro, Trafy Tres …) all hand back a model string from
 * `getdeviceattr.cgi` — those strings differ per board / firmware version
 * and let us tell our own product line apart. The OEM serves the *same*
 * model id to every reseller, so this mapping is intentionally Trafy-side
 * (we only ship to Trafy customers, false positives across brands aren't
 * a concern per product spec).
 *
 * Mapping keys are full model strings as returned by `getdeviceattr.cgi`'s
 * `model` field — most specific signal we have. Add a new line per Trafy
 * SKU as the cams are tested. Unmapped models fall back to the chipset
 * generic name (e.g. "HiSilicon DVR").
 *
 * GeneralPlus and Allwinner protocols already resolve to their single
 * Trafy products via [ChipsetProtocol.displayName] (Trafy Uno, Trafy Dos
 * Internet) — only the HiSilicon family needs per-model discrimination.
 */
object TrafyModelIdentifier {

    /** Full `model` strings keyed to the Trafy product name. */
    private val MODEL_TO_TRAFY = mapOf(
        // Confirmed Trafy Uno Pro: G3518 board / GNR product code / HUIYING OEM /
        // softversion 1.0.1.2.20250212. Other Trafy HiSilicon cams will have
        // different model strings; add their mappings here as they're tested.
        "G3518-FV-UCARRECORDER" to "Trafy Uno Pro",
    )

    /**
     * Wi-Fi SSID prefixes (case-insensitive) keyed to the Trafy product name.
     * Used when the firmware doesn't expose a model over HTTP — the only
     * remaining product-identifying signal is the SSID. Order matters: a
     * longer / more specific prefix should appear before a shorter one if
     * they overlap. Each entry is paired with the chipset protocol it must
     * match, since two different products can share an SSID prefix
     * (HiDvr_… is HI_DVR; HisDvr-… is EEASYTECH).
     */
    private val SSID_TO_TRAFY: List<Triple<ChipsetProtocol, String, String>> = listOf(
        Triple(ChipsetProtocol.EEASYTECH, "HisDvr-", "Trafy Dos Pro"),
    )

    /**
     * Last-resort per-chipset fallback: if a chipset currently ships exactly
     * one Trafy product, return its name even when no model / SSID match
     * was found. Replace with explicit mappings as the lineup grows.
     */
    private val PROTOCOL_DEFAULT_TRAFY = mapOf(
        ChipsetProtocol.EEASYTECH to "Trafy Dos Pro",
    )

    /**
     * Returns the Trafy product name for [device], or [fallback] if no
     * mapping matches. [fallback] is typically `device.protocol.displayName`.
     */
    fun displayName(device: DeviceInfo?, fallback: String): String {
        if (device == null) return fallback
        device.model?.let { MODEL_TO_TRAFY[it]?.let { return it } }
        device.ssid?.let { ssid ->
            SSID_TO_TRAFY
                .firstOrNull { (proto, prefix, _) ->
                    proto == device.protocol && ssid.startsWith(prefix, ignoreCase = true)
                }
                ?.let { return it.third }
        }
        PROTOCOL_DEFAULT_TRAFY[device.protocol]?.let { return it }
        return fallback
    }
}
