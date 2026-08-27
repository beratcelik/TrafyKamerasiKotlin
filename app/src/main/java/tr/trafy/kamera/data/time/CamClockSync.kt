package tr.trafy.kamera.data.time

import android.util.Log
import tr.trafy.kamera.data.generalplus.GeneralplusProtocol
import tr.trafy.kamera.data.generalplus.GeneralplusSession
import tr.trafy.kamera.data.model.ChipsetProtocol
import tr.trafy.kamera.data.network.DashcamHttpClient
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
 * **UX**: fire-and-forget from [tr.trafy.kamera.ui.viewmodel.DashcamViewModel]'s
 * handshake-success path. Non-fatal — we never block the UI on it and
 * silently swallow failures (the cam reaching us at all is enough to
 * keep the user productive; the wrong-timestamp problem is a polish item).
 *
 * **Per-chipset endpoint**:
 *  - Easytech: `GET /app/setsystime?date=YYYYMMDDhhmmss`
 *    (verified against the OEM `EeasytechProtocol` from golook-jadx —
 *    Chinese log line `同步时间` in the decompiled source)
 *  - HiDVR  (HiSilicon): `GET /cgi-bin/hisnet/setsystime.cgi?-time=YYYYMMDDhhmmss`
 *    (verified against `HiDvrProtocol.setSystemTime` in golook-jadx)
 *  - GeneralPlus (Trafy Uno): GPSOCKET binary over TCP/8081, vendor cmd
 *    `MODE_VENDOR / cmdId=0x00` + `"GPVENDOR"` sub-signature + 7-byte
 *    `[year16 LE, month, day, hour, minute, second]` payload. Verified
 *    against a viidure PCAP captured 2026-06-17 (frames 821 → 827).
 *  - Allwinner V853: no impl yet (no OEM source for CloudSpirit), skips.
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
            ChipsetProtocol.GENERALPLUS -> {
                // Trafy Uno — GPSOCKET binary over TCP/8081. Command shape
                // reverse-engineered from a viidure PCAP (2026-06-17 frame
                // 821: cam echoes the same 17-byte vendor payload back with
                // TYPE_ACK at frame 827). See [GeneralplusProtocol.buildSetSystemTime]
                // for the on-the-wire breakdown.
                Log.i(TAG, "GeneralPlus setSystemTime → $stamp (GPVENDOR)")
                syncGeneralplus()
            }
            ChipsetProtocol.ALLWINNER_V853 -> {
                // Trafy Dos Internet — relay envelope over TCP/8000. The
                // sub-command name is `settime`, reverse-engineered from
                // CloudSpirit v2.1.14 (com.plink.cloudspiritgo, libapp.so —
                // Dart fn `syncTimeZone`). Payload: LOCAL epoch seconds + tz
                // offset in seconds. See AllwinnerSession.setSystemTime for
                // the local-vs-UTC discovery (cam was double-applying tz).
                Log.i(TAG, "Allwinner V853 settime → (local epoch)")
                syncAllwinner()
            }
            else -> {
                Log.d(TAG, "no clock-sync impl for ${protocol.displayName} yet (Phase 2)")
                false
            }
        }
    }

    /**
     * Routes the settime relay through the live [tr.trafy.kamera.data.allwinner.AllwinnerSessionHolder]
     * so we share the TCP session that already holds login + bondlist state.
     * Returns false if no session is open (handshake hasn't finished yet) —
     * caller should retry after the next handshake.
     */
    private suspend fun syncAllwinner(): Boolean {
        val session = tr.trafy.kamera.data.allwinner.AllwinnerSessionHolder.current
        if (session == null) {
            Log.w(TAG, "Allwinner settime: no live session, skipping")
            return false
        }
        return runCatching { session.setSystemTime() }
            .onFailure { Log.w(TAG, "Allwinner settime threw: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Sends the GPVENDOR set-system-time packet over a one-shot
     * GeneralplusSession. The session handles auth handshake; we just hand
     * the pre-built vendor command to `sendPacket` and wait for the cam's
     * ACK (TYPE_ACK = 0x02, vendor mode echo).
     */
    private suspend fun syncGeneralplus(): Boolean {
        val now = Calendar.getInstance()
        val packet = GeneralplusProtocol.buildSetSystemTime(
            cmdIdx = 0x00,
            year   = now.get(Calendar.YEAR),
            month  = now.get(Calendar.MONTH) + 1,
            day    = now.get(Calendar.DAY_OF_MONTH),
            hour   = now.get(Calendar.HOUR_OF_DAY),
            minute = now.get(Calendar.MINUTE),
            second = now.get(Calendar.SECOND),
        )
        val ack = GeneralplusSession.withSession { _, sendPacket, receive ->
            sendPacket(packet)
            // Cam ACKs with mode=VENDOR (0xFF), cmdId=0x00, type=ACK.
            // `receive` matches by cmdId — pass 0x00 to wait for the vendor ack.
            val resp = receive(0x00)
            val isAck = resp?.isAck == true && resp.mode == GeneralplusProtocol.MODE_VENDOR
            Log.i(TAG, "GeneralPlus setSystemTime ack=$isAck (resp=$resp)")
            isAck
        }
        return ack == true
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
