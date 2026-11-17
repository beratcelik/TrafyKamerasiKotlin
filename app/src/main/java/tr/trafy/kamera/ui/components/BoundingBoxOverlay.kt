package tr.trafy.kamera.ui.components

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import tr.trafy.kamera.data.sensors.LiveSensorData
import tr.trafy.kamera.data.sensors.rememberLiveSensorData
import tr.trafy.kamera.data.video.HudWidgets
import tr.trafy.kamera.data.vision.OVERLAY_DEDUPE_IOU
import tr.trafy.kamera.data.vision.PlateDetection
import tr.trafy.kamera.data.vision.TrackedScene
import tr.trafy.kamera.data.vision.dedupePlatesByIou
import tr.trafy.kamera.data.vision.dedupeTracksByIou
import kotlin.math.tan

/**
 * Live AI overlay: paints each tracked vehicle as a translucent green slab
 * with the estimated distance-to-vehicle written over it, an enlarged crop
 * of its plate (real pixels, not OCR text) pinned above it, plus the four HUD
 * chrome widgets (clip-clock, compass, speed dial, altitude pill). Visually
 * mirrors the offline burn-in produced by `OverlayVideoEncoder` so a clip
 * recorded live and re-encoded later look identical.
 *
 * Coordinate spaces:
 *  - `scene.detections / tracks / plates` bboxes are in source-frame px
 *    (the bitmap the AI ran on).
 *  - The Canvas paints inside whatever box this Composable is sized to.
 *  - A contain-fit transform centres the source rect inside the Canvas,
 *    preserving aspect ratio. `offX/offY/scale` carry that transform.
 *
 * HUD chrome ignores the source-frame transform and pins to the four
 * Canvas corners so it stays in the same on-screen spot regardless of the
 * video's letterboxing.
 *
 * @param scene latest AI scene; nothing draws when null
 * @param sourceSize the dimensions of the frame the detections reference
 * @param showHud false hides the corner widgets (used when caller already
 *                stacks a separate chrome layer)
 * @param sensorData snapshot to drive the HUD widgets. Null means "live
 *                  phone state via [rememberLiveSensorData]" — that's the
 *                  default for the cam/live overlay paths. Sidecar-driven
 *                  playback supplies a non-null recorded value derived
 *                  from the GPS log so the HUD reads what was true at
 *                  recording time. Null is resolved AFTER the early-return
 *                  below — the LiveSensorProvider doesn't start when the
 *                  overlay has nothing to draw.
 */
