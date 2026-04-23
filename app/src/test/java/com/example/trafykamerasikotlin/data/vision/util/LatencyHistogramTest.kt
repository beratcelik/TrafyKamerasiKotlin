package com.example.trafykamerasikotlin.data.vision.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyHistogramTest {

    @Test fun `empty histogram returns EMPTY snapshot`() {
        val h = LatencyHistogram(10)
        assertEquals(LatencyHistogram.Snapshot.EMPTY, h.snapshot())
    }

    @Test fun `single sample has equal p50, p95, avg, min, max`() {
        val h = LatencyHistogram(10)
        h.record(42)
        val s = h.snapshot()
        assertEquals(1, s.samples)
        assertEquals(42, s.p50)
        assertEquals(42, s.p95)
        assertEquals(42, s.avg)
        assertEquals(42, s.min)
        assertEquals(42, s.max)
    }

    @Test fun `statistics across ten samples match expected percentiles`() {
        val h = LatencyHistogram(100)
        (1..10).forEach { h.record(it * 10) }   // 10, 20, …, 100
        val s = h.snapshot()
        assertEquals(10, s.samples)
        // p50 index = floor(9 * 0.5) = 4 → sorted[4] = 50
        assertEquals(50, s.p50)
        // p95 index = floor(9 * 0.95) = 8 → sorted[8] = 90
        assertEquals(90, s.p95)
        assertEquals(55, s.avg)   // (10+20+…+100)/10 = 55
        assertEquals(10, s.min)
        assertEquals(100, s.max)
    }

    @Test fun `ring buffer drops oldest samples past capacity`() {
        val h = LatencyHistogram(5)
        (1..10).forEach { h.record(it) }  // only 6..10 should remain
        val s = h.snapshot()
        assertEquals(5, s.samples)
        assertEquals(6, s.min)
        assertEquals(10, s.max)
        assertEquals(8, s.avg)   // (6+7+8+9+10)/5
    }

    @Test fun `clear resets the snapshot to empty`() {
        val h = LatencyHistogram(10)
        h.record(50)
        h.record(60)
        h.clear()
        assertEquals(LatencyHistogram.Snapshot.EMPTY, h.snapshot())
    }
}
