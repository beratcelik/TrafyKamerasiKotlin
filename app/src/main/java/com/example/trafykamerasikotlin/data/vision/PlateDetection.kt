package com.example.trafykamerasikotlin.data.vision

import android.graphics.RectF

/**
 * One plate detection. Unlike [Detection] there's no class (single-class
 * detector), but we keep the parent vehicle's index so the overlay can draw
 * a plate box inside the right vehicle and later steps (Chunk 3 OCR, Chunk 5
 * tracker) can link the plate text back to a track.
 *
 * `bbox` is in the ORIGINAL full-frame coordinate system, not the vehicle
 * crop — `NcnnPlateDetector` rescales from crop-local to frame-global before
 * returning so every coordinate in the pipeline shares the same origin.
 */
data class PlateDetection(
    val parentVehicleIndex: Int,
    val confidence:         Float,
    val bbox:               RectF,
)