@Composable
fun BoundingBoxOverlay(
    scene: TrackedScene?,
    sourceSize: Size,
    modifier: Modifier = Modifier,
    showHud: Boolean = true,
    sensorData: LiveSensorData? = null,
) {
    if (scene == null || sourceSize.width <= 0 || sourceSize.height <= 0) return

    // After the early-return so we don't spin up the GPS listener +
    // rotation-vector subscription for a Composable that has nothing to
    // draw. `rememberLiveSensorData` is called UNCONDITIONALLY here so the
    // Compose call graph stays deterministic across recompositions; we
    // just ignore the value when the caller supplied a recorded fix.
    val liveSensor by rememberLiveSensorData()
    val effectiveSensor = sensorData ?: liveSensor

    Canvas(modifier = modifier) {
        // Contain-fit: preserve aspect ratio of the source frame, centre it
        // inside the Canvas. Vehicle / plate boxes use this transform.
        val vw = size.width
        val vh = size.height
        val sw = sourceSize.width.toFloat()
        val sh = sourceSize.height.toFloat()
        val scale = minOf(vw / sw, vh / sh)
        val drawW = sw * scale
        val drawH = sh * scale
        val offX = (vw - drawW) / 2f
        val offY = (vh - drawH) / 2f

        // ── Vehicle slabs ──────────────────────────────────────────────────
        // Translucent green fills (no stroke, no class label). We prefer the
        // tracker's confirmed list, deduped on heavy overlap so NMS leaks
        // don't double-draw the same car.
        val tracks  = scene.tracks?.let { dedupeTracksByIou(it, OVERLAY_DEDUPE_IOU) }
        val plates  = scene.plates?.let  { dedupePlatesByIou(it, OVERLAY_DEDUPE_IOU) }

        // Pre-index plates by parent for distance lookup. Track id is the
        // stable key; raw detection index is the fallback when no tracker.
        val plateByTrackId = HashMap<Int, PlateDetection>(plates?.size ?: 0)
        val plateByDetIdx  = HashMap<Int, PlateDetection>(plates?.size ?: 0)
        plates?.forEach { p ->
            p.parentTrackId?.let { plateByTrackId[it] = p }
            if (p.parentVehicleIndex >= 0) plateByDetIdx[p.parentVehicleIndex] = p
        }

        val focalPx = sw / (2f * tan(Math.toRadians(ASSUMED_DASHCAM_FOV_DEG / 2.0)).toFloat())

        if (tracks != null && tracks.isNotEmpty()) {
            tracks.forEach { td ->
                val r = boxRect(td.bbox, offX, offY, scale)
                drawRect(color = VehicleSlabColor, topLeft = r.topLeft, size = r.size)

                val plate = plateByTrackId[td.trackId]
                val distM = plate?.let { distanceMeters(it, focalPx) }
                if (distM != null) drawDistanceCentered(r, distM)
            }
        } else {
            scene.detections.forEachIndexed { idx, det ->
                val r = boxRect(det.bbox, offX, offY, scale)
                drawRect(color = VehicleSlabColor, topLeft = r.topLeft, size = r.size)

                val plate = plateByDetIdx[idx]
                val distM = plate?.let { distanceMeters(it, focalPx) }
                if (distM != null) drawDistanceCentered(r, distM)
            }
        }

        // ── Plate billboards ───────────────────────────────────────────────
        // The real plate pixels, enlarged and pinned above each vehicle —
        // same as the offline "AI katmanı" burn-in. Drawn after every slab
        // so a nearby car's slab can't paint over another car's plate.
        // Replaces the old OCR text label: the viewer reads the plate
        // themselves instead of trusting a possibly-wrong OCR guess.
        val crops = scene.plateCrops
        if (!crops.isNullOrEmpty() && tracks != null) {
            val canvas = drawContext.canvas.nativeCanvas
            tracks.forEach { td ->
                val crop = crops[td.trackId] ?: return@forEach
                drawPlateBillboard(canvas, crop, boxRect(td.bbox, offX, offY, scale), offX, drawW, offY)
            }
        }

        // ── HUD chrome (corners) ────────────────────────────────────────────
        if (showHud) {
            // Size off the smaller Canvas dimension, NOT density. The
            // earlier density-floor of 56 dp blew up to 168 px on 3× phones
            // and ate half the portrait-mode live canvas. Plain proportional
            // sizing with raw-px clamps keeps widgets readable on small
            // canvases and prevents them from dominating big ones.
            val margin = (minOf(vw, vh) * 0.05f).coerceIn(20f, 56f)
            val dialR  = (minOf(vw, vh) * 0.12f).coerceIn(68f, 180f)
            // Altitude pill internal sizes scale with dial radius so it
            // visually matches the dials next to it.
            val pillScale = (dialR / 48f).coerceIn(1.5f, 3.8f)
            val canvas = drawContext.canvas.nativeCanvas

            // Top-left, smaller than the speed dial: bottom-left is where
            // the cam burns in its resolution + date/time stamp, and the
            // compass used to sit right on top of it.
            effectiveSensor.headingDeg?.let { headingDeg ->
                val compassR = (dialR * COMPASS_TO_DIAL_FRAC).coerceAtLeast(COMPASS_MIN_R_PX)
                HudWidgets.drawCompassDisc(
                    canvas     = canvas,
                    cx         = margin + compassR,
                    cy         = margin + compassR,
                    radius     = compassR,
                    headingDeg = headingDeg,
                )
            }
            effectiveSensor.speedKmh?.let { speedKmh ->
                HudWidgets.drawSpeedDial(
                    canvas   = canvas,
                    cx       = vw - margin - dialR,
                    cy       = vh - margin - dialR,
                    radius   = dialR,
                    speedKmh = speedKmh,
                )
            }
            effectiveSensor.altitudeM?.let { altitudeM ->
                HudWidgets.drawAltitudePill(
                    canvas    = canvas,
                    rightX    = vw - margin - dialR * 2f - 10f,
                    centerY   = vh - margin - dialR,
                    altitudeM = altitudeM,
                    scale     = pillScale,
                )
            }
            // Top-right: G-meter bar. Positioned near the speed dial since
            // the two concepts (speed + change-in-speed) read together.
            effectiveSensor.accelerationG?.let { accelG ->
                val gWidth  = dialR * 2.4f
                val gCenter = vw - margin - gWidth / 2
                val gY      = margin + 18f * pillScale
                HudWidgets.drawGForceBar(
                    canvas  = canvas,
                    centerX = gCenter,
                    centerY = gY,
                    width   = gWidth,
                    accelG  = accelG,
                    scale   = pillScale,
                )
            }
        }
    }
}

/** Source-frame bbox → on-screen rect using the contain-fit transform. */
private fun boxRect(bbox: android.graphics.RectF, offX: Float, offY: Float, scale: Float): Rect = Rect(
    offset = Offset(offX + bbox.left * scale, offY + bbox.top * scale),
    size   = androidx.compose.ui.geometry.Size(
        width  = bbox.width()  * scale,
        height = bbox.height() * scale,
    ),
)

