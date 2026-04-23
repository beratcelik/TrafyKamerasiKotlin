package com.example.trafykamerasikotlin.data.vision

import android.util.Size

/**
 * Immutable snapshot of the pipeline's per-frame output. Chunk 1 only populates
 * [detections]; later chunks add plate text, track IDs, distance, etc. without
 * breaking the data-class API (add nullable fields with defaults).
 */
data class TrackedScene(
    val sourceFrameSize:    Size,
    val detections:         List<Detection>,
    val timestampNanos:     Long,
    val inferenceLatencyMs: Int,
)
