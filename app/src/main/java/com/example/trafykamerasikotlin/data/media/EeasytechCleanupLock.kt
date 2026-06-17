package com.example.trafykamerasikotlin.data.media

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide serialisation gate for cam-side cleanup HTTP calls on
 * Easytech firmware.
 *
 * **Why this exists**: when the user transitions quickly between Live →
 * Settings → Media → Background → Leave (or kills the app from any tab),
 * each ViewModel fires its own cleanup chain concurrently:
 *
 *   - `EeasytechLiveRepository.exitLive`      → exitrecorder + rec=1
 *   - `EeasytechSettingsRepository.exitSettings` → setting?param=exit + playback?param=exit + rec=1
 *   - `EeasytechMediaRepository.exitPlayback` → playback?param=exit + setting?param=exit + rec=1
 *
 * Without serialisation the cam HTTP server receives a storm of 6-9
 * conflicting `setparamvalue?param=rec&value=1` and `*?param=exit`
 * requests in the same second. Two failure modes observed on real
 * hardware (logcat replay 2026-06-17):
 *
 *   1. `setparamvalue?param=rec&value=1 → "set fail"` repeatedly,
 *      because the cam is still in setting / playback mode from a
 *      concurrent caller — the recording resume never lands, the cam
 *      stays paused.
 *   2. The cam **reboots itself** as Wi-Fi drops (onLost network) and
 *      `scanForDashcams: found 0 dashcam SSID(s)` — the SSID disappears
 *      from the scan, confirming firmware reset.
 *
 * Holding this Mutex across the entire cleanup chain (acquire BEFORE
 * the first probe, release AFTER the final `rec=1`) serialises the
 * three callers. The cam processes each transition deterministically;
 * `rec=1` always lands on a quiescent cam and returns "set success".
 *
 * **Why a separate object**: the three repositories are independent
 * classes constructed per-ViewModel and would otherwise have no shared
 * state. Using a Kotlin `object` with a single `Mutex` field is the
 * cheapest way to give them process-wide coordination without changing
 * the repository constructors.
 */
internal object EeasytechCleanupLock {
    val mutex: Mutex = Mutex()
}
