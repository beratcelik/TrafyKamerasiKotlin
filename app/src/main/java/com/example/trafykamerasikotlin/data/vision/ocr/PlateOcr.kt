package com.example.trafykamerasikotlin.data.vision.ocr

import android.graphics.Bitmap

/**
 * Result of a single-frame plate recognition. `meanConfidence` is the mean
 * per-slot softmax probability across non-pad characters — a rough proxy for
 * "how sure is the model that this plate text is correct". Chunk 5 uses this
 * to weight multi-frame voting.
 *
 * @param text recognized characters with trailing `_` pads stripped.
 *             Empty string if every slot decoded to the pad token.
 * @param region optional region label (e.g. "Turkey") from the region head.
 *             Null when the model doesn't have a region head.
 */
data class PlateRecognition(
    val text:            String,
    val meanConfidence:  Float,
    val perSlotConfidence: FloatArray,
    val region:          String? = null,
) {
    /** A plate is "trustworthy enough to show to the user" when all visible chars beat this. */
    fun isConfident(threshold: Float = 0.30f): Boolean =
        text.isNotEmpty() && meanConfidence >= threshold
}

/**
 * Reads a license plate from an already-cropped plate bitmap. Implementations
 * own heavy native resources — call [initialize] once, [release] on teardown.
 *
 * Serial access only.
 */
interface PlateOcr {
    suspend fun initialize()
    suspend fun recognize(plateCrop: Bitmap): PlateRecognition
    fun release()
}
