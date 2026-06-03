package com.example.trafykamerasikotlin.data.allwinner

import java.io.OutputStream

/**
 * Splits an Annex-B H.264 byte buffer into discrete access units.
 *
 * The Allwinner cam dumps multiple frames in a single rtp2p inner-segment
 * during SD-card playback (live mode emits one frame per inner-segment;
 * playback mode does not). Without splitting, the muxer wraps the whole
 * blob as a single PES with one PTS — IjkPlayer can't render that.
 *
 * Strategy: walk start codes (`00 00 00 01` or `00 00 01`), classify each
 * NAL by `nal_unit_type & 0x1F`, and split before every VCL slice (1..5)
 * after we've already seen one in the current AU. Non-VCL NALs (SPS, PPS,
 * SEI, AUD) attach to the next AU. Output is suitable for one-PES-per-AU
 * muxing.
 */
fun splitAnnexBIntoAccessUnits(stream: ByteArray): List<ByteArray> {
    if (stream.isEmpty()) return emptyList()
    val nalStarts = ArrayList<Int>(16)
    var i = 0
    while (i + 3 < stream.size) {
        if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte()) {
            if (stream[i + 2] == 1.toByte()) {
                nalStarts.add(i + 3); i += 3; continue
            } else if (i + 3 < stream.size && stream[i + 2] == 0.toByte() && stream[i + 3] == 1.toByte()) {
                nalStarts.add(i + 4); i += 4; continue
            }
        }
        i++
    }
    if (nalStarts.isEmpty()) return listOf(stream)

    val aus = ArrayList<ByteArray>()
    var auStart = 0
    var auHasVcl = false
    for (idx in nalStarts.indices) {
        val nalHdrPos = nalStarts[idx]
        if (nalHdrPos >= stream.size) continue
        val type = stream[nalHdrPos].toInt() and 0x1F
        val isVcl = type in 1..5
        if (isVcl && auHasVcl) {
            // Boundary: flush from auStart up to the start code preceding this NAL.
            // Find the nearest preceding `00 00 ?? 01` start position.
            val codeStart = findStartCodeBefore(stream, nalHdrPos)
            aus.add(stream.copyOfRange(auStart, codeStart))
            auStart = codeStart
            auHasVcl = true
        } else if (isVcl) {
            auHasVcl = true
        }
    }
    if (auStart < stream.size) aus.add(stream.copyOfRange(auStart, stream.size))
    return aus
}

/**
 * Splits a buffer of concatenated ADTS frames into individual frames using
 * the 13-bit `frame_length` field in each header. The Allwinner cam ships
 * audio in `?? 11 20 ??`-tagged segments that may carry multiple ADTS
 * frames glued back-to-back; the muxer needs them one-per-PES.
 *
 * Tolerant of leading garbage (skips bytes until a valid `0xFFF` sync) and
 * truncated tail (stops cleanly when the last frame would run past the
 * buffer end).
 */
fun splitAdtsFrames(buf: ByteArray): List<ByteArray> {
    if (buf.size < 7) return emptyList()
    val out = ArrayList<ByteArray>(8)
    var i = 0
    while (i + 7 <= buf.size) {
        val syncOk = (buf[i].toInt() and 0xFF) == 0xFF &&
                     (buf[i + 1].toInt() and 0xF0) == 0xF0
        if (!syncOk) { i++; continue }
        val frameLen = ((buf[i + 3].toInt() and 0x03) shl 11) or
                       ((buf[i + 4].toInt() and 0xFF) shl 3) or
                       ((buf[i + 5].toInt() and 0xE0) ushr 5)
        if (frameLen < 7 || i + frameLen > buf.size) break
        out.add(buf.copyOfRange(i, i + frameLen))
        i += frameLen
    }
    return out
}

private fun findStartCodeBefore(buf: ByteArray, nalHdrPos: Int): Int {
    if (nalHdrPos >= 3 && buf[nalHdrPos - 1] == 1.toByte() &&
        buf[nalHdrPos - 2] == 0.toByte() && buf[nalHdrPos - 3] == 0.toByte()) {
        return if (nalHdrPos >= 4 && buf[nalHdrPos - 4] == 0.toByte()) nalHdrPos - 4 else nalHdrPos - 3
    }
    return nalHdrPos
}

