package com.example.trafykamerasikotlin.data.vision.voting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateVoteBookTest {

    @Test fun `no vote until minVotes reached`() {
        val vb = PlateVoteBook(minVotes = 3)
        vb.record(trackId = 1, text = "34ABC123")
        vb.record(trackId = 1, text = "34ABC123")
        assertNull("should return null before minVotes", vb.bestText(1))
        vb.record(trackId = 1, text = "34ABC123")
        assertNotNull("should return a result at minVotes", vb.bestText(1))
    }

    @Test fun `majority vote wins over noise`() {
        val vb = PlateVoteBook(minVotes = 3)
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
        val vb = PlateVoteBook(minVotes = 3)
        vb.record(1, "34ABC")   // slot 5..9 pad
        vb.record(1, "34ABC")
        vb.record(1, "34ABC")
        val voted = vb.bestText(1)!!
        assertEquals("34ABC", voted.text)
    }

    @Test fun `unrelated tracks don't cross-contaminate`() {
        val vb = PlateVoteBook(minVotes = 3)
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
        val vb = PlateVoteBook(minVotes = 3)
        vb.record(1, "34ABC")
        vb.record(1, "34ABC")
        vb.record(1, "34ABC")
        vb.prune(setOf(2))  // track 1 is gone
        assertNull(vb.bestText(1))
    }
}
