package com.example.trafykamerasikotlin.data.vision

import android.graphics.RectF

/**
 * One detection in ORIGINAL source-frame coordinates (letterbox already undone
 * by the native side).
 */
data class Detection(
    val cls:        VehicleClass,
    val confidence: Float,
    val bbox:       RectF,
)
