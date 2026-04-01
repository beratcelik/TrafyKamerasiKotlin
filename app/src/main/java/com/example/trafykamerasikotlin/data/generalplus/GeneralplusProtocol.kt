package com.example.trafykamerasikotlin.data.generalplus

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary packet encoder/decoder for the GeneralPlus (GP) dashcam TCP protocol.
 *
 * Every packet — both commands and responses — starts with the 8-byte ASCII
 * magic string "GPSOCKET", followed by 4 bytes of command/status info and an
 * optional 4-byte parameter/data field.
 *
 * ─── Command (client → camera) ────────────────────────────────────────────
 *   Bytes  0–7   "GPSOCKET"  (ASCII magic, 8 bytes)
 *   Byte   8     Type        0x01 = CMD
 *   Byte   9     CMDIndex    monotonically increasing uint8 sequence number
 *   Byte  10     Mode        see MODE_* constants
 *   Byte  11     CMDID       command ID within the mode
 *
 *   Regular command (no extra data):  12 bytes total
 *   Extended command (+ 4-byte data): 16 bytes total  ← bytes 12–15 = extra
 *
 * ─── Response (camera → client) ───────────────────────────────────────────
 *   Bytes  0–7   "GPSOCKET"
 *   Byte   8     Type        0x02 = ACK (success), 0x03 = NAK (error)
 *   Byte   9     CMDIndex    mirrors the command index
 *   Byte  10     Mode        mirrors the command mode
 *   Byte  11     CMDID       mirrors the command CMDID
 *   Bytes 12–13  DataSize    uint16 LE: number of payload bytes that follow
 *   Bytes 14+    Data        DataSize bytes of response payload
 *
 *   Confirmed from PcapDroid Frame 10 (AuthDevice ACK, 20 bytes total):
 *     GPSOCKET 02 00 00 05  06 00  f2 18 11 19 b0 43
 *     DataSize = 0x0006 = 6, payload = f2 18 11 19 b0 43 (6 bytes) ✓
 *
 *   Minimum response: 14 bytes (DataSize == 0).
 *   With payload:     14 + DataSize bytes.
 *
 * ─── Connection / AuthDevice packet (observed from PcapDroid) ─────────────
 *   47 50 53 4f 43 4b 45 54  01 00 00 05  77 07 8c 12
 *   G  P  S  O  C  K  E  T  type=CMD idx=0 mode=0 cmd=AuthDevice  token
 *
 * Reference: CamWrapper.java / MsgObj.java / Q6/h.java in viidure-jadx
 * (generalplus/com/GPCamLib/CamWrapper.java).
 */
object GeneralplusProtocol {

    private val MAGIC = "GPSOCKET".toByteArray(Charsets.US_ASCII)

    // ── Packet sizes ───────────────────────────────────────────────────────
    const val CMD_MIN_SIZE  = 12   // GPSOCKET (8) + type/idx/mode/cmd (4)
    const val CMD_EXT_SIZE  = 16   // CMD_MIN_SIZE + 4-byte param
    const val RESP_MIN_SIZE = 14   // GPSOCKET (8) + type/idx/mode/cmd (4) + DataSize (2)

    // ── Packet types (byte 8) ──────────────────────────────────────────────
    const val TYPE_CMD: Byte = 0x01   // GP_SOCK_TYPE_CMD
    const val TYPE_ACK: Byte = 0x02   // GP_SOCK_TYPE_ACK (success)
    const val TYPE_NAK: Byte = 0x03   // GP_SOCK_TYPE_NAK (error)

    // ── Modes (byte 10) ───────────────────────────────────────────────────
    const val MODE_GENERAL : Byte = 0x00  // GPSOCK_MODE_General
    const val MODE_RECORD  : Byte = 0x01  // GPSOCK_MODE_Record
    const val MODE_CAPTURE : Byte = 0x02  // GPSOCK_MODE_CapturePicture
    const val MODE_PLAYBACK: Byte = 0x03  // GPSOCK_MODE_Playback
    const val MODE_MENU    : Byte = 0x04  // GPSOCK_MODE_Menu
    const val MODE_VENDOR  : Byte = 0xFF.toByte()  // GPSOCK_MODE_Vendor

    // ── General mode command IDs (byte 11 when mode == MODE_GENERAL) ──────
    const val CMD_SET_MODE          : Byte = 0x00
    const val CMD_GET_DEVICE_STATUS : Byte = 0x01
    const val CMD_GET_PARAMETER_FILE: Byte = 0x02
    const val CMD_POWEROFF          : Byte = 0x03
    const val CMD_RESTART_STREAMING : Byte = 0x04
    const val CMD_AUTH_DEVICE       : Byte = 0x05

    // ── Menu mode command IDs (byte 11 when mode == MODE_MENU) ───────────
    const val CMD_MENU_GET_PARAMETER: Byte = 0x00  // select & read current value index
    const val CMD_MENU_SET_PARAMETER: Byte = 0x01  // write new value index (stateful)

