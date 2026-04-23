package com.example.trafykamerasikotlin.data.vision.detectors

import android.graphics.Bitmap
import com.example.trafykamerasikotlin.data.vision.PlateDetection

/**
 * Detects license plates inside a cropped vehicle region. The crop is
 * expected to come from a vehicle bounding box so the detector can run a
 * small-input model on a small region instead of the whole frame.
 *
 * Implementations return [PlateDetection]s in ORIGINAL FULL-FRAME coordinates
 * — they must rescale/translate from crop-local back to frame-global so the
 * consumer (overlay renderer, tracker) has a single coordinate system.
 *
 * Serial access only.
 */
interface PlateDetector {
    suspend fun initialize()

    /**
     * @param fullFrame the original, undecoded source frame (for optional
     *   fallback + downstream OCR cropping).
     * @param vehicleIndex index of the parent vehicle in the frame's detection
     *   list; embedded into the returned [PlateDetection] so the overlay can
     *   associate plates with their vehicles.
     * @param cropX/cropY/cropW/cropH the vehicle bounding box in full-frame
     *   coordinates. Implementations must clip to frame bounds internally.
     */
    suspend fun detectInCrop(
        fullFrame:    Bitmap,
        vehicleIndex: Int,
        cropX:        Int,
        cropY:        Int,
        cropW:        Int,
        cropH:        Int,
    ): List<PlateDetection>

    fun release()
}
