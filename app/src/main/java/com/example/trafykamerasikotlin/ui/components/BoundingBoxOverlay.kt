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
 * plate boxes and track IDs start appearing on the same canvas.
 *
 * @param scene detections in source-frame coordinates
 * @param sourceSize the dimensions of the frame the detections reference
 * @param strokeColor default red; override per-call for plates (Chunk 2 yellow), etc.
 */
@Composable
fun BoundingBoxOverlay(
    scene: TrackedScene?,
    sourceSize: Size,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color.Red,
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

        scene.detections.forEach { det ->
            val r = Rect(
                offset = Offset(offX + det.bbox.left  * scale, offY + det.bbox.top    * scale),
                size   = androidx.compose.ui.geometry.Size(
                    width  = det.bbox.width()  * scale,
                    height = det.bbox.height() * scale,
                ),
            )
            drawRect(
                color = strokeColor,
                topLeft = r.topLeft,
                size = r.size,
                style = Stroke(width = strokePx),
            )
            val label = "${det.cls.name.lowercase()}  ${"%.2f".format(det.confidence)}"
            drawContext.canvas.nativeCanvas.apply {
                val labelPx = 14.dp.toPx()
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = labelPx
                    isAntiAlias = true
                    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                }
                drawText(label, r.topLeft.x + 4f, (r.topLeft.y - 4f).coerceAtLeast(labelPx), paint)
            }
        }
    }
}
