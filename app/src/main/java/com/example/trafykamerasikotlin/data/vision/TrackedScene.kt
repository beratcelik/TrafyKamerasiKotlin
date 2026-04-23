package com.example.trafykamerasikotlin.data.vision

import android.util.Size
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection

/**
 * Immutable snapshot of the pipeline's per-frame output. Each chunk adds
 * nullable fields with defaults so existing consumers keep compiling:
 *  - Chunk 1 populated [detections].
 *  - Chunk 2 added [plates] — may be empty when nothing is visible, null
 *    when plate detection was skipped entirely.
 *  - Chunk 5 adds [tracks] — confirmed ByteTrack outputs with stable ids.
 *    When non-null, the overlay prefers it over [detections] so labels
 *    read "car#7 0.85" instead of anonymous "car 0.85".
 */
data class TrackedScene(
    val sourceFrameSize:    Size,
    val detections:         List<Detection>,
    val timestampNanos:     Long,
    val inferenceLatencyMs: Int,
    val plates:             List<PlateDetection>? = null,
    /** Total plate-inference time across all vehicle crops, for the debug UI. */
    val plateLatencyMs:     Int? = null,
    val tracks:             List<TrackedDetection>? = null,
)
