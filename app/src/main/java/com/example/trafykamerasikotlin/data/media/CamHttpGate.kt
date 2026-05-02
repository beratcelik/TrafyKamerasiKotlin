package com.example.trafykamerasikotlin.data.media

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide single-flight gate for any HTTP call to the dashcam.
 *
 * Why: G3518-class HiDVR firmwares can only handle one or two concurrent
 * HTTP connections. When multiple thumbnail extractions stream MP4s at
 * once, the cam's webserver stops accepting new connections — even simple
 * control calls (`getdirfilelist.cgi`, `workmodecmd.cgi`) start failing
 * with `ConnectException` or empty bodies. Result: refreshing the Media
 * tab while thumbnails are loading shows an empty page.
 *
 * Solution: serialise everything that talks to the cam through this
 * mutex. Listing calls (~100 ms) wait at most one in-flight thumbnail
 * extraction, then have exclusive access. Subsequent thumbnail jobs
 * queue back up after the listing finishes.
 *
 * Generation counter: bumped by [MediaViewModel.load] before every
 * fresh listing pass. Coil thumbnail fetchers capture the generation
 * when they start and bail out at the next check if it has changed —
 * that drains a queue of stale extractions immediately when the user
 * navigates back into Media, so the new listing isn't stuck behind
 * 8 × MMR timeouts.
 *
 * Note: this only mutexes the *cam HTTP* side — internet calls to
 * trafy.tr or local file reads aren't gated.
 */
object CamHttpGate {
    val mutex = Mutex()

    private val gen = AtomicInteger(0)
    fun currentGeneration(): Int = gen.get()
    fun bumpGeneration(): Int = gen.incrementAndGet()
}
