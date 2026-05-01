package com.example.trafykamerasikotlin.data.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * extraction (capped at 15 s), then have exclusive access. Subsequent
 * thumbnail jobs queue back up after the listing finishes.
 *
 * Note: this only mutexes the *cam HTTP* side — internet calls to
 * trafy.tr or local file reads aren't gated.
 */
object CamHttpGate {
    /** Public so callers can use `CamHttpGate.mutex.withLock { … }` (inline,
     *  supports non-local returns from inside the block). */
    val mutex = Mutex()
}
