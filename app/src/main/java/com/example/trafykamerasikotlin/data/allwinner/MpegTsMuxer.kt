package com.example.trafykamerasikotlin.data.allwinner

import java.io.OutputStream

/**
 * Minimal MPEG-TS muxer for a single H.264 video stream. Output is a
 * standards-compliant transport stream that FFmpeg's `mpegts` demuxer —
 * the one IjkPlayer's lite FFmpeg build keeps enabled — accepts as live
 * input. Audio is intentionally not muxed; cam audio is dropped upstream.
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
        private const val PROGRAM_NUM        = 1
        private const val H264_STREAM_TYPE   = 0x1B
        private const val TS_PACKET_SIZE     = 188
        // Cam runs at 25 fps (HI3516CV610-class encoder default; verified
        // by counting recorded frames vs wall-clock). Tagging frames at
        // 30 fps caused the player to burn through its buffer faster than
        // packets arrived, freezing on the first frame.
        private const val PTS_TICK_PER_FRAME = 90000L / 25  // 25 fps → 3600
        private const val TABLE_REEMIT_EVERY = 30
    }

    private var videoCc = 0
    private var patCc   = 0
    private var pmtCc   = 0
    private var pts     = 0L
    private var frameCount = 0

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
        val section = byteArrayOf(
            0x02,                             // table_id = PMT
            0xB0.toByte(), 0x12,              // length = 18
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
