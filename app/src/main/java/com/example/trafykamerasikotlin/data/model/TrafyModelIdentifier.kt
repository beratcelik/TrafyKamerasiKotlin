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
     * Returns the Trafy product name for [device], or [fallback] if the
     * model isn't recognised. [fallback] is typically
     * `device.protocol.displayName` (e.g. "HiSilicon DVR").
     */
    fun displayName(device: DeviceInfo?, fallback: String): String {
        if (device == null) return fallback
        device.model?.let { MODEL_TO_TRAFY[it]?.let { return it } }
        return fallback
    }
}
