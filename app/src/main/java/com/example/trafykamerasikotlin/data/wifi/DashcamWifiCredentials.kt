package com.example.trafykamerasikotlin.data.wifi

import android.content.Context

/**
 * Remembers user-set dashcam hotspot passwords, keyed by SSID.
 *
 * Why: [DashcamWifiManager] otherwise only ever connects with the factory
 * default passphrase ("12345678"). The moment a user changes the hotspot
 * password (Settings → Wi-Fi), the app could never auto-reconnect and was
 * effectively locked out of the camera until a factory reset. We now persist
 * whatever password the user sets and hand it back to the connect path.
 *
 * Stored in plain SharedPreferences (`trafy_wifi_creds`), matching the app's
 * other preference stores (e.g. [LastConnectedDevicePreferences]). The value is
 * a local AP passphrase for an internet-less hotspot, not an account secret, so
 * plaintext prefs are an acceptable trade-off for zero extra dependencies.
 */
object DashcamWifiCredentials {
    private const val PREFS_NAME  = "trafy_wifi_creds"
    private const val KEY_PREFIX  = "pw_"   // per-SSID password entry

    /** Persists [password] for [ssid] (called after a successful Wi-Fi change). */
    fun remember(context: Context, ssid: String, password: String) {
        if (ssid.isBlank() || password.isBlank()) return
        prefs(context).edit().putString(KEY_PREFIX + ssid, password).apply()
    }

    /** The remembered password for [ssid], or null to fall back to the default. */
    fun passwordFor(context: Context, ssid: String): String? =
        prefs(context).getString(KEY_PREFIX + ssid, null)

    /** Drops the stored password for [ssid] (e.g. after a factory reset, or once
     *  the default password is confirmed working again). */
    fun forget(context: Context, ssid: String) {
        prefs(context).edit().remove(KEY_PREFIX + ssid).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
