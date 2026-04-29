package com.example.trafykamerasikotlin.data.vision.voting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateVoteBookTest {

    /** Disable the Turkish-format filter for tests that aren't about it,
     *  so vote-counting and trimming semantics are exercised in isolation. */
    private fun unfilteredVoteBook(minVotes: Int = 3) =
        PlateVoteBook(minVotes = minVotes, plateFormat = null)

    @Test fun `no vote until minVotes reached`() {
        val vb = unfilteredVoteBook()
        vb.record(trackId = 1, text = "34ABC123")
        vb.record(trackId = 1, text = "34ABC123")
        assertNull("should return null before minVotes", vb.bestText(1))
        vb.record(trackId = 1, text = "34ABC123")
        assertNotNull("should return a result at minVotes", vb.bestText(1))
    }

    @Test fun `majority vote wins over noise`() {
        val vb = unfilteredVoteBook()
        vb.record(1, "34ABC123")
        vb.record(1, "34ABC123")
        vb.record(1, "34ABC123")
        vb.record(1, "XYZZZZZZ")  // one noisy read shouldn't flip the winner
        val voted = vb.bestText(1)!!
        assertEquals("34ABC123", voted.text)
        assertEquals(4, voted.votes)
        assertTrue(voted.agreement > 0.7f)
    }

    @Test fun `shorter reads trim to their common stem`() {
        val vb = unfilteredVoteBook()
        vb.record(1, "34ABC")   // slot 5..9 pad
        vb.record(1, "34ABC")
        vb.record(1, "34ABC")
        val voted = vb.bestText(1)!!
        assertEquals("34ABC", voted.text)
    }

    @Test fun `unrelated tracks don't cross-contaminate`() {
        val vb = unfilteredVoteBook()
        vb.record(1, "34ABC123")
        vb.record(1, "34ABC123")
        vb.record(1, "34ABC123")
        vb.record(2, "07XYZ999")
        vb.record(2, "07XYZ999")
        vb.record(2, "07XYZ999")
        assertEquals("34ABC123", vb.bestText(1)!!.text)
        assertEquals("07XYZ999", vb.bestText(2)!!.text)
    }

    @Test fun `prune drops entries for removed tracks`() {
        val vb = unfilteredVoteBook()
        vb.record(1, "34ABC")
        vb.record(1, "34ABC")
        vb.record(1, "34ABC")
        vb.prune(setOf(2))  // track 1 is gone
        assertNull(vb.bestText(1))
    }

    @Test fun `Turkish format filter rejects noisy reads after grace window`() {
        // Default constructor uses TURKISH_PLATE_FORMAT and graceReadings=2.
        // First 2 reads bypass the filter; from the 3rd onward bad reads are dropped.
        val vb = PlateVoteBook(minVotes = 3)
        assertTrue("grace #1", vb.record(1, "06KE1453"))
        assertTrue("grace #2", vb.record(1, "06KE1453"))
        // After grace: a Turkish-shaped read passes...
        assertTrue("post-grace good", vb.record(1, "06KE1453"))
        // ...but a non-Turkish-shaped read is rejected.
        val accepted = vb.record(1, "XYZZZZZZ")
        assertEquals("noisy read should be rejected", false, accepted)

        val voted = vb.bestText(1)!!
        assertEquals("06KE1453", voted.text)
        assertEquals(3, voted.votes)
    }
}
