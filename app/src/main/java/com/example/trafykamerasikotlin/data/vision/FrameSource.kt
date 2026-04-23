package com.example.trafykamerasikotlin.data.vision

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * One source-frame in ARGB_8888 (Android's RGBA memory layout).
 *
 * @param timestampNanos monotonic time when the frame was captured/decoded
 * @param rotationDegrees clockwise rotation to apply for display (0 for file
 *   sources; matters when this interface is wired to live camera in later chunks)
 */
data class Frame(
    val bitmap:           Bitmap,
    val timestampNanos:   Long,
    val rotationDegrees:  Int = 0,
)

/**
 * Stream of decoded frames into the vision pipeline. Chunks 1–3 wire this to
 * picked MP4 files; Chunk 4 wires it to the live dashcam stream. The pipeline
 * is source-agnostic by design.
 */
interface FrameSource {
    val frames: Flow<Frame>
    fun start()
    fun stop()
}