/**
 * Estimated metres-to-vehicle from a plate detection. Uses the cam's plate
 * width as a known ruler — Turkish standard plate is 520 mm wide — and an
 * assumed 70° horizontal FOV (we don't know the dashcam's real focal
 * length). ±20% but plenty good for a HUD readout.
 */
private fun distanceMeters(plate: PlateDetection, focalPx: Float): Int? {
    val px = plate.bbox.width()
    if (px < DISTANCE_MIN_PLATE_PX) return null
    val m = (PLATE_PHYSICAL_WIDTH_MM * focalPx / px / 1000f).toInt()
    return if (m in 1..DISTANCE_MAX_M) m else null
}

/**
 * Draws [crop] enlarged above [slab] with the yellow/black double border
 * used by `OverlayVideoEncoder.drawBillboard`. Width follows the vehicle's
 * on-screen width with floor/ceiling relative to the visible video area
 * (not density) so it reads the same in portrait and full-screen landscape.
 * Clamped inside the video area horizontally and to its top edge.
 */
private fun drawPlateBillboard(
    canvas: android.graphics.Canvas,
    crop: android.graphics.Bitmap,
    slab: Rect,
    videoLeft: Float,
    videoWidth: Float,
    videoTop: Float,
) {
    if (crop.isRecycled || crop.width < 4 || crop.height < 4) return
    val targetW = (slab.width * BILLBOARD_VEHICLE_WIDTH_FRAC)
        .coerceAtLeast(videoWidth * BILLBOARD_MIN_VIDEO_FRAC)
        .coerceAtMost(videoWidth * BILLBOARD_MAX_VIDEO_FRAC)
    val targetH = targetW * crop.height / crop.width
    var left = slab.left + slab.width / 2f - targetW / 2f
    left = left.coerceAtMost(videoLeft + videoWidth - targetW).coerceAtLeast(videoLeft)
    val top = (slab.top - targetH - BILLBOARD_GAP_PX).coerceAtLeast(videoTop)
    val dst = android.graphics.RectF(left, top, left + targetW, top + targetH)

    canvas.drawBitmap(crop, null, dst, BillboardBitmapPaint)
    BillboardBorderPaint.color = android.graphics.Color.BLACK
    BillboardBorderPaint.strokeWidth = 4f
    canvas.drawRect(dst, BillboardBorderPaint)
    BillboardBorderPaint.color = android.graphics.Color.YELLOW
    BillboardBorderPaint.strokeWidth = 2.5f
    canvas.drawRect(dst, BillboardBorderPaint)
}

// Bilinear filtering keeps the upscaled plate smooth instead of blocky.
private val BillboardBitmapPaint = android.graphics.Paint(
    android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
)
private val BillboardBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE
}

/** Paints `<N>` over `M` centred in [boxOut] — matches the burn-in style. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDistanceCentered(boxOut: Rect, distM: Int) {
    val numSize = (minOf(boxOut.width, boxOut.height) * 0.32f).coerceIn(28f, 96f)
    val cx = boxOut.left + boxOut.width  / 2f
    val cy = boxOut.top  + boxOut.height / 2f
    val numPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF0FFFFFF.toInt()
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        textSize = numSize
    }
    val canvas = drawContext.canvas.nativeCanvas
    val numText = distM.toString()
    val numW = numPaint.measureText(numText)
    val baselineY = cy + numSize * 0.10f
    canvas.drawText(numText, cx - numW / 2, baselineY, numPaint)
    val unitSize = numSize * 0.38f
    numPaint.textSize = unitSize
    val unitW = numPaint.measureText("M")
    canvas.drawText("M", cx - unitW / 2, baselineY + unitSize * 1.1f, numPaint)
}

/** Vehicle slab fill — matches `OverlayVideoEncoder.VEHICLE_FILL_ARGB`. */
private val VehicleSlabColor = Color(0x6633D158)

private const val ASSUMED_DASHCAM_FOV_DEG = 70.0
private const val PLATE_PHYSICAL_WIDTH_MM = 520f
private const val DISTANCE_MIN_PLATE_PX   = 10f
private const val DISTANCE_MAX_M          = 80

// Billboard sizing. Somewhat larger than the burn-in's (0.35 / 160 px on a
// 720p frame) because a phone screen is smaller than the exported video.
private const val BILLBOARD_VEHICLE_WIDTH_FRAC = 0.45f
private const val BILLBOARD_MIN_VIDEO_FRAC     = 0.20f
private const val BILLBOARD_MAX_VIDEO_FRAC     = 0.40f
private const val BILLBOARD_GAP_PX             = 8f

// Compass is a secondary readout — about half the speed dial's size.
// Floor keeps the fixed-px needle (HudWidgets.drawCompassDisc) legible.
private const val COMPASS_TO_DIAL_FRAC = 0.55f
private const val COMPASS_MIN_R_PX     = 32f