    // ── Playback mode command IDs (byte 11 when mode == MODE_PLAYBACK) ───
    // Confirmed from PcapDroid capture (PCAPdroid_01_Nis_18_13_49.pcap).
    const val CMD_PLAYBACK_START    : Byte = 0x00  // StartPlayback(fileIndex uint16 LE)
    const val CMD_PLAYBACK_PAUSE    : Byte = 0x01  // Pause
    const val CMD_PLAYBACK_GET_COUNT: Byte = 0x02  // GetFileCount → uint16 LE file count
    const val CMD_PLAYBACK_GET_LIST : Byte = 0x03  // GetNameList(type, startIdx) → entries
    const val CMD_PLAYBACK_GET_THUMB: Byte = 0x04  // GetThumbnail(fileIndex) → JPEG bytes
    const val CMD_PLAYBACK_GET_RAW  : Byte = 0x05  // GetRawData(fileIndex) → chunked file data
    const val CMD_PLAYBACK_STOP     : Byte = 0x06  // Stop(extCode byte)
    const val CMD_PLAYBACK_GET_NAME : Byte = 0x07  // GetSpecificName(fileIndex) → filename
    const val CMD_PLAYBACK_DELETE   : Byte = 0x08  // DeleteFile(fileIndex) → ACK/NAK

    // ── Device modes for SetMode command ──────────────────────────────────
    const val DEVICE_MODE_RECORD   : Byte = 0x00  // Normal recording
    const val DEVICE_MODE_PLAYBACK : Byte = 0x02  // File browsing / download

    /** Maximum bytes per GetRawData streaming chunk (confirmed from PCAP). */
    const val RAW_DATA_CHUNK_SIZE = 61440

    /** Bytes of XML data per GetParameterFile chunk (confirmed from PcapDroid frame 35). */
    const val XML_CHUNK_DATA_SIZE = 242

    /**
     * Fixed 4-byte auth token appended to the AuthDevice packet.
     * Observed verbatim in PcapDroid capture of the Viidure app.
     */
    private val AUTH_TOKEN = byteArrayOf(0x77, 0x07, 0x8c.toByte(), 0x12)

    // ── Parsed response ────────────────────────────────────────────────────

    data class Response(
        val type   : Byte,
        val cmdIdx : Byte,
        val mode   : Byte,
        val cmdId  : Byte,
        val data   : ByteArray = ByteArray(0),
    ) {
        val isAck: Boolean get() = type == TYPE_ACK
    }

    // ── Packet builders ────────────────────────────────────────────────────

    /**
     * Builds the 16-byte AuthDevice (connection/handshake) packet.
     * Must be the very first packet sent after TCP connect.
     */
    fun buildAuthDevice(cmdIdx: Byte = 0x00): ByteArray =
        buildExtended(cmdIdx, MODE_GENERAL, CMD_AUTH_DEVICE, AUTH_TOKEN)

    /**
     * Builds a 12-byte command packet (no extra data field).
     */
    fun buildCommand(cmdIdx: Byte, mode: Byte, cmdId: Byte): ByteArray {
        val buf = ByteArray(CMD_MIN_SIZE)
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = mode
        buf[11] = cmdId
        return buf
    }

    /**
     * Builds a 16-byte extended command packet with 4 bytes of extra data.
     */
    fun buildExtended(cmdIdx: Byte, mode: Byte, cmdId: Byte, param: ByteArray): ByteArray {
        require(param.size == 4) { "param must be exactly 4 bytes" }
        val buf = ByteArray(CMD_EXT_SIZE)
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = mode
        buf[11] = cmdId
        param.copyInto(buf, 12)
        return buf
    }

    /**
     * Builds a 16-byte GetParameter command.
     * mode=MODE_MENU (0x04), cmdId=CMD_MENU_GET_PARAMETER (0x00)
     * settingId is encoded as uint32 little-endian in the 4-byte param field.
     * Confirmed from PcapDroid frame 55: settingId=0x9006 → param=`06 90 00 00`.
     */
    fun buildGetParameter(cmdIdx: Byte, settingId: Int): ByteArray {
        val param = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(settingId).array()
        return buildExtended(cmdIdx, MODE_MENU, CMD_MENU_GET_PARAMETER, param)
    }

    /**
     * Builds a 17-byte SetParameter command for enum and action settings.
     * mode=MODE_MENU (0x04), cmdId=CMD_MENU_SET_PARAMETER (0x01)
     * settingId encoded as uint32 LE in bytes 12-15, newValueIdx in byte 16.
     * Confirmed from PcapDroid: 17-byte format with settingId embedded (NOT stateful).
     * Response: 14 bytes (pure ACK, DataSize=0).
     */
    fun buildSetParameter(cmdIdx: Byte, settingId: Int, newValueIdx: Int): ByteArray {
        val buf = ByteArray(CMD_EXT_SIZE + 1)   // 16 + 1 = 17 bytes
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = MODE_MENU
        buf[11] = CMD_MENU_SET_PARAMETER
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(settingId).array().copyInto(buf, 12)
        buf[16] = (newValueIdx and 0xFF).toByte()
        return buf
    }

