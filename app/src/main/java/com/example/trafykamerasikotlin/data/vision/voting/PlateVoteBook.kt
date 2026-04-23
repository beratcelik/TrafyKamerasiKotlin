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
 * Not thread-safe — the live pipeline only touches it from one inference
 * coroutine.
 */
class PlateVoteBook(
    private val numSlots: Int  = 10,
    private val padChar:  Char = '_',
    private val minVotes: Int  = 3,
) {

    // trackId -> Array<SlotHistogram>
    private val perTrack = HashMap<Int, Array<HashMap<Char, Int>>>()
    private val perTrackCount = HashMap<Int, Int>()

    /**
     * Record one OCR reading. Reads shorter than [numSlots] get their
     * unused slots voted as [padChar] (so a stable short plate still wins
     * — the final [bestText] trims those pads).
     */
    fun record(trackId: Int, text: String) {
        val slots = perTrack.getOrPut(trackId) { Array(numSlots) { HashMap() } }
        for (i in 0 until numSlots) {
            val c = text.getOrElse(i) { padChar }
            slots[i].merge(c, 1) { a, _ -> a + 1 }
        }
        perTrackCount[trackId] = (perTrackCount[trackId] ?: 0) + 1
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
            var bestChar = padChar
            var bestCount = 0
            var total = 0
            for ((c, n) in slots[i]) {
                total += n
                if (n > bestCount) { bestCount = n; bestChar = c }
            }
            chars[i] = bestChar
            perSlotAgreement[i] = if (total > 0) bestCount.toFloat() / total else 0f
        }
        val text = String(chars).trimEnd(padChar)
        // Agreement: only over slots whose winner isn't pad — that's what
        // the user actually sees on screen.
        val nonPadAgreements = (0 until numSlots)
            .filter { chars[it] != padChar }
            .map { perSlotAgreement[it] }
        val agreement = if (nonPadAgreements.isEmpty()) 0f
            else nonPadAgreements.average().toFloat()
        return VotedPlateText(text = text, agreement = agreement, votes = votes)
    }

    /** Drop state for tracks no longer active — called per frame. */
    fun prune(activeTrackIds: Set<Int>) {
        val toRemove = perTrack.keys.filter { it !in activeTrackIds }
        for (id in toRemove) { perTrack.remove(id); perTrackCount.remove(id) }
    }

    fun clear() {
        perTrack.clear()
        perTrackCount.clear()
    }
}
