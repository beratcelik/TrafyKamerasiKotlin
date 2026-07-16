package com.example.trafykamerasikotlin.data.report

import android.app.Application
import android.content.Context
import android.os.Build
import com.example.trafykamerasikotlin.data.settings.LastConnectedDevicePreferences
import com.example.trafykamerasikotlin.data.wifi.DashcamWifiManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Builds the JSON body for `POST /api/app/reports` (see docs/mobile-reports-api.md
 * on the trafy.tr side). Plain `org.json.JSONObject` — repo convention, no
 * serialization library needed for one endpoint.
 *
 * Client-side size caps keep the worst-case payload well under the server's
 * 100KB `express.json()` body limit: logs ≤ 48k chars, stackTrace ≤ 12k,
 * description ≤ 4k.
 */
object ReportPayload {
    const val MAX_LOGS_CHARS        = 48_000
    const val MAX_STACK_CHARS       = 12_000
    const val MAX_DESCRIPTION_CHARS = 4_000
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_EMAIL_CHARS = 200
    private const val MAX_DASHCAM_CHARS = 64

    /** Automatic crash report — built inside the dying process, queued to disk. */
    fun crash(context: Context, throwable: Throwable, fingerprint: String, logs: String?): JSONObject =
        base(context, reportType = "crash").apply {
            put("title", DiagnosticsCollector.crashTitle(throwable).take(MAX_TITLE_CHARS))
            put("stackTrace", DiagnosticsCollector.throwableToString(throwable))
            put("fingerprint", fingerprint)
            put("occurrenceCount", 1)
            logs?.let { put("logs", it.take(MAX_LOGS_CHARS)) }
        }

    /**
     * User-submitted feedback. [includeDiagnostics] gates the optional extras
     * (log excerpt + connected dashcam SSID); the fields the API requires on
     * every report (device model, OS/app version, locale) are always present.
     */
    fun feedback(
        context: Context,
        reportType: String,
        description: String,
        contactEmail: String?,
        logs: String?,
        includeDiagnostics: Boolean,
    ): JSONObject =
        base(context, reportType, includeDashcamModel = includeDiagnostics).apply {
            put("description", description.take(MAX_DESCRIPTION_CHARS))
            // Invalid emails are dropped rather than sent: the server 400s the
            // whole report on a malformed contactEmail, and a queued offline
            // report would then be deleted as a permanent reject — silently
            // losing the user's text over a typo'd optional field.
            contactEmail?.trim()
                ?.takeIf { it.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches() }
                ?.let { put("contactEmail", it.take(MAX_EMAIL_CHARS)) }
            if (includeDiagnostics) logs?.let { put("logs", it.take(MAX_LOGS_CHARS)) }
        }

    /**
     * Fields common to every report. Everything is wrapped defensively —
     * this also runs on the crash path, where a second exception would
     * silently lose the report.
     */
    private fun base(
        context: Context,
        reportType: String,
        includeDashcamModel: Boolean = true,
    ): JSONObject {
        val app = context.applicationContext
        val pkgInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = pkgInfo?.versionCode ?: 0
        return JSONObject().apply {
            put("clientReportId", UUID.randomUUID().toString())
            put("platform", "android")
            put("reportType", reportType)
            put("appVersionCode", versionCode)
            put("appVersionName", pkgInfo?.versionName ?: "")
            put("osVersion", "${Build.VERSION.RELEASE ?: "?"} (API ${Build.VERSION.SDK_INT})")
            put("deviceModel", Build.MODEL ?: "")
            put("deviceBrand", Build.BRAND ?: "")
            put("locale", Locale.getDefault().toLanguageTag())
            if (includeDashcamModel) putDashcam(app)
            put("occurredAt", iso8601Now())
        }
    }

    /**
     * Names the connected/last-known camera. Prefers the persisted record
     * ([LastConnectedDevicePreferences], written on every handshake) — it
     * carries the resolved marketing model, chipset and firmware and survives
     * the phone leaving the dashcam hotspot. Falls back to the live SSID when
     * no camera has been connected on this install yet.
     */
    private fun JSONObject.putDashcam(app: Context) {
        val model = LastConnectedDevicePreferences.modelName(app)
        if (model != null) {
            put("dashcamModel", model.take(MAX_DASHCAM_CHARS))
            LastConnectedDevicePreferences.chipset(app)
                ?.let { put("dashcamChipset", it.take(MAX_DASHCAM_CHARS)) }
            LastConnectedDevicePreferences.firmware(app)
                ?.let { put("dashcamFirmware", it.take(MAX_DASHCAM_CHARS)) }
        } else {
            currentDashcamSsid(app)?.let { put("dashcamModel", it.take(MAX_DASHCAM_CHARS)) }
        }
    }

    /** Live SSID — only used before any camera has been connected. */
    private fun currentDashcamSsid(app: Context): String? = runCatching {
        (app as? Application)?.let { DashcamWifiManager(it).getCurrentDashcamSsid() }
    }.getOrNull()

    private fun iso8601Now(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date())
    }
}