package tr.trafy.kamera.data.settings

import android.content.Context
import androidx.annotation.StringRes
import tr.trafy.kamera.R

/**
 * Localized labels for MStar / Hime3 camera settings (Trafy Tres).
 *
 * Menu keys are the full `Camera.Menu.*` ids from cammenu.xml; option values
 * are the raw `<item id>` strings. Reuses `hidvr_*` / `eeasy_*` resources for
 * shared vocabulary (On / Off / Low / Medium / High, minutes/hours formats,
 * Wi-Fi / Format / Reset / About action titles) so we don't duplicate strings.
 *
 * The displayed labels reproduce what the OEM Waycam app shows for this device
 * (verified against its logcat: `entries=[kapat, düşük, orta, yüksek]`,
 * `[1dak, 3dak, 5dak]`, etc.) rather than the raw English text the firmware
 * bakes into cammenu.xml.
 *
 * Reference: MstarCapabilityManager.java / MstarDevConst.java in golook-jadx.
 */
object MstarTranslations {

    @StringRes
    private val TITLE_RES = mapOf(
        "Camera.Menu.VideoRes"       to R.string.hidvr_title_mediamode,
        "Camera.Menu.EncodeType"     to R.string.hidvr_title_enc_payload_type,
        "Camera.Menu.RecordWithAudio" to R.string.hidvr_title_audio,
        "Camera.Menu.VideoClipTime"  to R.string.hidvr_title_rec_split_time,
        "Camera.Menu.GSensor"        to R.string.hidvr_title_gsr_sensitivity,
        "Camera.Menu.ParkingMonitor" to R.string.mstar_title_parking_monitor,
        "Camera.Menu.Timelapse"      to R.string.mstar_title_timelapse,
        "Camera.Menu.BatProtect"     to R.string.hidvr_title_low_power_protect,
        "Camera.Menu.VoiceSwitch"    to R.string.eeasy_title_speaker,
        "Camera.Menu.Flicker"        to R.string.eeasy_title_light_fre,
        "Camera.Menu.UpsideDown"     to R.string.mstar_title_flip_mirror,
        "Camera.Menu.InUpsideDown"   to R.string.mstar_title_in_flip,
        // Action sentinel keys shared with the HiDvr settings flow.
        "getwifi.cgi?"        to R.string.hidvr_title_wifi_settings,
        "format"              to R.string.hidvr_title_format,
        "reset.cgi?"          to R.string.hidvr_title_reset,
        "getdeviceattr.cgi?"  to R.string.hidvr_title_about_camera,
    )

    /** Fuse-box caveat — only relevant when the cam is hardwired (parking power). */
    @StringRes
    private val DESC_RES = mapOf(
        "Camera.Menu.ParkingMonitor" to R.string.mstar_desc_fuse_box,
        "Camera.Menu.Timelapse"      to R.string.mstar_desc_fuse_box,
        "Camera.Menu.BatProtect"     to R.string.mstar_desc_fuse_box,
    )

    /** Generic on/off/level vocabulary — case-folded so ON/on/On all map. */
    @StringRes
    private val GENERIC_OPTION_RES = mapOf(
        "on"     to R.string.hidvr_opt_on,
        "off"    to R.string.hidvr_opt_off,
        "level0" to R.string.hidvr_opt_low,
        "level1" to R.string.hidvr_opt_medium,
        "level2" to R.string.hidvr_opt_high,
        "low"    to R.string.hidvr_opt_low,
        "middle" to R.string.hidvr_opt_medium,
        "high"   to R.string.hidvr_opt_high,
    )

    /**
     * Battery-protection levels carry a voltage rather than a low/med/high
     * meaning — locale-neutral, so returned as literals.
     */
    private val BAT_PROTECT_VOLTAGE = mapOf(
        "LEVEL0" to "11.8 V",
        "LEVEL1" to "12.0 V",
        "LEVEL2" to "12.2 V",
    )

    /** Locale-neutral labels (codecs / frequencies). */
    private val NEUTRAL_OPTIONS = mapOf(
        "H264" to "H.264", "H265" to "H.265",
        "50HZ" to "50 Hz", "60HZ" to "60 Hz",
    )

    private val MIN_ID_REGEX  = Regex("""^(\d+)\s*MIN$""", RegexOption.IGNORE_CASE)
    private val HOUR_ID_REGEX = Regex("""^(\d+)\s*H$""", RegexOption.IGNORE_CASE)

    // ── Public API ─────────────────────────────────────────────────────────

    fun title(ctx: Context, key: String, fallback: String = key): String =
        TITLE_RES[key]?.let { ctx.getString(it) } ?: fallback

    fun description(ctx: Context, key: String): String? =
        DESC_RES[key]?.let { ctx.getString(it) }

    /**
     * Localized label for an option value.
     * Priority: battery voltage → generic (on/off/level) → minutes/hours →
     * neutral (codec/Hz) → fallback (the raw cammenu text).
     */
    fun optionLabel(ctx: Context, menuKey: String, value: String, fallback: String = value): String {
        if (menuKey == "Camera.Menu.BatProtect") {
            BAT_PROTECT_VOLTAGE[value.uppercase()]?.let { return it }
        }
        GENERIC_OPTION_RES[value.lowercase()]?.let { return ctx.getString(it) }
        MIN_ID_REGEX.matchEntire(value)?.let {
            return ctx.getString(R.string.hidvr_opt_minutes_fmt, it.groupValues[1].toInt())
        }
        HOUR_ID_REGEX.matchEntire(value)?.let {
            return ctx.getString(R.string.hidvr_opt_hours_fmt, it.groupValues[1].toInt())
        }
        NEUTRAL_OPTIONS[value.uppercase()]?.let { return it }
        return fallback
    }
}
