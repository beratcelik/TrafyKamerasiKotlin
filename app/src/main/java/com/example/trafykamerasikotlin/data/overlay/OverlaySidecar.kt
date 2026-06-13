package com.example.trafykamerasikotlin.data.overlay

import android.graphics.RectF
import android.util.Size
import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.VehicleClass
import com.example.trafykamerasikotlin.data.vision.VotedPlateText
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection
import java.util.Locale

/**
 * One sidecar entry — the minimum a [com.example.trafykamerasikotlin.ui.components.BoundingBoxOverlay]
 * needs to redraw what a single inference frame produced:
 *  - playback timestamp keyed in microseconds (matches `presentationTimeUs` so
 *    the player's `currentPosition` (ms) maps cleanly with `* 1000`).
 *  - the source frame size the bboxes are relative to (for the contain-fit
 *    transform).
 *  - one row per confirmed track + one row per plate, both in source-frame
 *    pixels.
 *
 * Per-row fields are the strict subset the overlay actually reads:
 *  - tracks: id + class + bbox (vehicle slabs + distance lookup join key).
 *  - plates: parent track id + bbox + already-locked voted text (we resolve
 *    each track's FINAL voted text at scan end so plate billboards appear
 *    locked from playback frame 0 — the same UX win the old Pass 1 buys,
 *    without a second decode pass).
 *
 * The renderer's [TrackedScene.detections] fallback (used when `tracks` is
 * null) is unreachable in our scan because the pipeline always populates
 * tracks, so we don't bother serialising the bare detection list.
 */
