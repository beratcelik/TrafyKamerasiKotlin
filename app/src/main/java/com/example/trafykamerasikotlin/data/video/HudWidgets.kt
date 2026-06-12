package com.example.trafykamerasikotlin.data.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny library of paint routines used by [OverlayVideoEncoder] to draw the
 * HUD chrome on each composited frame. Styled after a couple of commercial
 * dashcam apps (`BotsLab`-style timer pill, classic round speedo & compass,
 * "RAKIM" altitude capsule, per-vehicle distance pills).
 *
 * Each function is pure: no internal state, no allocation outside short-
 * lived `Paint`s. The caller hands in position/size in output-frame px and
 * the current value(s); the widget paints itself onto [canvas].
 *
 * Colors mirror the in-app palette ([ui/theme/Color.kt]) — `ColorSuccess`
 * (#30D158) for the green pills and ring, white text on a translucent
 * black disc for the dial fills.
 */
internal object HudWidgets {

    /** ColorSuccess (0xFF30D158) used as the dial ring + green pill base. */
    private const val GREEN_OPAQUE = 0xCC30D158.toInt() // ring/needle, 80% alpha
    private const val GREEN_FILL   = 0x701F8D3D.toInt() // pill backgrounds, 44% alpha
    private const val DISC_FILL    = 0x60000000.toInt() // dial fills, 38% black

    fun drawDistancePill(
        canvas: Canvas,
        billboardRect: RectF,
        distanceM: Int,
    ) {
        val text = "$distanceM M"
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()                                          // slightly translucent white
            isFakeBoldText = true
            textSize = (billboardRect.width() * 0.22f).coerceIn(12f, 20f)
        }
        val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_FILL
            style = Paint.Style.FILL
        }
        val tw = paintText.measureText(text)
        val pad = paintText.textSize * 0.5f
        val pillW = tw + pad * 2
        val pillH = paintText.textSize * 1.5f
        val cx = billboardRect.centerX()
        val left = cx - pillW / 2
        val top  = billboardRect.bottom + 3f
        val rect = RectF(left, top, left + pillW, top + pillH)
        canvas.drawRoundRect(rect, pillH / 2, pillH / 2, paintBg)
        val textY = top + pillH / 2 - (paintText.descent() + paintText.ascent()) / 2
        canvas.drawText(text, cx - tw / 2, textY, paintText)
    }

    fun drawClipTimerPill(
        canvas: Canvas,
        rightX: Float,
        topY: Float,
        timestampUs: Long,
    ) {
        val totalSec = timestampUs / 1_000_000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val text = "%02d:%02d:%02d".format(h, m, s)
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            isFakeBoldText = true
            textSize = 22f
        }
        val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_FILL
            style = Paint.Style.FILL
        }
        val tw = paintText.measureText(text)
        val pad = 12f
        val pillH = 32f
        val pillW = tw + pad * 2
        val rect = RectF(rightX - pillW, topY, rightX, topY + pillH)
        canvas.drawRoundRect(rect, pillH / 2, pillH / 2, paintBg)
        val textY = topY + pillH / 2 - (paintText.descent() + paintText.ascent()) / 2
        canvas.drawText(text, rect.left + pad, textY, paintText)
    }

    fun drawSpeedDial(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        speedKmh: Int,
    ) {
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC_FILL; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, radius, discPaint)

        val pct = (speedKmh / 180f).coerceIn(0f, 1f)
        val arcRect = RectF(cx - radius + 6f, cy - radius + 6f, cx + radius - 6f, cy + radius - 6f)
        val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x30FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, 135f, 270f, false, ringTrack)
        val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_OPAQUE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, 135f, 270f * pct, false, ringFill)

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            isFakeBoldText = true
            textSize = radius * 0.62f
            textSkewX = -0.10f
        }
        val numText = speedKmh.toString()
        val numW = numPaint.measureText(numText)
        canvas.drawText(numText, cx - numW / 2, cy + numPaint.textSize / 3, numPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99CCCCCC.toInt()
            textSize = radius * 0.20f
        }
        val labelText = "km/h"
        val labelW = labelPaint.measureText(labelText)
        canvas.drawText(labelText, cx - labelW / 2, cy + radius * 0.55f, labelPaint)
    }

    fun drawAltitudePill(
        canvas: Canvas,
        rightX: Float,
        centerY: Float,
        altitudeM: Int,
        scale: Float = 1f,
    ) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB0B7E4C6.toInt()
            isFakeBoldText = true
            textSize = 12f * scale
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            isFakeBoldText = true
            textSize = 26f * scale
            textSkewX = -0.10f
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN_FILL; style = Paint.Style.FILL }

        val labelText = "Rakım"
        val numText = "${altitudeM}M"
        val labelW = labelPaint.measureText(labelText)
        val numW   = numPaint.measureText(numText)
        val contentW = maxOf(labelW, numW)
        val pad   = 14f * scale
        val pillW = contentW + pad * 2
        val pillH = 54f * scale
        val left = rightX - pillW
        val top  = centerY - pillH / 2
        canvas.drawRoundRect(RectF(left, top, rightX, top + pillH), pillH * 0.45f, pillH * 0.45f, bgPaint)
        canvas.drawText(labelText, left + (pillW - labelW) / 2, top + 18f * scale, labelPaint)
        canvas.drawText(numText,   left + (pillW - numW)   / 2, top + 44f * scale, numPaint)
    }

    /**
     * Horizontal G-meter pill. Bar fills from the centre line toward the
     * right (green) when accelerating and toward the left (red) when
     * decelerating. Centre vertical tick stays visible so "constant speed"
     * reads cleanly. [accelG] is signed g (positive = car gaining speed).
     */
    fun drawGForceBar(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        width: Float,
        accelG: Float,
        scale: Float = 1f,
    ) {
        val height = 22f * scale
        val pad    = 4f  * scale
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DISC_FILL
            style = Paint.Style.FILL
        }
        val trackRect = RectF(
            centerX - width / 2, centerY - height / 2,
            centerX + width / 2, centerY + height / 2,
        )
        canvas.drawRoundRect(trackRect, height / 2, height / 2, track)

        // Outer ring matches the dial style for visual cohesion.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_OPAQUE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(trackRect, height / 2, height / 2, ring)

        // Fill bar — clamp to ±0.6 g for visual headroom (real values from
        // GPS deltas rarely exceed ±0.5 even with hard braking).
        val saturationG = 0.6f
        val pct = (accelG / saturationG).coerceIn(-1f, 1f)
        val maxHalf = width / 2 - pad
        val barFillRect = if (pct >= 0f) {
            RectF(centerX, centerY - height / 2 + pad, centerX + pct * maxHalf, centerY + height / 2 - pad)
        } else {
            RectF(centerX + pct * maxHalf, centerY - height / 2 + pad, centerX, centerY + height / 2 - pad)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = when {
                accelG >  0.04f -> 0xCC30D158.toInt() // green (accel)
                accelG < -0.04f -> 0xCCFF453A.toInt() // red (decel)
                else            -> 0x66FFFFFF        // near-constant: faint white
            }
        }
        canvas.drawRoundRect(barFillRect, (height - pad * 2) / 2, (height - pad * 2) / 2, fill)

        // Centre tick — vertical line down the middle of the bar.
        val centreTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
        }
        canvas.drawLine(
            centerX, centerY - height / 2 + pad,
            centerX, centerY + height / 2 - pad,
            centreTick,
        )

        // "G" label centred above (small, dimmed).
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB0FFFFFF.toInt()
            isFakeBoldText = true
            textSize = 11f * scale
        }
        val labelText = "G"
        val labelW = labelPaint.measureText(labelText)
        canvas.drawText(labelText, centerX - labelW / 2, centerY - height / 2 - 3f * scale, labelPaint)
    }

    fun drawCompassDisc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        headingDeg: Float,
    ) {
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC_FILL; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, radius, discPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_OPAQUE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(cx, cy, radius - 3f, ringPaint)

        // Needle. 0° = north (up), increases clockwise. Standard screen coords
        // have +Y pointing down, so rotate by `headingDeg - 90` to get the
        // arrow pointing the right way.
        val tipR  = radius - 12f
        val sideR = 7f
        val tipDeg  = headingDeg - 90.0
        val side1Deg = tipDeg + 130.0   // slight tail-fan
        val side2Deg = tipDeg - 130.0
        val tipX  = cx + (cos(Math.toRadians(tipDeg))   * tipR).toFloat()
        val tipY  = cy + (sin(Math.toRadians(tipDeg))   * tipR).toFloat()
        val s1x   = cx + (cos(Math.toRadians(side1Deg)) * sideR).toFloat()
        val s1y   = cy + (sin(Math.toRadians(side1Deg)) * sideR).toFloat()
        val s2x   = cx + (cos(Math.toRadians(side2Deg)) * sideR).toFloat()
        val s2y   = cy + (sin(Math.toRadians(side2Deg)) * sideR).toFloat()

        val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_OPAQUE
            style = Paint.Style.FILL
        }
        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(s1x, s1y)
        path.lineTo(cx, cy)
        path.lineTo(s2x, s2y)
        path.close()
        canvas.drawPath(path, needlePaint)

        // Yellow tail for orientation contrast (matches reference #1).
        val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD60A.toInt()
            style = Paint.Style.FILL
        }
        val tailTipDeg = tipDeg + 180.0
        val tipBx = cx + (cos(Math.toRadians(tailTipDeg)) * (tipR * 0.55f)).toFloat()
        val tipBy = cy + (sin(Math.toRadians(tailTipDeg)) * (tipR * 0.55f)).toFloat()
        val tailPath = Path()
        tailPath.moveTo(tipBx, tipBy)
        tailPath.lineTo(s1x, s1y)
        tailPath.lineTo(cx, cy)
        tailPath.lineTo(s2x, s2y)
        tailPath.close()
        canvas.drawPath(tailPath, tailPaint)

        // Cardinal letter for current heading (Turkish: K/D/G/B).
        val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            isFakeBoldText = true
            textSize = radius * 0.32f
        }
        val letter = cardinalFor(headingDeg)
        val lw = letterPaint.measureText(letter)
        canvas.drawText(letter, cx - lw / 2, cy + radius - 10f, letterPaint)
    }

    /** Turkish cardinal letter for the heading (K kuzey, D doğu, G güney, B batı). */
    private fun cardinalFor(deg: Float): String {
        val d = ((deg % 360f) + 360f) % 360f
        return when {
            d < 45f || d >= 315f -> "K"
            d < 135f             -> "D"
            d < 225f             -> "G"
            else                 -> "B"
        }
    }
}