/**
 * Minimal MPEG-TS muxer for a single H.264 video stream. Output is a
 * standards-compliant transport stream that FFmpeg's `mpegts` demuxer —
 * the one IjkPlayer's lite FFmpeg build keeps enabled — accepts as live
 * input. Optionally muxes a parallel AAC-LC audio elementary stream on
 * PID 0x101 — call [writeAdtsFrame] per ADTS frame to add it.
 *
 * Each [writeFrame] emits one PES packet on the video PID (0x100). The
 * PSI tables (PAT on PID 0, PMT on PID 0x1000) are re-emitted every ~30
 * frames so a player joining mid-stream re-syncs quickly.
 *
 * PTS is monotonically incremented assuming 30 fps. The cam doesn't ship
 * real timestamps with each frame, but a steady virtual clock is enough
 * for IjkPlayer to render at a smooth pace; minor drift won't matter on
 * a live preview.
 */
class MpegTsMuxer(private val out: OutputStream) {

    companion object {
        private const val PAT_PID            = 0x0000
        private const val PMT_PID            = 0x1000
        private const val VIDEO_PID          = 0x0100
        private const val AUDIO_PID          = 0x0101
        private const val PROGRAM_NUM        = 1
        private const val H264_STREAM_TYPE   = 0x1B
        // ISO/IEC 13818-7 Audio with ADTS transport — what FFmpeg's mpegts
        // demuxer expects when AAC frames have their original ADTS headers.
        private const val AAC_ADTS_STREAM_TYPE = 0x0F
        private const val TS_PACKET_SIZE     = 188
        // Cam runs at 25 fps (HI3516CV610-class encoder default; verified
        // by counting recorded frames vs wall-clock). Tagging frames at
        // 30 fps caused the player to burn through its buffer faster than
        // packets arrived, freezing on the first frame.
        private const val PTS_TICK_PER_FRAME = 90000L / 25  // 25 fps → 3600
        private const val TABLE_REEMIT_EVERY = 30
    }

    private var videoCc = 0
    private var audioCc = 0
    private var patCc   = 0
    private var pmtCc   = 0
    private var pts     = 0L
    private var audioPts = 0L
    private var frameCount = 0
    /**
     * AAC sample rate detected from the first ADTS header we mux. Defaults
     * to 16 kHz which is what the Allwinner V853 cam encodes at — verified
     * by parsing the `ff f1 60 40 ...` sync pattern in its rtp2p audio
     * segments. AAC-LC frames are 1024 samples regardless of rate, so the
     * 90 kHz PTS tick per frame is `1024 * 90000 / sampleRate` (5760 at
     * 16 kHz). Wrong rate → audio drift, eventually muting in players.
     */
    private var audioSampleRate = 16_000

    /**
     * Wraps one H.264 access unit's bytes (Annex-B) in one PES packet.
     *
     * PTS uses a fixed 25 fps clock (the cam's encoder runs at 25 fps —
     * confirmed by counting GOPs of 24-25 frames each). Wall-clock PTS
     * tracking made playback jittery because frames arrive in bursts on
     * the wire; a fixed-rate PTS lets the player's buffer absorb the
     * jitter and render smoothly.
     */
    fun writeFrame(h264: ByteArray) {
        if (h264.isEmpty()) return
        if (frameCount % TABLE_REEMIT_EVERY == 0) {
            emitPat()
            emitPmt()
        }
        emitPes(h264)
        frameCount++
        pts += PTS_TICK_PER_FRAME
    }

    /**
     * Wraps one AAC ADTS frame as a single PES packet on the audio PID.
     *
     * Audio PTS advances at `1024 * 90000 / sampleRate` ticks per frame —
     * AAC-LC is fixed at 1024 samples per frame. We parse the sample rate
     * from the first ADTS header and assume it stays constant for the
     * stream (true for this cam; would need a per-frame parse for a stream
     * that adapts rate mid-file).
     *
     * Caller must hand over byte boundaries that line up with ADTS frames
     * — use [splitAdtsFrames] if the upstream chunk concatenates several.
     */
    fun writeAdtsFrame(adts: ByteArray) {
        if (adts.size < 7) return
        if (audioPts == 0L) audioSampleRate = parseAdtsSampleRate(adts) ?: audioSampleRate
        emitAudioPes(adts)
        audioPts += 1024L * 90000L / audioSampleRate
    }

