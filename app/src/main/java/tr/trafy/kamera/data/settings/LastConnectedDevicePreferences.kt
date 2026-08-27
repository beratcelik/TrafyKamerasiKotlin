package tr.trafy.kamera.data.settings

import android.content.Context
import tr.trafy.kamera.data.model.DeviceInfo
import tr.trafy.kamera.data.model.TrafyModelIdentifier

/**
 * Remembers the most recently connected dashcam so bug/crash reports can name
 * the camera even when the phone is no longer on its Wi-Fi hotspot — the usual
 * case, since feedback is sent over cellular and crashes fire from a dying
 * process that may already be disconnected.
 *
 * Updated on every successful handshake ([save]); the stored record is
 * therefore live when connected and last-known otherwise. Plain
 * SharedPreferences (`trafy_ui_prefs`), mirroring [GpsLoggingPreferences].
 */
object LastConnectedDevicePreferences {
    private const val PREFS_NAME = "trafy_ui_prefs"
    private const val KEY_MODEL    = "last_cam_model"     // resolved Trafy marketing name (best label)
    private const val KEY_CHIPSET  = "last_cam_chipset"   // chipset family display name
    private const val KEY_FIRMWARE = "last_cam_firmware"  // firmware / software version

    /** Persists the identifying attributes of the connected [device]. */
    fun save(context: Context, device: DeviceInfo) {
        // Resolved marketing name ("Trafy Uno Pro"), falling back to the raw
        // model string, then the SSID — always the most human label we have.
        val modelName = TrafyModelIdentifier.displayName(
            device,
            fallback = device.model ?: device.ssid ?: device.protocol.displayName,
        )
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, modelName)
            .putString(KEY_CHIPSET, device.protocol.displayName)
            .putString(KEY_FIRMWARE, device.softwareVersion)
            .apply()
    }

    fun modelName(context: Context): String? = prefs(context).getString(KEY_MODEL, null)
    fun chipset(context: Context): String? = prefs(context).getString(KEY_CHIPSET, null)
    fun firmware(context: Context): String? = prefs(context).getString(KEY_FIRMWARE, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
