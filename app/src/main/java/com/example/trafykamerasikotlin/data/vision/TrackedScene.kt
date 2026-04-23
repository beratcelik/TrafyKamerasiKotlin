package com.example.trafykamerasikotlin.data.vision

import android.util.Size

/**
 * Immutable snapshot of the pipeline's per-frame output. Each chunk adds
 * nullable fields with defaults so existing consumers keep compiling:
 *  - Chunk 1 populated [detections].
 *  - Chunk 2 adds [plates] — may be empty when nothing is visible, null
 *    when plate detection was skipped entirely.
 */
data class TrackedScene(
    val sourceFrameSize:    Size,
    val detections:         List<Detection>,
    val timestampNanos:     Long,
    val inferenceLatencyMs: Int,
    val plates:             List<PlateDetection>? = null,
    /** Total plate-inference time across all vehicle crops, for the debug UI. */
    val plateLatencyMs:     Int? = null,
)