data class SidecarEntry(
    val presentationTimeUs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val tracks: List<TrackRow>,
    val plates: List<PlateRow>,
) {
    data class TrackRow(
        val trackId: Int,
        val cls: VehicleClass,
        val bbox: RectF,
    )

    data class PlateRow(
        val parentTrackId: Int,
        val bbox: RectF,
        /** Final-locked text per track at scan end (post-vote), or null when nothing locked. */
        val text: String?,
        val votes: Int,
        val agreement: Float,
    )

    /**
     * Reconstruct a [TrackedScene] the overlay can render. Mirrors the shape
     * `LiveVisionPipeline.processFrame` produces, minus debug fields the
     * overlay never reads. `timestampNanos` is derived from the PTS so
     * scenes are still in chronological order if any downstream code sorts.
     */
    fun toTrackedScene(): TrackedScene {
        val sceneTracks: List<TrackedDetection> = tracks.map { t ->
            TrackedDetection(
                trackId    = t.trackId,
                cls        = t.cls,
                confidence = 1f,             // the overlay doesn't read this
                bbox       = RectF(t.bbox),  // defensive copy
                age        = 0,
            )
        }
        val scenePlates: List<PlateDetection> = plates.map { p ->
            val voted = if (p.text != null && p.text.isNotEmpty())
                VotedPlateText(text = p.text, agreement = p.agreement, votes = p.votes)
            else null
            PlateDetection(
                parentVehicleIndex = -1,
                confidence         = 1f,
                bbox               = RectF(p.bbox),
                recognition        = null,
                parentTrackId      = p.parentTrackId,
                votedText          = voted,
            )
        }
        return TrackedScene(
            sourceFrameSize    = Size(sourceWidth, sourceHeight),
            detections         = emptyList(),     // tracks branch wins in renderer
            timestampNanos     = presentationTimeUs * 1_000L,
            inferenceLatencyMs = 0,
            plates             = scenePlates,
            plateLatencyMs     = null,
            tracks             = sceneTracks,
        )
    }

    /**
     * Hand-rolled NDJSON encode. Mirrors the style of
     * [com.example.trafykamerasikotlin.data.sensors.GpsLogger]'s
     * `toNdjsonPhone` so the codebase has a consistent "tiny-JSON" pattern
     * without pulling kotlinx.serialization for one nested record.
     *
     * Format:
     * ```
     * {"pts":<us>,"src":[w,h],
     *  "tracks":[{"id":N,"cls":"CAR","bb":[l,t,r,b]}, ...],
     *  "plates":[{"pid":N,"bb":[l,t,r,b],"t":"34ABC123","v":5,"a":0.83}, ...]}
     * ```
     * Empty arrays still emit `[]` rather than being omitted — keeps the
     * reader's parse paths uniform.
     */
    fun toNdjson(): String {
        val sb = StringBuilder(96 + tracks.size * 56 + plates.size * 64)
        sb.append('{')
        sb.append("\"pts\":").append(presentationTimeUs)
        sb.append(",\"src\":[").append(sourceWidth).append(',').append(sourceHeight).append(']')
        sb.append(",\"tracks\":[")
        tracks.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(t.trackId)
            sb.append(",\"cls\":\"").append(t.cls.name).append('"')
            sb.append(",\"bb\":[")
            appendRect(sb, t.bbox)
            sb.append("]}")
        }
        sb.append("],\"plates\":[")
        plates.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"pid\":").append(p.parentTrackId)
            sb.append(",\"bb\":[")
            appendRect(sb, p.bbox)
            sb.append(']')
            if (p.text != null) {
                sb.append(",\"t\":\"").append(jsonEscape(p.text)).append('"')
                sb.append(",\"v\":").append(p.votes)
                sb.append(",\"a\":").append(format2(p.agreement))
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        /** Tolerant parser: ignores malformed lines (returns null), missing optional fields are zero/empty. */
        fun fromNdjson(line: String): SidecarEntry? {
            val l = line.trim()
            if (l.isEmpty() || !l.startsWith("{")) return null
            val pts = extractLong(l, "\"pts\"") ?: return null
            val srcPair = extractIntPair(l, "\"src\"") ?: return null
            val tracks = parseTrackRows(l)
            val plates = parsePlateRows(l)
            return SidecarEntry(
                presentationTimeUs = pts,
                sourceWidth        = srcPair.first,
                sourceHeight       = srcPair.second,
                tracks             = tracks,
                plates             = plates,
            )
        }

        // ── small hand-rolled helpers — no kotlinx.serialization dep ──

        private fun appendRect(sb: StringBuilder, r: RectF) {
            sb.append(format2(r.left)).append(',')
                .append(format2(r.top)).append(',')
                .append(format2(r.right)).append(',')
                .append(format2(r.bottom))
        }

        private fun format2(v: Float): String =
            String.format(Locale.US, "%.2f", v)

        private fun jsonEscape(text: String): String {
            // We only ever serialise plate text (uppercase ASCII alphanumerics) +
            // OEM-supplied vehicle class names. Defensive: escape backslash and
            // double-quote anyway in case future callers feed in arbitrary text.
            if (text.indexOf('\\') < 0 && text.indexOf('"') < 0) return text
            val sb = StringBuilder(text.length + 4)
            for (c in text) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"'  -> sb.append("\\\"")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun extractLong(line: String, key: String): Long? {
            val idx = line.indexOf(key); if (idx < 0) return null
            val colon = line.indexOf(':', idx + key.length); if (colon < 0) return null
            var i = colon + 1
            while (i < line.length && line[i] == ' ') i++
            val start = i
            while (i < line.length && (line[i] == '-' || line[i].isDigit())) i++
            return if (i > start) line.substring(start, i).toLongOrNull() else null
        }

        private fun extractFloat(line: String, key: String): Float? {
            val idx = line.indexOf(key); if (idx < 0) return null
            val colon = line.indexOf(':', idx + key.length); if (colon < 0) return null
            var i = colon + 1
            while (i < line.length && line[i] == ' ') i++
            val start = i
            while (i < line.length && (line[i] == '-' || line[i] == '.' || line[i].isDigit() || line[i] == 'e' || line[i] == 'E' || line[i] == '+')) i++
            return if (i > start) line.substring(start, i).toFloatOrNull() else null
        }

        private fun extractIntPair(line: String, key: String): Pair<Int, Int>? {
            val idx = line.indexOf(key); if (idx < 0) return null
            val open = line.indexOf('[', idx + key.length); if (open < 0) return null
            val close = line.indexOf(']', open + 1); if (close < 0) return null
            val parts = line.substring(open + 1, close).split(',')
            if (parts.size < 2) return null
            val a = parts[0].trim().toIntOrNull() ?: return null
            val b = parts[1].trim().toIntOrNull() ?: return null
            return a to b
        }

        private fun extractString(slice: String, key: String): String? {
            val k = "$key:\""
            val ki = slice.indexOf(k); if (ki < 0) return null
            val start = ki + k.length
            // Walk to the next unescaped quote — plate texts are simple, but
            // the parser stays correct if a future encoder writes escapes.
            var i = start
            while (i < slice.length) {
                val c = slice[i]
                if (c == '\\' && i + 1 < slice.length) { i += 2; continue }
                if (c == '"') return slice.substring(start, i)
                i++
            }
            return null
        }

        private fun parseTrackRows(line: String): List<SidecarEntry.TrackRow> {
            val arr = extractArrayBody(line, "\"tracks\"") ?: return emptyList()
            return splitTopLevelObjects(arr).mapNotNull { obj ->
                val id  = extractLong(obj, "\"id\"")?.toInt() ?: return@mapNotNull null
                val clsName = extractString(obj, "\"cls\"") ?: return@mapNotNull null
                val cls = runCatching { VehicleClass.valueOf(clsName) }.getOrDefault(VehicleClass.UNKNOWN)
                val bb  = extractRect(obj, "\"bb\"") ?: return@mapNotNull null
                SidecarEntry.TrackRow(trackId = id, cls = cls, bbox = bb)
            }
        }

        private fun parsePlateRows(line: String): List<SidecarEntry.PlateRow> {
            val arr = extractArrayBody(line, "\"plates\"") ?: return emptyList()
            return splitTopLevelObjects(arr).mapNotNull { obj ->
                val pid = extractLong(obj, "\"pid\"")?.toInt() ?: return@mapNotNull null
                val bb  = extractRect(obj, "\"bb\"") ?: return@mapNotNull null
                val text = extractString(obj, "\"t\"")
                val votes = extractLong(obj, "\"v\"")?.toInt() ?: 0
                val agree = extractFloat(obj, "\"a\"") ?: 0f
                SidecarEntry.PlateRow(
                    parentTrackId = pid, bbox = bb,
                    text = text?.takeIf { it.isNotEmpty() },
                    votes = votes, agreement = agree,
                )
            }
        }

        private fun extractRect(slice: String, key: String): RectF? {
            val open = slice.indexOf("$key:[").let { if (it < 0) -1 else it + key.length + 2 }
            if (open < 0) return null
            val close = slice.indexOf(']', open); if (close < 0) return null
            val parts = slice.substring(open, close).split(',')
            if (parts.size < 4) return null
            val l = parts[0].trim().toFloatOrNull() ?: return null
            val t = parts[1].trim().toFloatOrNull() ?: return null
            val r = parts[2].trim().toFloatOrNull() ?: return null
            val b = parts[3].trim().toFloatOrNull() ?: return null
            return RectF(l, t, r, b)
        }

        private fun extractArrayBody(line: String, key: String): String? {
            val ki = line.indexOf(key); if (ki < 0) return null
            val open = line.indexOf('[', ki + key.length); if (open < 0) return null
            // Walk forward matching brackets to handle nested rows-with-inner-arrays.
            var depth = 0
            var i = open
            while (i < line.length) {
                when (line[i]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return line.substring(open + 1, i) }
                }
                i++
            }
            return null
        }

        private fun splitTopLevelObjects(body: String): List<String> {
            if (body.isBlank()) return emptyList()
            val out = ArrayList<String>(4)
            var depth = 0
            var start = -1
            var i = 0
            while (i < body.length) {
                when (body[i]) {
                    '{' -> { if (depth == 0) start = i; depth++ }
                    '}' -> { depth--; if (depth == 0 && start >= 0) { out += body.substring(start, i + 1); start = -1 } }
                    '"' -> {
                        // Skip over a quoted string verbatim so commas / brackets
                        // inside text don't confuse the depth counter.
                        i++
                        while (i < body.length) {
                            val q = body[i]
                            if (q == '\\' && i + 1 < body.length) { i += 2; continue }
                            if (q == '"') break
                            i++
                        }
                    }
                }
                i++
            }
            return out
        }
    }
}
