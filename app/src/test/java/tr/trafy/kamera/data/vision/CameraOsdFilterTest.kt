package tr.trafy.kamera.data.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOsdFilterTest {

    private fun osd(l: Float, t: Float, r: Float, b: Float, w: Int = 1920, h: Int = 1080) =
        CameraOsdFilter.isLikelyOsd(l, t, r, b, w, h)

    @Test fun `bottom-left date stamp is rejected`() {
        // "2026/11/17 16:49:58" at ~x=40..520, y=1030..1062 on a 1080p frame.
        assertTrue(osd(40f, 1030f, 520f, 1062f))
    }

    @Test fun `part of the stamp is rejected`() {
        // Detector boxes only the "16:49:58" chunk.
        assertTrue(osd(330f, 1030f, 520f, 1062f))
    }

    @Test fun `resolution line above the date is rejected`() {
        // Measured on a real G3518 frame: "4K+1080P" at 2–21% x, 81–87% y.
        assertTrue(osd(40f, 875f, 400f, 940f))
    }

    @Test fun `partial box on the resolution line is rejected`() {
        assertTrue(osd(150f, 870f, 400f, 905f))
    }

    @Test fun `plate of car ahead is kept`() {
        assertFalse(osd(800f, 620f, 960f, 650f))
    }

    @Test fun `close plate low in the middle lane is kept`() {
        // Centre x ≈ 50% — outside the left zone even though it's low.
        assertFalse(osd(860f, 870f, 1060f, 930f))
    }

    @Test fun `left-lane plate above the stamp is kept`() {
        // Centre y ≈ 72% — above the stamp zone.
        assertFalse(osd(300f, 760f, 520f, 800f))
    }

    @Test fun `time boxed together with the bumper is rejected`() {
        // Seen on device: box over "17:02:24" + bumper, centre x ≈ 0.40.
        assertTrue(osd(480f, 961f, 1075f, 1048f))
    }

    @Test fun `close plate slightly left of centre is kept`() {
        // ~25% of the plate lies in the zone.
        assertFalse(osd(672f, 864f, 1056f, 929f))
    }

    @Test fun `bottom-right is not the OSD zone`() {
        assertFalse(osd(1500f, 1030f, 1700f, 1062f))
    }

    @Test fun `top of frame is never filtered`() {
        assertFalse(osd(40f, 20f, 520f, 52f))
    }

    @Test fun `scales with 4K frames`() {
        assertTrue(osd(80f, 2070f, 1000f, 2120f, 3840, 2160))
        assertFalse(osd(1600f, 1300f, 1900f, 1360f, 3840, 2160))
    }

    @Test fun `degenerate frame never rejects`() {
        assertFalse(osd(10f, 10f, 20f, 20f, 0, 0))
    }
}
