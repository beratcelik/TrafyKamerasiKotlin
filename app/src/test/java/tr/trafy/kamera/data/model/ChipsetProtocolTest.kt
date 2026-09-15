package tr.trafy.kamera.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChipsetProtocolTest {

    @Test
    fun `maps each dashcam hotspot subnet to its chipset`() {
        assertEquals(ChipsetProtocol.GENERALPLUS, ChipsetProtocol.forClientIp("192.168.25.100"))
        assertEquals(ChipsetProtocol.HI_DVR, ChipsetProtocol.forClientIp("192.168.0.10"))
        assertEquals(ChipsetProtocol.MSTAR, ChipsetProtocol.forClientIp("192.168.1.33"))
        assertEquals(ChipsetProtocol.MSTAR_HZ, ChipsetProtocol.forClientIp("192.72.1.5"))
        assertEquals(ChipsetProtocol.SIGMA_STAR, ChipsetProtocol.forClientIp("192.168.201.2"))
        assertEquals(ChipsetProtocol.EEASYTECH, ChipsetProtocol.forClientIp("192.168.169.169"))
        assertEquals(ChipsetProtocol.ALLWINNER_V853, ChipsetProtocol.forClientIp("192.168.35.7"))
    }

    @Test
    fun `novatek wins over mstar on the shared 192_168_1 prefix`() {
        assertEquals(ChipsetProtocol.NOVATEK, ChipsetProtocol.forClientIp("192.168.1.254"))
    }

    @Test
    fun `non-dashcam subnets are not matched`() {
        assertNull(ChipsetProtocol.forClientIp("10.131.23.15"))
        assertNull(ChipsetProtocol.forClientIp("192.168.43.1"))
        // Prefix match must respect the octet boundary.
        assertNull(ChipsetProtocol.forClientIp("192.168.250.3"))
    }
}
