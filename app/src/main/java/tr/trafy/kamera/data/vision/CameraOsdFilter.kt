package tr.trafy.kamera.data.vision

/**
 * Rejects "plates" that are really the dashcam's own burned-in text — the
 * resolution / date / time stamp (e.g. `4K+1080P 2026/11/17 16:49:58`) that
 * Trafy firmwares always paint in the bottom-left corner. The plate model
 * happily fires on that white text, and the overlay then enlarges it as a
 * billboard (user report: "kamera yazısını plaka sanıyor").
 *
 * We filter by position, not by content, so firmware font / format changes
 * don't matter. The zone is deliberately limited to the bottom-left: a
 * windshield-mounted dashcam sees its own hood in the bottom strip, so a
 * real plate essentially never has its centre there — and one that did
 * would be cut off by the frame edge and unreadable anyway.
 *
 * Floats only (no RectF) so it's unit-testable on the plain JVM.
 */
object CameraOsdFilter {

    /**
     * Bottom strip, as a fraction of frame height. Measured on a HiSilicon
     * G3518 live frame: the `4K+1080P` line sits at 81–87% of the height and
     * the date/time line at 91–97%, so the zone starts at 78%.
     */
    const val BOTTOM_BAND_FRAC = 0.22f

    /**
     * Left part of the frame the stamp occupies. Measured: the date/time
     * line ends at ~35% of the width, `4K+1080P` at ~21%. Kept tight because
     * the strip above is tall — cars in the middle/right lanes stay untouched.
     */
    const val LEFT_ZONE_FRAC = 0.40f

    /** True when a plate candidate is centred inside the bottom-left OSD zone. */
    fun isLikelyOsd(
        left: Float, top: Float, right: Float, bottom: Float,
        frameWidth: Int, frameHeight: Int,
    ): Boolean {
        if (frameWidth <= 0 || frameHeight <= 0) return false
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return centerY > frameHeight * (1f - BOTTOM_BAND_FRAC) &&
               centerX < frameWidth * LEFT_ZONE_FRAC
    }
}
