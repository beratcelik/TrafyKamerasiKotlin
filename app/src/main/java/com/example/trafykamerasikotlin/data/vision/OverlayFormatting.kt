package com.example.trafykamerasikotlin.data.vision

import android.graphics.RectF
import androidx.annotation.StringRes
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection

/**
 * Shared formatting + dedupe helpers for the AI overlay. Used by the live
 * Compose overlay ([com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay])
 * and the offline burn-in renderer
 * ([com.example.trafykamerasikotlin.data.video.OverlayVideoEncoder]) so both
 * surfaces draw identical labels.
 */

/**
 * Greedy-NMS IoU threshold for overlay-side de-duplication. Two boxes with
 * IoU above this are treated as the same vehicle / plate; the more confident
 * one wins. Loose enough to catch detector NMS leaks (~0.5 IoU) but strict
 * enough not to merge two genuinely-close vehicles.
 */
const val OVERLAY_DEDUPE_IOU: Float = 0.55f

@StringRes
fun VehicleClass.labelRes(): Int = when (this) {
    VehicleClass.CAR        -> R.string.vehicle_class_car
    VehicleClass.BUS        -> R.string.vehicle_class_bus
    VehicleClass.TRUCK      -> R.string.vehicle_class_truck
    VehicleClass.MOTORCYCLE -> R.string.vehicle_class_motorcycle
    VehicleClass.BICYCLE    -> R.string.vehicle_class_bicycle
    VehicleClass.PERSON     -> R.string.vehicle_class_person
    VehicleClass.UNKNOWN    -> R.string.vehicle_class_unknown
}

/** Greedy IoU dedupe for tracked detections, sorted by descending confidence. */
fun dedupeTracksByIou(
    items: List<TrackedDetection>,
    iouThreshold: Float = OVERLAY_DEDUPE_IOU,
): List<TrackedDetection> {
    if (items.size < 2) return items
    val sorted = items.sortedByDescending { it.confidence }
    val kept = ArrayList<TrackedDetection>(sorted.size)
    outer@ for (item in sorted) {
        for (k in kept) if (rectIou(item.bbox, k.bbox) > iouThreshold) continue@outer
        kept += item
    }
    return kept
}

/** Greedy IoU dedupe for plate detections; the winner keeps its OCR / voted text. */
fun dedupePlatesByIou(
    items: List<PlateDetection>,
    iouThreshold: Float = OVERLAY_DEDUPE_IOU,
): List<PlateDetection> {
    if (items.size < 2) return items
    val sorted = items.sortedByDescending { it.confidence }
    val kept = ArrayList<PlateDetection>(sorted.size)
    outer@ for (item in sorted) {
        for (k in kept) if (rectIou(item.bbox, k.bbox) > iouThreshold) continue@outer
        kept += item
    }
    return kept
}

private fun rectIou(a: RectF, b: RectF): Float {
    val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
    val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
    val inter = ix * iy
    if (inter <= 0f) return 0f
    val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
    val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
    val un = areaA + areaB - inter
    return if (un > 0f) inter / un else 0f
}

/**
 * Insert spaces at digit↔letter boundaries so a Turkish plate like
 * "34ABC123" reads as "34 ABC 123". OCR returns no internal whitespace, so
 * we re-add it on the way to the screen.
 */
private val TURKISH_PLATE_SPACER = Regex("(?<=\\d)(?=[A-Za-z])|(?<=[A-Za-z])(?=\\d)")

fun formatTurkishPlate(raw: String): String =
    raw.uppercase().replace(TURKISH_PLATE_SPACER, " ")
