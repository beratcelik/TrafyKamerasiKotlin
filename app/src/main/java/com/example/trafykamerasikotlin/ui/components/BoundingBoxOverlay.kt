package com.example.trafykamerasikotlin.ui.components

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.data.vision.TrackedScene

/**
 * Compose primitive that draws bounding boxes + class+confidence labels on
 * top of whatever sits behind it. Stable API — reused across Chunks 2–6 once
 * plate OCR text and track IDs start appearing on the same canvas.
 *
 * Chunk 2: draws vehicle boxes (red) AND plate boxes (yellow) in one pass
 * so they share a single coordinate transform. The scene's `plates` list
 * is in the same original-frame coordinate space as `detections`.
 *
 * @param scene detections + plates in source-frame coordinates
 * @param sourceSize the dimensions of the frame the detections reference
 */
@Composable
fun BoundingBoxOverlay(
    scene: TrackedScene?,
    sourceSize: Size,
    modifier: Modifier = Modifier,
    vehicleColor: Color = Color.Red,
    plateColor:   Color = Color.Yellow,
) {
    if (scene == null || sourceSize.width <= 0 || sourceSize.height <= 0) return

    Canvas(modifier = modifier) {
        // Contain-fit: preserve aspect ratio, center inside the view.
        val vw = size.width
        val vh = size.height
        val sw = sourceSize.width.toFloat()
        val sh = sourceSize.height.toFloat()
        val scale = minOf(vw / sw, vh / sh)
        val drawW = sw * scale
        val drawH = sh * scale
        val offX = (vw - drawW) / 2f
        val offY = (vh - drawH) / 2f

        val strokePx = 2.dp.toPx()
        val labelPx = 14.dp.toPx()
        val plateLabelPx = 11.dp.toPx()

        // Vehicles first so plates draw on top when they overlap.
        scene.detections.forEach { det ->
            val r = Rect(
                offset = Offset(offX + det.bbox.left  * scale, offY + det.bbox.top    * scale),
                size   = androidx.compose.ui.geometry.Size(
                    width  = det.bbox.width()  * scale,
                    height = det.bbox.height() * scale,
                ),
            )
            drawRect(
                color = vehicleColor,
                topLeft = r.topLeft,
                size = r.size,
                style = Stroke(width = strokePx),
            )
            val label = "${det.cls.name.lowercase()}  ${"%.2f".format(det.confidence)}"
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = labelPx
                    isAntiAlias = true
                    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                }
                drawText(label, r.topLeft.x + 4f, (r.topLeft.y - 4f).coerceAtLeast(labelPx), paint)
            }
        }

        scene.plates?.forEach { plate ->
            val r = Rect(
                offset = Offset(offX + plate.bbox.left  * scale, offY + plate.bbox.top    * scale),
                size   = androidx.compose.ui.geometry.Size(
                    width  = plate.bbox.width()  * scale,
                    height = plate.bbox.height() * scale,
                ),
            )
            drawRect(
                color = plateColor,
                topLeft = r.topLeft,
                size = r.size,
                style = Stroke(width = strokePx),
            )
            // Only show OCR text when the recognizer is confident — the CCT-S
            // model spits out random-looking characters at ~0.03–0.08 confidence
            // on unreadable crops (uniform distribution over 37 chars is ~0.027).
            // Showing that noise looks worse than showing no text at all.
            // Chunk 5's multi-frame voting is what lets us raise this bar.
            val recog = plate.recognition
            val primary = if (recog != null && recog.isConfident(threshold = 0.30f)) {
                "${recog.text}  ${"%.2f".format(recog.meanConfidence)}"
            } else {
                "plate ${"%.2f".format(plate.confidence)}"
            }
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.YELLOW
                    textSize = plateLabelPx
                    isAntiAlias = true
                    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                }
                drawText(primary, r.topLeft.x + 2f,
                    (r.topLeft.y + r.size.height + plateLabelPx + 2f), paint)
            }
        }
    }
}
