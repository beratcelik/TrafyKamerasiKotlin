package com.example.trafykamerasikotlin.data.sensors

/**
 * Read-only GPS track aligned to a video clip's recording window. Multiple
 * sources can produce one of these (per-cam sidecar parsers, phone-log
 * store) and the burn-in encoder consumes them through this interface.
 *
 * Implementations should be cheap to call per frame — the encoder hits
 * [fixAt] once per output frame at 30 fps. Binary-searching a sorted
 * [LongArray] is the canonical implementation.
 */
interface GpsTrack {
    /**
     * Returns the closest sample within [maxGapMs] of [tEpochMs], or null
     * when no sample is within tolerance. The encoder uses null to mean
     * "blank this widget on this frame" — honest about coverage gaps
     * rather than silently faking values.
     */
    fun fixAt(tEpochMs: Long, maxGapMs: Long = DEFAULT_MAX_GAP_MS): GpsFix?

    /**
     * 0..1 — what fraction of the requested window has at least one fix
     * within `DEFAULT_MAX_GAP_MS` of every position. Surfaced to the user
     * in the download completion toast so they know how much of the clip
     * carries real data.
     */
    val coveragePercent: Float

    /** Human-readable tag for which source produced this track (toast copy). */
    val sourceLabel: String

    companion object {
        const val DEFAULT_MAX_GAP_MS: Long = 3_000L
    }
}

/** No coverage at all — every [fixAt] call returns null. */
object EmptyGpsTrack : GpsTrack {
    override fun fixAt(tEpochMs: Long, maxGapMs: Long): GpsFix? = null
    override val coveragePercent: Float = 0f
    override val sourceLabel: String = "yok"
}

/**
 * In-memory track backed by a sorted [LongArray] of timestamps and a
 * parallel [Array] of fixes. The canonical implementation — small enough
 * to fit a full day's GPS log (a few thousand samples) in memory, fast
 * enough to call per frame.
 */
class IndexedGpsTrack(
    private val times: LongArray,           // sorted ascending
    private val fixes: Array<GpsFix>,
    override val sourceLabel: String,
    /** Window the track was loaded for — used to compute coverage %. */
    private val windowStartMs: Long,
    private val windowEndMs:   Long,
) : GpsTrack {

    override fun fixAt(tEpochMs: Long, maxGapMs: Long): GpsFix? {
        if (times.isEmpty()) return null
        // Standard bisection: java.util.Arrays.binarySearch returns either
        // a hit index or -(insertion_point) - 1.
        val raw = java.util.Arrays.binarySearch(times, tEpochMs)
        val idx = if (raw >= 0) raw else (-raw - 1)
        // Best candidate is either the slot at idx or the one before — pick
        // whichever timestamp is closer.
        val candidates = listOfNotNull(
            (idx - 1).takeIf { it >= 0 },
            idx.takeIf { it < times.size },
        )
        val best = candidates.minByOrNull { kotlin.math.abs(times[it] - tEpochMs) } ?: return null
        val gap  = kotlin.math.abs(times[best] - tEpochMs)
        return if (gap <= maxGapMs) fixes[best] else null
    }

    override val coveragePercent: Float by lazy {
        if (windowEndMs <= windowStartMs) return@lazy 0f
        // Bucket the window into 1-second slots and count slots that have a
        // fix within DEFAULT_MAX_GAP_MS. Good enough for a user-facing %.
        val stepMs = 1_000L
        var covered = 0
        var total = 0
        var t = windowStartMs
        while (t <= windowEndMs) {
            if (fixAt(t) != null) covered++
            total++
            t += stepMs
        }
        if (total == 0) 0f else covered.toFloat() / total
    }
}
