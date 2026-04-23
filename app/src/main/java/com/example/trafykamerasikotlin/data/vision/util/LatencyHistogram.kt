package com.example.trafykamerasikotlin.data.vision.util

/**
 * Rolling ring buffer of recent inference latencies (ms). Computes p50/p95/avg
 * without retaining full history. Thread-safe via a single monitor — expected
 * call rate is at most display-frame-rate.
 */
class LatencyHistogram(private val capacity: Int = 100) {

    private val buf = IntArray(capacity)
    private var count = 0
    private var head = 0
    private val lock = Any()

    data class Snapshot(
        val samples: Int,
        val p50:     Int,
        val p95:     Int,
        val avg:     Int,
        val min:     Int,
        val max:     Int,
    ) {
        companion object {
            val EMPTY = Snapshot(0, 0, 0, 0, 0, 0)
        }
    }

    fun record(latencyMs: Int) {
        synchronized(lock) {
            buf[head] = latencyMs
            head = (head + 1) % capacity
            if (count < capacity) count++
        }
    }

    fun clear() {
        synchronized(lock) {
            count = 0
            head = 0
        }
    }

    fun snapshot(): Snapshot {
        val copy: IntArray
        synchronized(lock) {
            if (count == 0) return Snapshot.EMPTY
            copy = IntArray(count)
            if (count < capacity) {
                System.arraycopy(buf, 0, copy, 0, count)
            } else {
                // Ring buffer wrapped — the slot at `head` is the oldest.
                System.arraycopy(buf, head, copy, 0, capacity - head)
                System.arraycopy(buf, 0, copy, capacity - head, head)
            }
        }
        copy.sort()
        val n = copy.size
        val sum = copy.sumOf { it.toLong() }
        return Snapshot(
            samples = n,
            p50 = copy[percentileIdx(n, 0.50)],
            p95 = copy[percentileIdx(n, 0.95)],
            avg = (sum / n).toInt(),
            min = copy[0],
            max = copy[n - 1],
        )
    }

    private fun percentileIdx(n: Int, p: Double): Int =
        ((n - 1) * p).toInt().coerceIn(0, n - 1)
}
