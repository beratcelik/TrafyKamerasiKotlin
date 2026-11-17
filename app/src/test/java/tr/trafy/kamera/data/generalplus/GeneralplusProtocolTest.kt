package tr.trafy.kamera.data.generalplus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-exact checks for the GPSOCKET menu writes (mirrors iOS GpSocketProtocolTests). */
class GeneralplusProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `enum write carries a one-byte length before the value`() {
        // Report #19: without the 0x01 length Trafy Uno ACKs and stores nothing.
        val packet = GeneralplusProtocol.buildSetParameter(1, settingId = 3, newValueIdx = 2)
        assertEquals(18, packet.size)
        assertArrayEquals(bytes(0x01, 0x04, 0x01), packet.copyOfRange(9, 12)) // idx, MODE_MENU, SET_PARAMETER
        assertArrayEquals(bytes(0x03, 0x00, 0x00, 0x00, 0x01, 0x02), packet.copyOfRange(12, 18))
    }

    @Test fun `action keeps the zero-length 17-byte form`() {
        val packet = GeneralplusProtocol.buildTriggerAction(1, settingId = 0x0207) // Format SD
        assertEquals(17, packet.size)
        assertArrayEquals(bytes(0x07, 0x02, 0x00, 0x00, 0x00), packet.copyOfRange(12, 17))
    }

    @Test fun `packets start with the GPSOCKET magic and command type`() {
        val packet = GeneralplusProtocol.buildSetParameter(0, settingId = 0x0201, newValueIdx = 1)
        assertArrayEquals("GPSOCKET".toByteArray(Charsets.US_ASCII), packet.copyOfRange(0, 8))
        assertEquals(0x01.toByte(), packet[8])
    }
}
