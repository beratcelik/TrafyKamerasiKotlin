package com.example.trafykamerasikotlin.data.vision.tracker

import android.graphics.RectF
import com.example.trafykamerasikotlin.data.vision.Detection

/**
 * A pragmatic port of ByteTrack's two-pass IoU association, tuned for
 * mid-range Android and dashcam footage. Pure Kotlin, no BLAS — the
 * "Kalman filter" is a first-order EMA on box center velocity (plenty for
 * vehicle motion between inference frames) and we pick the cheap greedy-max
 * assignment instead of Hungarian (few tracks per frame makes the
 * difference invisible).
 *
 * ## BYTE in one paragraph
 * Incoming detections are split into **high-score** (> [highConfThreshold])
 * and **low-score** (between [lowConfThreshold] and [highConfThreshold]).
 * Pass 1 associates high-score dets with existing confirmed+lost tracks via
 * IoU. Pass 2 associates **remaining unmatched tracks** with the low-score
 * detections — this is the trick that catches vehicles occluded for a frame
 * or two. Still-unmatched high-score dets start new tentative tracks;
 * tentatives that survive [minHitsToConfirm] consecutive frames become
 * Confirmed and start emitting track IDs to the overlay.
 *
 * Tracks that go [maxAgeLost] frames without a match are Removed.
 */
class ByteTracker(
    private val highConfThreshold: Float = 0.50f,
    private val lowConfThreshold:  Float = 0.10f,
    private val iouMatchThreshold: Float = 0.30f,
    private val minHitsToConfirm:  Int   = 3,
    private val maxAgeLost:        Int   = 30,
    /** EMA alpha for bbox updates — higher = trust measurement more. */
    private val emaAlpha:          Float = 0.6f,
    /** Velocity EMA — lower = smoother motion, less reactive. */
    private val velocityAlpha:     Float = 0.3f,
) {

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1

    /**
     * Advance the tracker by one frame. Returns the currently CONFIRMED tracks
     * with their latest bbox + id. New (tentative) tracks are not emitted
     * until they hit [minHitsToConfirm] — Chunk 5's vote book relies on that
     * lock-in to avoid polluting its histograms with one-off false positives.
     */
    fun update(detections: List<Detection>): List<TrackedDetection> {
        // 1. Predict every track's next position from its EMA'd velocity.
        for (t in tracks) {
            t.bbox = t.bbox.shifted(t.vcx, t.vcy)
            t.age++
            t.timeSinceUpdate++
        }

        // 2. Split detections by confidence tier.
        val hi = detections.filter { it.confidence >= highConfThreshold }
        val lo = detections.filter {
            it.confidence < highConfThreshold && it.confidence >= lowConfThreshold
        }

        // 3. Pass 1 — match confirmed + lost tracks with high-conf detections.
        val primaryTracks = tracks.filter { it.state != TrackState.REMOVED }
        val (matched1, unmatchedTracks1, unmatchedDets1) =
            greedyIouMatch(primaryTracks, hi, iouMatchThreshold)
        matched1.forEach { (t, d) -> hit(t, d) }

        // 4. Pass 2 — BYTE's rescue: unmatched tracks against low-conf dets.
        val (matched2, unmatchedTracks2, _) =
            greedyIouMatch(unmatchedTracks1, lo, iouMatchThreshold)
        matched2.forEach { (t, d) -> hit(t, d) }

        // 5. Miss + expire remaining tracks.
        for (t in unmatchedTracks2) {
            when (t.state) {
                TrackState.NEW -> t.state = TrackState.REMOVED  // lost before confirmation
                TrackState.CONFIRMED -> if (t.timeSinceUpdate >= 1) t.state = TrackState.LOST
                TrackState.LOST -> if (t.timeSinceUpdate >= maxAgeLost) t.state = TrackState.REMOVED
                TrackState.REMOVED -> {}
            }
        }
        tracks.removeAll { it.state == TrackState.REMOVED }

        // 6. Spawn new tentative tracks for unmatched high-conf detections.
        for (d in unmatchedDets1) {
            tracks.add(newTrack(d))
        }

        // 7. Emit confirmed tracks.
        return tracks
            .filter { it.state == TrackState.CONFIRMED || it.state == TrackState.LOST }
            .filter { it.timeSinceUpdate == 0 }  // only tracks that got a measurement this frame
            .map { it.toSnapshot() }
    }

    fun activeTrackIds(): Set<Int> =
        tracks.asSequence()
            .filter { it.state == TrackState.CONFIRMED || it.state == TrackState.LOST }
            .map { it.id }
            .toSet()

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun newTrack(d: Detection): Track = Track(
        id = nextTrackId++,
        cls = d.cls,
        bbox = RectF(d.bbox),
        vcx = 0f,
        vcy = 0f,
        confidence = d.confidence,
        state = TrackState.NEW,
        age = 0,
        hits = 1,
        timeSinceUpdate = 0,
    )

    /** Fold a successful detection into track state (EMA bbox, update vel). */
    private fun hit(t: Track, d: Detection) {
        val prevCx = (t.bbox.left + t.bbox.right) * 0.5f
        val prevCy = (t.bbox.top  + t.bbox.bottom) * 0.5f

        val newLeft   = emaAlpha * d.bbox.left   + (1f - emaAlpha) * t.bbox.left
        val newTop    = emaAlpha * d.bbox.top    + (1f - emaAlpha) * t.bbox.top
        val newRight  = emaAlpha * d.bbox.right  + (1f - emaAlpha) * t.bbox.right
        val newBottom = emaAlpha * d.bbox.bottom + (1f - emaAlpha) * t.bbox.bottom
        t.bbox = RectF(newLeft, newTop, newRight, newBottom)

        val newCx = (newLeft + newRight) * 0.5f
        val newCy = (newTop + newBottom) * 0.5f
        val measuredVcx = newCx - prevCx
        val measuredVcy = newCy - prevCy
        t.vcx = velocityAlpha * measuredVcx + (1f - velocityAlpha) * t.vcx
        t.vcy = velocityAlpha * measuredVcy + (1f - velocityAlpha) * t.vcy

        t.confidence = d.confidence
        t.cls = d.cls
        t.hits++
        t.timeSinceUpdate = 0
        if (t.state == TrackState.NEW && t.hits >= minHitsToConfirm) {
            t.state = TrackState.CONFIRMED
        } else if (t.state == TrackState.LOST) {
            t.state = TrackState.CONFIRMED  // reacquired after a brief miss
        }
    }

    private fun Track.toSnapshot(): TrackedDetection = TrackedDetection(
        trackId    = id,
        cls        = cls,
        confidence = confidence,
        bbox       = RectF(bbox),
        age        = age,
    )
}

