package com.example.trafykamerasikotlin.data.allwinner

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire format for Allwinner V853 (GST-A19-01-PL) control channel on TCP :8000.
 *
 * Every message is length-prefixed:
 *   [4 bytes little-endian length N][N bytes payload]
 *
 * Payload formats:
 *   request:  "<cmd>:<cookie>{JSON}"       e.g. login:37{"app":"ysjl",...}
 *   response: "{JSON}"                     e.g. {"f":"login","cookie":37,"ret":0,...}
 *
 * The 4-byte length is the size of the payload only (it does NOT include the 4 length
 * bytes themselves).
 */
internal object AllwinnerFrameCodec {

    const val MAX_FRAME_BYTES = 10 * 1024 * 1024  // 10 MB hard cap

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        val n = payload.size
        val header = ByteArray(4)
        header[0] = (n and 0xFF).toByte()
        header[1] = ((n ushr 8) and 0xFF).toByte()
        header[2] = ((n ushr 16) and 0xFF).toByte()
        header[3] = ((n ushr 24) and 0xFF).toByte()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    fun readFrame(inp: InputStream): ByteArray {
        val header = readExact(inp, 4)
        val length = (header[0].toInt() and 0xFF) or
                ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 24)
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IllegalStateException("Allwinner frame length out of range: $length")
        }
        return readExact(inp, length)
    }

    private fun readExact(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = inp.read(buf, read, n - read)
            if (r < 0) throw EOFException("Allwinner stream closed after $read/$n bytes")
            read += r
        }
        return buf
    }
}