    fun flush() = out.flush()

    // ── PSI ────────────────────────────────────────────────────────────

    private fun emitPat() {
        val section = byteArrayOf(
            0x00,                             // table_id = PAT
            0xB0.toByte(), 0x0D,              // section_syntax_indicator=1, length=13
            0x00, 0x01,                       // transport_stream_id = 1
            0xC1.toByte(), 0x00, 0x00,        // version=0, current_next=1, sec_no, last_sec_no
            // program loop: 1 entry
            (PROGRAM_NUM ushr 8).toByte(),
            (PROGRAM_NUM and 0xFF).toByte(),
            (0xE0 or (PMT_PID ushr 8)).toByte(),
            (PMT_PID and 0xFF).toByte(),
        )
        emitPsi(PAT_PID, section + crc32(section)) { val c = patCc; patCc = (patCc + 1) and 0xF; c }
    }

    private fun emitPmt() {
        // section_length covers everything after itself up to and including
        // the CRC. With two ES entries (5B each) and 9B fixed PMT body +
        // 4B CRC, that's 9 + 5 + 5 + 4 = 23 → 0x17.
        val section = byteArrayOf(
            0x02,                             // table_id = PMT
            0xB0.toByte(), 0x17,              // section_syntax_indicator=1, length=23
            (PROGRAM_NUM ushr 8).toByte(),
            (PROGRAM_NUM and 0xFF).toByte(),
            0xC1.toByte(), 0x00, 0x00,        // version+current+section numbers
            (0xE0 or (VIDEO_PID ushr 8)).toByte(),  // PCR_PID = video PID
            (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,              // program_info_length = 0
            // ES loop: H.264 video
            H264_STREAM_TYPE.toByte(),
            (0xE0 or (VIDEO_PID ushr 8)).toByte(),
            (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,              // ES_info_length = 0
            // ES loop: AAC ADTS audio
            AAC_ADTS_STREAM_TYPE.toByte(),
            (0xE0 or (AUDIO_PID ushr 8)).toByte(),
            (AUDIO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,              // ES_info_length = 0
        )
        emitPsi(PMT_PID, section + crc32(section)) { val c = pmtCc; pmtCc = (pmtCc + 1) and 0xF; c }
    }

    private inline fun emitPsi(pid: Int, sectionWithCrc: ByteArray, ccProvider: () -> Int) {
        val pkt = ByteArray(TS_PACKET_SIZE).also { it.fill(0xFF.toByte(), 4, TS_PACKET_SIZE) }
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid ushr 8)).toByte()                  // PUSI=1
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or ccProvider()).toByte()                  // payload only
        pkt[4] = 0x00                                             // pointer field
        sectionWithCrc.copyInto(
            destination = pkt,
            destinationOffset = 5,
            startIndex = 0,
            endIndex = sectionWithCrc.size.coerceAtMost(TS_PACKET_SIZE - 5),
        )
        out.write(pkt)
    }

    // ── PES ────────────────────────────────────────────────────────────

