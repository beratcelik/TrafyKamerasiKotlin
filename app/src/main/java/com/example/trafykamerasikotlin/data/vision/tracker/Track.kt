package com.example.trafykamerasikotlin.data.vision.tracker

import android.graphics.RectF
import com.example.trafykamerasikotlin.data.vision.VehicleClass

/**
 * One live track maintained by [ByteTracker]. State transitions:
 *   New → (matched N frames) → Confirmed → (missed K frames) → Lost → Removed
 */
enum class TrackState { NEW, CONFIRMED, LOST, REMOVED }

/**
 * Mutable track bookkeeping. Kept internal-ish to the tracker module; the
 * public output wraps a frozen snapshot in [TrackedDetection].
 */
data class Track(
    val id:     Int,
    var cls:    VehicleClass,
    /** Last known bounding box in full-frame coordinates. */
    var bbox:   RectF,
    /** EMA'd center velocity (pixels per tracker tick). */
    var vcx:    Float,
    var vcy:    Float,
    var confidence: Float,
    var state:  TrackState,
    /** Age in frames since creation. */
    var age:    Int,
    /** Total number of successful matches (hit streak across the life). */
    var hits:   Int,
    /** Frames since last successful match. Resets to 0 on each hit. */
    var timeSinceUpdate: Int,
)

/**
 * Immutable snapshot of a confirmed track at one frame. This is what the
 * overlay + vote book consume; [Track]'s mutable state never leaves the
 * tracker.
 */
data class TrackedDetection(
    val trackId:    Int,
    val cls:        VehicleClass,
    val confidence: Float,
    val bbox:       RectF,
    /** Frames since the tracker first emitted this id — useful for "lock-in" UX. */
    val age:        Int,
)
