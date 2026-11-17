package tr.trafy.kamera.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafyModelIdentifierTest {

    private fun hiDvr(model: String?) =
        DeviceInfo(protocol = ChipsetProtocol.HI_DVR, clientIp = "192.168.0.10", model = model)

    private fun name(device: DeviceInfo) =
        TrafyModelIdentifier.displayName(device, fallback = device.protocol.displayName)

    @Test fun `original Trafy Dos board`() {
        assertEquals("Trafy Dos", name(hiDvr("HI3516CV610-B-FV-CARRECORDER")))
    }

    @Test fun `later Trafy Dos board revision from report 18`() {
        assertEquals("Trafy Dos", name(hiDvr("HI3516CV610-E-CARRECORDER")))
    }

    @Test fun `Trafy Uno Pro`() {
        assertEquals("Trafy Uno Pro", name(hiDvr("G3518-FV-UCARRECORDER")))
    }

    @Test fun `unknown HiSilicon model falls back to the chipset name`() {
        assertEquals("HiSilicon DVR", name(hiDvr("SOMETHING-NEW")))
    }
}
