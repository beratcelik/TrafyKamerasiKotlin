package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.VehicleClass
import com.example.trafykamerasikotlin.data.vision.dedupePlatesByIou
import com.example.trafykamerasikotlin.data.vision.dedupeTracksByIou
import com.example.trafykamerasikotlin.data.vision.formatTurkishPlate
import com.example.trafykamerasikotlin.data.vision.labelRes

/**
 * Draws the AI overlay (vehicle boxes, plate boxes, voted plate text) into
 * a transparent ARGB bitmap that the GL pipeline uploads as a texture and
 * blends on top of the decoded video frame.
 *
 * Re-rendered only when the [TrackedScene] reference changes — between
 * inferences (every Nth source frame) the same texture is reused for free.
 *
 * Identical labels to the live overlay
 * ([com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay]):
 * localized vehicle class names, Turkish-formatted plate text, IoU dedupe.
 */
class OverlayBitmapRenderer(context: Context, val width: Int, val height: Int) {

    /** Reusable bitmap — cleared and redrawn on each [render] call. */
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (height / 360f).coerceAtLeast(2f)  // ~6 px at 1440p
    }
    private val vehicleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = (height / 32f).coerceAtLeast(18f)  // ~45 px at 1440p
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val plateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = (height / 40f).coerceAtLeast(16f)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val classLabels: Map<VehicleClass, String> =
        VehicleClass.entries.associateWith { context.getString(it.labelRes()) }
    private val plateShortLabel: String = context.getString(R.string.plate_label_short)

    fun render(scene: TrackedScene?) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        if (scene == null) return

        val sx = width  / scene.sourceFrameSize.width.toFloat()
        val sy = height / scene.sourceFrameSize.height.toFloat()

        // Vehicle boxes (red). Prefer tracker output for stable labels.
        boxPaint.color = Color.RED
        val tracks = scene.tracks
        if (tracks != null && tracks.isNotEmpty()) {
            dedupeTracksByIou(tracks).forEach { t ->
                val x1 = t.bbox.left   * sx
                val y1 = t.bbox.top    * sy
                val x2 = t.bbox.right  * sx
                val y2 = t.bbox.bottom * sy
                canvas.drawRect(x1, y1, x2, y2, boxPaint)
                canvas.drawText(
                    classLabels[t.cls] ?: t.cls.name,
                    x1 + 4f,
                    (y1 - 6f).coerceAtLeast(vehicleTextPaint.textSize),
                    vehicleTextPaint,
                )
            }
        } else {
            scene.detections.forEach { d ->
                val x1 = d.bbox.left   * sx
                val y1 = d.bbox.top    * sy
                val x2 = d.bbox.right  * sx
                val y2 = d.bbox.bottom * sy
                canvas.drawRect(x1, y1, x2, y2, boxPaint)
                canvas.drawText(
                    classLabels[d.cls] ?: d.cls.name,
                    x1 + 4f,
                    (y1 - 6f).coerceAtLeast(vehicleTextPaint.textSize),
                    vehicleTextPaint,
                )
            }
        }

        // Plate boxes (yellow) + voted text.
        boxPaint.color = Color.YELLOW
        scene.plates?.let { dedupePlatesByIou(it) }?.forEach { p ->
            val x1 = p.bbox.left   * sx
            val y1 = p.bbox.top    * sy
            val x2 = p.bbox.right  * sx
            val y2 = p.bbox.bottom * sy
            canvas.drawRect(x1, y1, x2, y2, boxPaint)

            val voted = p.votedText
            val recog = p.recognition
            val rawPlateText: String = when {
                voted != null -> voted.text
                recog != null && recog.isConfident(threshold = 0.30f) -> recog.text
                else -> ""
            }
            val label = if (rawPlateText.isNotEmpty()) {
                formatTurkishPlate(rawPlateText)
            } else {
                plateShortLabel
            }
            canvas.drawText(
                label,
                x1 + 2f,
                y2 + plateTextPaint.textSize + 2f,
                plateTextPaint,
            )
        }
    }

    fun release() { bitmap.recycle() }
}