    private fun emitPes(payload: ByteArray) {
        val pesHeader = ByteArray(14)
        pesHeader[0] = 0x00; pesHeader[1] = 0x00; pesHeader[2] = 0x01
        pesHeader[3] = 0xE0.toByte()                              // stream_id = video
        pesHeader[4] = 0x00; pesHeader[5] = 0x00                  // length = 0 (unbounded video)
        pesHeader[6] = 0x80.toByte()                              // marker bits
        pesHeader[7] = 0x80.toByte()                              // PTS_DTS_flags = 10 (PTS only)
        pesHeader[8] = 0x05                                       // PES header data length
        encodePts(pts, pesHeader, 9)

        val full = pesHeader + payload
        var offset = 0
        var first = true
        while (offset < full.size) {
            val pkt = ByteArray(TS_PACKET_SIZE)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0x00) or (VIDEO_PID ushr 8)).toByte()
            pkt[2] = (VIDEO_PID and 0xFF).toByte()
            val payloadStart: Int
            if (first) {
                // Always carry an adaptation field with PCR on the first TS
                // packet of a PES — gives the player a clock reference.
                val pcrAdaptLen = 7  // 1 flag byte + 6 PCR bytes
                val remaining = full.size - offset
                if (remaining + 4 + 1 + pcrAdaptLen > TS_PACKET_SIZE) {
                    pkt[3] = (0x30 or videoCc).toByte()
                    pkt[4] = pcrAdaptLen.toByte()
                    pkt[5] = 0x10                                 // PCR_flag
                    encodePcr(pts, pkt, 6)
                    payloadStart = 4 + 1 + pcrAdaptLen
                } else {
                    val adaptLen = TS_PACKET_SIZE - 4 - 1 - remaining
                    pkt[3] = (0x30 or videoCc).toByte()
                    pkt[4] = adaptLen.toByte()
                    pkt[5] = 0x10
                    encodePcr(pts, pkt, 6)
                    for (i in 12 until 5 + adaptLen) pkt[i] = 0xFF.toByte()
                    payloadStart = 4 + 1 + adaptLen
                }
            } else {
                val remaining = full.size - offset
                if (remaining >= 184) {
                    pkt[3] = (0x10 or videoCc).toByte()
                    payloadStart = 4
                } else {
                    val adaptLen = 184 - remaining
                    pkt[3] = (0x30 or videoCc).toByte()
                    pkt[4] = (adaptLen - 1).toByte()
                    if (adaptLen > 1) {
                        pkt[5] = 0x00                             // no flags
                        for (i in 6 until 4 + adaptLen) pkt[i] = 0xFF.toByte()
                    }
                    payloadStart = 4 + adaptLen
                }
            }
            videoCc = (videoCc + 1) and 0xF
            val space = TS_PACKET_SIZE - payloadStart
            val copyLen = space.coerceAtMost(full.size - offset)
            full.copyInto(pkt, payloadStart, offset, offset + copyLen)
            // If the payload didn't fill the packet, the remaining bytes stay 0
            // — that's harmless because we sized the adaptation field above to
            // exactly fill any gap.
            out.write(pkt)
            offset += copyLen
            first = false
        }
    }

    /**
     * Emits one ADTS frame as a PES packet on the audio PID. PES length is
     * set explicitly (audio PES packets MUST have non-zero length per the
     * spec — only video gets the unbounded `length=0` exemption).
     */
    private fun emitAudioPes(payload: ByteArray) {
        val pesHeader = ByteArray(14)
        pesHeader[0] = 0x00; pesHeader[1] = 0x00; pesHeader[2] = 0x01
        pesHeader[3] = 0xC0.toByte()                              // stream_id = audio
        // PES_packet_length covers everything after the length field, i.e.
        // 8 bytes of optional PES header (we use marker+flags+headerLen+5B PTS)
        // plus the ADTS payload.
        val pesLen = 8 + payload.size
        pesHeader[4] = ((pesLen ushr 8) and 0xFF).toByte()
        pesHeader[5] = (pesLen and 0xFF).toByte()
        pesHeader[6] = 0x80.toByte()                              // marker bits
        pesHeader[7] = 0x80.toByte()                              // PTS_DTS_flags = 10 (PTS only)
        pesHeader[8] = 0x05                                       // PES header data length
        encodePts(audioPts, pesHeader, 9)

        val full = pesHeader + payload
        var offset = 0
        var first = true
        while (offset < full.size) {
            val pkt = ByteArray(TS_PACKET_SIZE)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0x00) or (AUDIO_PID ushr 8)).toByte()
            pkt[2] = (AUDIO_PID and 0xFF).toByte()
            val remaining = full.size - offset
            val payloadStart: Int
            if (remaining >= 184) {
                pkt[3] = (0x10 or audioCc).toByte()
                payloadStart = 4
            } else {
                // Stuff with adaptation field to fill the TS packet exactly.
                val adaptLen = 184 - remaining
                pkt[3] = (0x30 or audioCc).toByte()
                pkt[4] = (adaptLen - 1).toByte()
                if (adaptLen > 1) {
                    pkt[5] = 0x00
                    for (i in 6 until 4 + adaptLen) pkt[i] = 0xFF.toByte()
                }
                payloadStart = 4 + adaptLen
            }
            audioCc = (audioCc + 1) and 0xF
            val space = TS_PACKET_SIZE - payloadStart
            val copyLen = space.coerceAtMost(full.size - offset)
            full.copyInto(pkt, payloadStart, offset, offset + copyLen)
            out.write(pkt)
            offset += copyLen
            first = false
        }
    }

    /**
     * ADTS sample frequency table (per ISO/IEC 13818-7). Index lives in
     * bits 2..5 of byte 2 of the ADTS header.
     */
    private fun parseAdtsSampleRate(adts: ByteArray): Int? {
        if (adts.size < 4) return null
        // Sanity: validate sync = 0xFFF in bytes 0..1.
        if ((adts[0].toInt() and 0xFF) != 0xFF) return null
        if ((adts[1].toInt() and 0xF0) != 0xF0) return null
        val idx = (adts[2].toInt() ushr 2) and 0x0F
        val rates = intArrayOf(
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
            24_000, 22_050, 16_000, 12_000, 11_025,  8_000,
             7_350,      0,      0,      0,
        )
        return rates.getOrNull(idx)?.takeIf { it > 0 }
    }

    private fun encodePts(pts: Long, dst: ByteArray, off: Int) {
        // 33-bit PTS encoded as 5 bytes with marker bits, prefix `0010`.
        // Byte layout (per ISO/IEC 13818-1):
        //   byte 0: '0010' | PTS[32..30] | '1'        (marker)
        //   byte 1: PTS[29..22]
        //   byte 2: PTS[21..15] | '1'                  (marker)
        //   byte 3: PTS[14..7]
        //   byte 4: PTS[6..0]   | '1'                  (marker)
        dst[off]     = (0x21 or (((pts ushr 30) and 0x07L).toInt() shl 1)).toByte()
        dst[off + 1] = ((pts ushr 22) and 0xFFL).toByte()
        dst[off + 2] = ((((pts ushr 15) and 0x7FL).toInt() shl 1) or 0x01).toByte()
        dst[off + 3] = ((pts ushr 7) and 0xFFL).toByte()
        dst[off + 4] = ((((pts and 0x7FL).toInt()) shl 1) or 0x01).toByte()
    }

    private fun encodePcr(pcr: Long, dst: ByteArray, off: Int) {
        // 33-bit base + 6-bit reserved + 9-bit extension (we use 0)
        val base = pcr
        dst[off]     = ((base ushr 25) and 0xFFL).toByte()
        dst[off + 1] = ((base ushr 17) and 0xFFL).toByte()
        dst[off + 2] = ((base ushr  9) and 0xFFL).toByte()
        dst[off + 3] = ((base ushr  1) and 0xFFL).toByte()
        dst[off + 4] = (((base and 0x01L).toInt() shl 7) or 0x7E).toByte()
        dst[off + 5] = 0x00
    }

    private fun crc32(data: ByteArray): ByteArray {
        // MPEG-2 / DVB CRC32 (polynomial 0x04C11DB7, init 0xFFFFFFFF, no reflection).
        var crc = 0xFFFFFFFFL
        for (b in data) {
            for (i in 7 downTo 0) {
                val bit = ((b.toInt() ushr i) and 1).toLong()
                val msb = (crc ushr 31) and 1L
                crc = (crc shl 1) and 0xFFFFFFFFL
                if ((bit xor msb) != 0L) crc = crc xor 0x04C11DB7L
            }
        }
        return byteArrayOf(
            ((crc ushr 24) and 0xFFL).toByte(),
            ((crc ushr 16) and 0xFFL).toByte(),
            ((crc ushr  8) and 0xFFL).toByte(),
            (crc and 0xFFL).toByte(),
        )
    }
}
