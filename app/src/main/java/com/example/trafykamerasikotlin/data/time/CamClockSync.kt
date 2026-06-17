package com.example.trafykamerasikotlin.data.time

import android.util.Log
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import java.util.Calendar

/**
 * Pushes the phone's current LOCAL clock to the dashcam's RTC.
 *
 * **Why**: most dashcams ship with a stale RTC (no NTP, weak/missing
 * backup battery), so the SD-card filename timestamps end up wrong by
 * months or years. The OEM apps do this on every connect; we mirror.
 *
 * **What time goes over the wire**: phone's LOCAL time (default
 * timezone), because the cam OSD draws local wallclock onto recordings.
 * UTC would produce off-by-N-hours filenames and a misleading OSD.
 *
 * **UX**: fire-and-forget from [com.example.trafykamerasikotlin.ui.viewmodel.DashcamViewModel]'s
 * handshake-success path. Non-fatal — we never block the UI on it and
 * silently swallow failures (the cam reaching us at all is enough to
 * keep the user productive; the wrong-timestamp problem is a polish item).
 *
 * **Per-chipset endpoint** (Phase 1 covers the two we have user devices
 * for; GeneralPlus and Allwinner come in a follow-up because their time-set
 * RPCs ride over GPSOCKET / RTP2P respectively and need separate plumbing):
 *  - Easytech: `GET /app/setsystime?date=YYYYMMDDhhmmss`
 *    (verified against the OEM `EeasytechProtocol` from golook-jadx —
 *    Chinese log line `同步时间` in the decompiled source)
 *  - HiDVR  (HiSilicon): `GET /cgi-bin/hisnet/setsystime.cgi?-time=YYYYMMDDhhmmss`
 *    (verified against `HiDvrProtocol.setSystemTime` in golook-jadx)
 */
object CamClockSync {

    private const val TAG = "Trafy.CamClockSync"

    /**
     * Push phone-local clock to the cam. Returns `true` when the cam
     * acknowledged the set; `false` when the chipset has no Phase-1
     * implementation or the HTTP call failed (callers shouldn't gate UI
     * on this — log + continue).
     */
    suspend fun syncFromPhoneClock(protocol: ChipsetProtocol): Boolean {
        val stamp = phoneNowStamp()
        return when (protocol) {
            ChipsetProtocol.EEASYTECH -> {
                val url = "http://${protocol.deviceIp}/app/setsystime?date=$stamp"
                Log.i(TAG, "Easytech setsystime → $stamp")
                DashcamHttpClient.probe(url).also {
                    Log.i(TAG, "Easytech setsystime ack=$it")
                }
            }
            ChipsetProtocol.HI_DVR -> {
                val url = "http://${protocol.deviceIp}/cgi-bin/hisnet/setsystime.cgi?-time=$stamp"
                Log.i(TAG, "HiDvr setsystime.cgi → $stamp")
                DashcamHttpClient.probe(url).also {
                    Log.i(TAG, "HiDvr setsystime.cgi ack=$it")
                }
            }
            else -> {
                Log.d(TAG, "no clock-sync impl for ${protocol.displayName} yet (Phase 2)")
                false
            }
        }
    }

    /**
     * Returns the phone's current local time as a 14-char `YYYYMMDDhhmmss`
     * string — the exact form both Easytech and HiDVR expect.
     */
    private fun phoneNowStamp(): String {
        val now = Calendar.getInstance()
        return "%04d%02d%02d%02d%02d%02d".format(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND),
        )
    }
}
