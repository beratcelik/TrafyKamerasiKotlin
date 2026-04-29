package com.example.trafykamerasikotlin.ui.components

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.vision.OVERLAY_DEDUPE_IOU
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.VehicleClass
import com.example.trafykamerasikotlin.data.vision.dedupePlatesByIou
import com.example.trafykamerasikotlin.data.vision.dedupeTracksByIou
import com.example.trafykamerasikotlin.data.vision.formatTurkishPlate

/**
 * Compose primitive that draws bounding boxes + class+confidence labels on
 * top of whatever sits behind it.
 *
 * Vehicle boxes (red) and plate boxes (yellow) share a single coordinate
 * transform; the scene's `plates` list is in the same original-frame
 * coordinate space as `detections`. Boxes update each time a fresh
 * [TrackedScene] arrives from inference (~10 Hz at the default
 * `inferenceEveryN = 3` cadence). User-visible labels:
 *   - Vehicles: localized class name only ("Otomobil", "Kamyon", …).
 *   - Plates: voted/recognized text formatted Turkish-style ("34 ABC 123"),
 *     or the localized "plate" placeholder when OCR hasn't returned text.
 *
 * Heavily-overlapping detections (vehicles or plates) are deduped at draw
 * time so the same vehicle isn't labeled twice when the YOLO NMS leaks a
 * near-duplicate or the tracker briefly carries two ids for one car.
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

    // Localized class labels — resolved up-front in the @Composable scope
    // so the Canvas draw lambda (which can't call stringResource directly)
    // can read them. Stable across recomposes via remember.
    val carLabel        = stringResource(R.string.vehicle_class_car)
    val busLabel        = stringResource(R.string.vehicle_class_bus)
    val truckLabel      = stringResource(R.string.vehicle_class_truck)
    val motorcycleLabel = stringResource(R.string.vehicle_class_motorcycle)
    val bicycleLabel    = stringResource(R.string.vehicle_class_bicycle)
    val personLabel     = stringResource(R.string.vehicle_class_person)
    val unknownLabel    = stringResource(R.string.vehicle_class_unknown)
    val plateShortLabel = stringResource(R.string.plate_label_short)
    val labelFor: (VehicleClass) -> String = remember(
        carLabel, busLabel, truckLabel, motorcycleLabel, bicycleLabel, personLabel, unknownLabel,
    ) {
        { cls ->
            when (cls) {
                VehicleClass.CAR        -> carLabel
                VehicleClass.BUS        -> busLabel
                VehicleClass.TRUCK      -> truckLabel
                VehicleClass.MOTORCYCLE -> motorcycleLabel
                VehicleClass.BICYCLE    -> bicycleLabel
                VehicleClass.PERSON     -> personLabel
                VehicleClass.UNKNOWN    -> unknownLabel
            }
        }
    }

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

        // Vehicles first so plates draw on top when they overlap. Prefer the
        // tracker's output when present so labels reflect confirmed tracks.
        // De-duplicate heavily-overlapping tracks before drawing — when the
        // detector emits two near-identical boxes for one vehicle (NMS
        // sometimes leaks through) the tracker assigns each its own id, and
        // we'd draw both. With the trackId hidden the labels collapse to
        // identical text a few pixels apart, looking like a "duplicate".
        val trackRects = scene.tracks?.let { dedupeTracksByIou(it, OVERLAY_DEDUPE_IOU) }
        if (trackRects != null && trackRects.isNotEmpty()) {
            trackRects.forEach { td ->
                val r = Rect(
                    offset = Offset(offX + td.bbox.left * scale, offY + td.bbox.top * scale),
                    size   = androidx.compose.ui.geometry.Size(
                        width  = td.bbox.width()  * scale,
                        height = td.bbox.height() * scale,
                    ),
                )
                drawRect(color = vehicleColor, topLeft = r.topLeft, size = r.size,
                         style = Stroke(width = strokePx))
                val label = labelFor(td.cls)
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
        } else {
            // Pre-tracker fallback (raw detections).
            scene.detections.forEach { det ->
                val r = Rect(
                    offset = Offset(offX + det.bbox.left * scale, offY + det.bbox.top * scale),
                    size   = androidx.compose.ui.geometry.Size(
                        width  = det.bbox.width()  * scale,
                        height = det.bbox.height() * scale,
                    ),
                )
                drawRect(color = vehicleColor, topLeft = r.topLeft, size = r.size,
                         style = Stroke(width = strokePx))
                val label = labelFor(det.cls)
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
        }

        // Dedupe plates — heavy overlap → keep the most-confident box (and
        // its OCR/voted text), drop the others.
        val dedupedPlates = scene.plates?.let { dedupePlatesByIou(it, OVERLAY_DEDUPE_IOU) }

        dedupedPlates?.forEach { plate ->
            val r = Rect(
                offset = Offset(offX + plate.bbox.left * scale, offY + plate.bbox.top * scale),
                size = androidx.compose.ui.geometry.Size(
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
            // Plate text only — no vote count, no confidence numbers.
            // Voted text is the multi-frame consensus and is preferred when
            // available; otherwise fall back to a confident single-frame
            // OCR read; otherwise show the localized "plate" placeholder.
            val voted = plate.votedText
            val recog = plate.recognition
            val rawPlateText: String = when {
                voted != null -> voted.text
                recog != null && recog.isConfident(threshold = 0.30f) -> recog.text
                else -> ""
            }
            val primary = if (rawPlateText.isNotEmpty()) {
                formatTurkishPlate(rawPlateText)
            } else {
                plateShortLabel
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

