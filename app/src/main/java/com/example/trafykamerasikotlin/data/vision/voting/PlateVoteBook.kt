package com.example.trafykamerasikotlin.data.vision.voting

import com.example.trafykamerasikotlin.data.vision.VotedPlateText

/**
 * Per-track per-slot character histogram. Every time we get an OCR read on
 * a tracked plate, we record one vote per character slot (positions 0..9
 * for fast-plate-ocr's 10-slot layout). Once a track has at least
 * [minVotes] readings logged, [bestText] returns the majority character
 * per slot, stripping trailing pad-char — this is the "lock-in" text the
 * overlay shows.
 *
 * Works because per-frame OCR noise tends to be random across slots while
 * the correct characters stack up consistently. Spec's "~98% accuracy at
 * 10-frame voting" comes from this behaviour.
 *
 * Storage: per-track 2-D `IntArray` sized `[numSlots][alphabet.size]`
 * indexed by character position in the [alphabet]. Replaced an earlier
 * `HashMap<Char, Int>` per slot — the alphabet is fixed (37 chars for
 * fast-plate-ocr's `0-9A-Z_`), so an indexed array eliminates per-record
 * autoboxing and the closure allocation in `merge { a, _ -> a + 1 }`.
 *
 * Not thread-safe — the live pipeline only touches it from one inference
 * coroutine.
 */
class PlateVoteBook(
    private val alphabet: String = DEFAULT_ALPHABET,
    private val padChar:  Char   = '_',
    private val numSlots: Int    = 10,
    private val minVotes: Int    = 3,
    /**
     * If non-null, OCR readings whose stripped text doesn't match this regex
     * are dropped from voting (after a small grace window — see
     * [graceReadings]). Keeps Turkish-shaped plates from being polluted by
     * letter/digit confusion in pad slots. Pass `null` to disable filtering.
     *
     * Default: Turkish civilian plate format `[0-9]{2}[A-Z]{1,4}[0-9]{2,4}`
     * (e.g. `34ABC123`, `06KE1453`). The OCR strips internal spaces, so we
     * match against the spaceless form.
     */
    private val plateFormat: Regex? = TURKISH_PLATE_FORMAT,
    /**
     * Number of initial readings that bypass [plateFormat] filtering. Lets a
     * track build up at least some signal before we start being picky —
     * useful when the first one or two reads land mostly-but-not-quite
     * matching the format.
     */
    private val graceReadings: Int = 2,
) {

    private val alphaSize = alphabet.length
    private val padIdx    = alphabet.indexOf(padChar).let { if (it < 0) alphaSize - 1 else it }

    /** Lookup table: char-code → alphabet index. -1 if not in alphabet. */
    private val charToIdx = IntArray(128) { -1 }.also { table ->
        for (i in alphabet.indices) {
            val code = alphabet[i].code
            if (code in 0 until 128) table[code] = i
        }
    }

    // trackId -> [numSlots][alphaSize] counts.
    private val perTrack = HashMap<Int, Array<IntArray>>()
    private val perTrackCount = HashMap<Int, Int>()

    /**
     * Record one OCR reading. Reads shorter than [numSlots] get their
     * unused slots voted as [padChar] (so a stable short plate still wins
     * — the final [bestText] trims those pads).
     *
     * Format filter: after [graceReadings] readings on this track, drop any
     * subsequent reading whose stripped text doesn't match [plateFormat].
     * Returns `true` if the vote was recorded, `false` if it was rejected.
     */
    fun record(trackId: Int, text: String): Boolean {
        val priorCount = perTrackCount[trackId] ?: 0
        // After the grace window, gate by the plate-format regex (if any).
        // We strip the pad char before matching so internal pad noise from
        // OCR slot confusion doesn't kill an otherwise-clean read.
        if (plateFormat != null && priorCount >= graceReadings) {
            val stripped = text.replace(padChar.toString(), "")
            if (!plateFormat.matches(stripped)) return false
        }
        val slots = perTrack.getOrPut(trackId) { Array(numSlots) { IntArray(alphaSize) } }
        for (i in 0 until numSlots) {
            val idx = if (i < text.length) charToIdx.getOrElse(text[i].code) { -1 } else padIdx
            val target = if (idx < 0) padIdx else idx
            slots[i][target]++
        }
        perTrackCount[trackId] = priorCount + 1
        return true
    }

    /**
     * Return the current majority-vote text + vote count + agreement
     * (fraction of votes that matched the winner, averaged over
     * non-pad slots) for this track — or null if we haven't seen
     * enough votes yet.
     */
    fun bestText(trackId: Int): VotedPlateText? {
        val slots = perTrack[trackId] ?: return null
        val votes = perTrackCount[trackId] ?: 0
        if (votes < minVotes) return null

        val chars = CharArray(numSlots)
        val perSlotAgreement = FloatArray(numSlots)
        for (i in 0 until numSlots) {
            val histogram = slots[i]
            var bestIdx = padIdx
            var bestCount = 0
            var total = 0
            for (k in 0 until alphaSize) {
                val n = histogram[k]
                total += n
                if (n > bestCount) { bestCount = n; bestIdx = k }
            }
            chars[i] = alphabet[bestIdx]
            perSlotAgreement[i] = if (total > 0) bestCount.toFloat() / total else 0f
        }
        val text = String(chars).trimEnd(padChar)
        // Agreement: only over slots whose winner isn't pad — that's what
        // the user actually sees on screen.
        var sum = 0f
        var count = 0
        for (i in 0 until numSlots) {
            if (chars[i] != padChar) { sum += perSlotAgreement[i]; count++ }
        }
        val agreement = if (count == 0) 0f else sum / count
        return VotedPlateText(text = text, agreement = agreement, votes = votes)
    }

    /** Drop state for tracks no longer active — called per frame. */
    fun prune(activeTrackIds: Set<Int>) {
        // Iterate via iterator so we can remove without allocating a snapshot list.
        val it = perTrack.entries.iterator()
        while (it.hasNext()) {
            val id = it.next().key
            if (id !in activeTrackIds) {
                it.remove()
                perTrackCount.remove(id)
            }
        }
    }

    fun clear() {
        perTrack.clear()
        perTrackCount.clear()
    }

    companion object {
        /** Matches OnnxPlateOcr's default; override at construction if the model uses a different alphabet. */
        const val DEFAULT_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_"

        /**
         * Turkish civilian plate format: 2 digits + 1–4 letters + 2–4 digits,
         * matched against the OCR-stripped (no-space) form. Examples that
         * pass: `34ABC123`, `06KE1453`, `35AB12`. Examples that fail (and
         * thus get filtered out of the vote): `4ABC123` (1 leading digit),
         * `34A1234567` (digit/letter slot confusion), `34_ABC123` (still
         * has pad).
         */
        val TURKISH_PLATE_FORMAT: Regex =
            Regex("^[0-9]{2}[A-Z]{1,4}[0-9]{2,4}$")
    }
}
