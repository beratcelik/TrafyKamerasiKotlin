package com.example.trafykamerasikotlin.data.video

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sin

/**
 * Cheap, deliberately-rough ego-state estimator for the HUD overlay burned
 * into Video AI İşleme output. We don't have access to the original drive's
 * GPS at offline-processing time, so speed/altitude/compass are *fake but
 * plausible* values that animate with the footage — the user opted into
 * this trade-off so the burn-in matches commercial-dashcam aesthetics.
 *
 * **Speed**: scaled motion magnitude in the road strip (bottom half of the
 * frame, downscaled 8×). Higher pixel deltas → higher km/h.
 * **Altitude**: a per-clip random base in [80, 250] m with a slow sine drift.
 * **Compass**: a per-clip random base heading with drift biased by the
 * horizontal direction of optical-flow.
 *
 * All numbers are *cached* — calling [update] on a skipped frame returns
 * the last computed values without re-reading the bitmap, matching the
 * existing inferenceEveryN cadence in OfflineVideoProcessor.
 */
class HudEgoEstimator(
    seed: Long,
) {

    data class EgoState(val speedKmh: Int, val altitudeM: Int, val compassDeg: Float)

    private val rng = java.util.Random(seed)
    private val baseAltitudeM: Int   = 80 + rng.nextInt(170)            // 80..250
    private val baseCompassDeg: Float = rng.nextFloat() * 360f

    /** Downscaled grayscale of the bottom-half "road" strip from the previous frame. */
    private var prevStrip: IntArray? = null
    private var prevStripW = 0
    private var prevStripH = 0

    private var emaSadPerPx     = 0f
    private var emaHorizontalBias = 0f
    private var compassDriftDeg   = 0f

    @Volatile private var cached = EgoState(0, baseAltitudeM, baseCompassDeg)

    /**
     * Read the current frame, update internal smoothing, return the latest
     * synthetic ego state. Safe to call on every frame; cheap enough (~ms).
     * If [src] is too small to sample we return the cached state without
     * touching internal buffers.
     */
    fun update(src: Bitmap, frameIndex: Long): EgoState {
        val stripW = (src.width / DOWNSCALE).coerceAtLeast(4)
        val stripH = ((src.height / 2) / DOWNSCALE).coerceAtLeast(4)
        if (stripW < 4 || stripH < 4) return cached

        val curr = sampleGrayBottomStrip(src, stripW, stripH)

        val prev = prevStrip
        if (prev != null && prevStripW == stripW && prevStripH == stripH) {
            // SAD across the whole strip — proxy for ego-speed.
            var sad = 0L
            for (i in curr.indices) sad += abs(curr[i] - prev[i])
            val sadPerPx = sad.toFloat() / curr.size

            // Horizontal-bias term: SAD of `curr` vs `prev shifted right` minus
            // vs `prev shifted left`. If features moved right on screen we're
            // panning left, and vice-versa. Used only for the synthetic
            // compass — not displayed numerically.
            var biasSum = 0L
            for (y in 0 until stripH) {
                val rowOff = y * stripW
                for (x in 1 until stripW - 1) {
                    biasSum += abs(curr[rowOff + x] - prev[rowOff + x + 1]) -
                               abs(curr[rowOff + x] - prev[rowOff + x - 1])
                }
            }
            val horizBias = biasSum.toFloat() / (stripW * stripH).coerceAtLeast(1)

            emaSadPerPx       = lerp(emaSadPerPx,       sadPerPx,  SPEED_EMA_ALPHA)
            emaHorizontalBias = lerp(emaHorizontalBias, horizBias, COMPASS_EMA_ALPHA)

            val speed   = (emaSadPerPx * SAD_TO_KMH).coerceIn(0f, 180f).toInt()
            compassDriftDeg += (emaHorizontalBias * COMPASS_BIAS_TO_DEG)
                .coerceIn(-COMPASS_DRIFT_CAP_DEG, COMPASS_DRIFT_CAP_DEG)
            val compass = wrap360(baseCompassDeg + compassDriftDeg)
            val altitude = (baseAltitudeM + 18f * sin(frameIndex / 90.0)).toInt()

            cached = EgoState(speed, altitude, compass)
        } else {
            // First frame — no delta yet. Seed altitude only.
            val altitude = (baseAltitudeM + 18f * sin(frameIndex / 90.0)).toInt()
            cached = cached.copy(altitudeM = altitude, compassDeg = baseCompassDeg)
        }

        prevStrip  = curr
        prevStripW = stripW
        prevStripH = stripH
        return cached
    }

    /**
     * Reads the bottom half of [src] downscaled by [DOWNSCALE]× into a
     * grayscale IntArray. Uses one [Bitmap.getPixels] call to pull the
     * source region in bulk, then sub-samples in Kotlin — faster than
     * per-pixel `getPixel` and avoids allocating a scaled Bitmap.
     */
    private fun sampleGrayBottomStrip(src: Bitmap, stripW: Int, stripH: Int): IntArray {
        val bottomY = src.height / 2
        val bottomH = src.height - bottomY
        val pixels = IntArray(src.width * bottomH)
        src.getPixels(pixels, 0, src.width, 0, bottomY, src.width, bottomH)
        val out = IntArray(stripW * stripH)
        for (y in 0 until stripH) {
            val srcY = (y * DOWNSCALE).coerceAtMost(bottomH - 1)
            val rowOff = srcY * src.width
            val outOff = y * stripW
            for (x in 0 until stripW) {
                val srcX = (x * DOWNSCALE).coerceAtMost(src.width - 1)
                val p = pixels[rowOff + srcX]
                val r = (p shr 16) and 0xFF
                val g = (p shr  8) and 0xFF
                val b =  p         and 0xFF
                out[outOff + x] = (r * 30 + g * 59 + b * 11) / 100   // luma approx
            }
        }
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun wrap360(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    companion object {
        private const val DOWNSCALE             = 8
        private const val SPEED_EMA_ALPHA       = 0.20f
        private const val COMPASS_EMA_ALPHA     = 0.10f
        /** SAD/pixel → km/h. Tuned empirically — bump up if footage feels too slow. */
        private const val SAD_TO_KMH            = 2.5f
        /** Multiplier on per-frame horizontal flow bias when accumulating compass drift. */
        private const val COMPASS_BIAS_TO_DEG   = 0.02f
        /** Hard cap on compass drift per frame so the needle doesn't whip. */
        private const val COMPASS_DRIFT_CAP_DEG = 0.6f
    }
}