private data class Matching(
    val matched:   List<Pair<Track, Detection>>,
    val unmatched: List<Track>,
    val spare:     List<Detection>,
)

/**
 * Greedy IoU matching: repeatedly pick the highest-IoU (track, det) pair
 * above the threshold, mark both used, repeat. Not Hungarian-optimal but
 * the per-frame track+det counts in a dashcam scene are tiny (< 20), so
 * the difference is noise.
 */
private fun greedyIouMatch(
    tracks: List<Track>,
    dets:   List<Detection>,
    threshold: Float,
): Matching {
    if (tracks.isEmpty() || dets.isEmpty()) return Matching(emptyList(), tracks, dets)

    val iou = Array(tracks.size) { i ->
        FloatArray(dets.size) { j -> iou(tracks[i].bbox, dets[j].bbox) }
    }
    val usedT = BooleanArray(tracks.size)
    val usedD = BooleanArray(dets.size)
    val matched = mutableListOf<Pair<Track, Detection>>()

    while (true) {
        var bestT = -1; var bestD = -1; var bestV = threshold
        for (i in tracks.indices) if (!usedT[i]) {
            for (j in dets.indices) if (!usedD[j]) {
                if (iou[i][j] > bestV) { bestV = iou[i][j]; bestT = i; bestD = j }
            }
        }
        if (bestT < 0) break
        usedT[bestT] = true
        usedD[bestD] = true
        matched += tracks[bestT] to dets[bestD]
    }
    val unmatchedT = tracks.filterIndexed { i, _ -> !usedT[i] }
    val unmatchedD = dets  .filterIndexed { j, _ -> !usedD[j] }
    return Matching(matched, unmatchedT, unmatchedD)
}

private fun iou(a: RectF, b: RectF): Float {
    val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
    val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
    val inter = ix * iy
    val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
    val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
    val un = areaA + areaB - inter
    return if (un > 0f) inter / un else 0f
}

private fun RectF.shifted(dx: Float, dy: Float) =
    RectF(left + dx, top + dy, right + dx, bottom + dy)
