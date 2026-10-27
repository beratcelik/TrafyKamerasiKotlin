package tr.trafy.kamera.data.wifi

import android.content.Context

/**
 * SSIDs proven to belong to a dashcam: every network a handshake succeeded on,
 * and every name the user gave the hotspot in Settings → Wi-Fi.
 *
 * Why: [DashcamWifiManager] recognises cams by factory SSID keywords ("dvr",
 * "cardv", …), but the app's own Wi-Fi dialog lets the user rename the hotspot
 * to anything. After a rename the scan and the "already on cam Wi-Fi" fast path
 * both skipped the camera, and the app reported "no dashcam found" for good.
 *
 * Plain SharedPreferences (`trafy_known_cams`), like [DashcamWifiCredentials].
 */
object KnownDashcamSsids {
    private const val PREFS_NAME = "trafy_known_cams"
    private const val KEY_SSIDS  = "ssids"

    /** Records [ssid] as a dashcam hotspot. */
    fun remember(context: Context, ssid: String) {
        if (ssid.isBlank()) return
        val known = all(context)
        if (ssid in known) return
        prefs(context).edit().putStringSet(KEY_SSIDS, known + ssid).apply()
    }

    fun contains(context: Context, ssid: String): Boolean = ssid in all(context)

    private fun all(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SSIDS, null) ?: emptySet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
