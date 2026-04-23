package com.example.trafykamerasikotlin.data.vision

import android.graphics.RectF
import com.example.trafykamerasikotlin.data.vision.ocr.PlateRecognition

/**
 * One plate detection. Unlike [Detection] there's no class (single-class
 * detector), but we keep the parent vehicle's index so the overlay can draw
 * a plate box inside the right vehicle and later steps (Chunk 5 multi-frame
 * tracker voting) can link the plate text back to a track.
 *
 * `bbox` is in the ORIGINAL full-frame coordinate system, not the vehicle
 * crop — `NcnnPlateDetector` rescales from crop-local to frame-global before
 * returning so every coordinate in the pipeline shares the same origin.
 *
 * `recognition` is populated by Chunk 3's OCR pass. Null means OCR wasn't
 * attempted (e.g. plate too small); an empty-text recognition means OCR ran
 * but the model produced only pad characters.
 */
data class PlateDetection(
    val parentVehicleIndex: Int,
    val confidence:         Float,
    val bbox:               RectF,
    val recognition:        PlateRecognition? = null,
)