    /**
     * Builds a 13-byte SetMode command.
     * Confirmed from PCAP: SetMode(Playback=0x02) = 4750534f434b45540100000002
     */
    fun buildSetMode(cmdIdx: Byte, deviceMode: Byte): ByteArray {
        val buf = ByteArray(CMD_MIN_SIZE + 1)
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = MODE_GENERAL
        buf[11] = CMD_SET_MODE
        buf[12] = deviceMode
        return buf
    }

    /**
     * Builds a 14-byte Playback command with a 2-byte fileIndex parameter (uint16 LE).
     * Used for: StartPlayback(0x00), GetThumbnail(0x04), GetRawData(0x05), DeleteFile(0x08).
     * Confirmed from PCAP: GetThumbnail(fileIndex=2) = 4750534f434b4554010003040200
     */
    fun buildPlaybackCmd(cmdIdx: Byte, cmdId: Byte, fileIndex: Int): ByteArray {
        val buf = ByteArray(CMD_MIN_SIZE + 2)
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = MODE_PLAYBACK
        buf[11] = cmdId
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(fileIndex.toShort()).array()
            .copyInto(buf, 12)
        return buf
    }

    /**
     * Builds a 13-byte Stop command.
     * mode=MODE_PLAYBACK, cmdId=CMD_PLAYBACK_STOP, 1-byte stop code.
     * Confirmed from PCAP [97]: Stop(0x41) precedes every GetRawData request.
     * Stops any active RTSP playback before raw file download can proceed.
     */
    fun buildPlaybackStop(cmdIdx: Byte, stopCode: Byte = 0x41): ByteArray {
        val buf = ByteArray(CMD_MIN_SIZE + 1)
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = MODE_PLAYBACK
        buf[11] = CMD_PLAYBACK_STOP
        buf[12] = stopCode
        return buf
    }

    /**
     * Builds a 15-byte GetNameList command.
     * mode=MODE_PLAYBACK, cmdId=CMD_PLAYBACK_GET_LIST.
     * Confirmed from PCAP: param=010000 (type=0x01, startIdx=0x0000).
     * type=0x01 returns all files; startIdx is 0-based first file to return.
     */
    fun buildGetNameList(cmdIdx: Byte, type: Byte = 0x01, startIdx: Int = 0): ByteArray {
        val buf = ByteArray(CMD_MIN_SIZE + 3)
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = MODE_PLAYBACK
        buf[11] = CMD_PLAYBACK_GET_LIST
        buf[12] = type
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(startIdx.toShort()).array()
            .copyInto(buf, 13)
        return buf
    }

    /**
     * Builds a variable-length SetParameter command for string settings (e.g. WiFi password).
     * Format: GPSOCKET(8) + type(1) + idx(1) + mode(1) + cmdId(1) + settingId(4 LE) + len(1) + value(N)
     * Confirmed from PcapDroid: SetParameter(0x0301, len=8, "12345678") = 25 bytes.
     */
    fun buildSetParameterString(cmdIdx: Byte, settingId: Int, value: String): ByteArray {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(CMD_EXT_SIZE + 1 + valueBytes.size)  // 17 + N bytes
        MAGIC.copyInto(buf, 0)
        buf[8]  = TYPE_CMD
        buf[9]  = cmdIdx
        buf[10] = MODE_MENU
        buf[11] = CMD_MENU_SET_PARAMETER
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(settingId).array().copyInto(buf, 12)
        buf[16] = valueBytes.size.toByte()
        valueBytes.copyInto(buf, 17)
        return buf
    }

    // ── Response reader ────────────────────────────────────────────────────

    /**
     * Reads exactly one response from [input].
     *
     * Reads the 14-byte minimum header, verifies the "GPSOCKET" magic,
     * then reads the DataSize payload if present.
     *
     * Returns null on stream error or if the magic bytes are wrong.
     */
    fun readResponse(input: InputStream): Response? {
        val header = readExact(input, RESP_MIN_SIZE) ?: return null

        // Verify "GPSOCKET" magic
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) return null
        }

        val type   = header[8]
        val cmdIdx = header[9]
        val mode   = header[10]
        val cmdId  = header[11]

        // For ACK responses bytes 12–13 = DataSize (uint16 LE).
        // For NAK responses bytes 12–13 = error code (observed 0xFFFE/0xFFFF in PCAP);
        // there is NO data payload after a NAK — do not attempt to read one.
        val data = if (type == TYPE_ACK) {
            val dataSize = ByteBuffer.wrap(header, 12, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            if (dataSize > 0) readExact(input, dataSize) ?: ByteArray(0) else ByteArray(0)
        } else {
            ByteArray(0)
        }

        return Response(type, cmdIdx, mode, cmdId, data)
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private fun readExact(input: InputStream, len: Int): ByteArray? {
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n == -1) return null
            offset += n
        }
        return buf
    }
}
