package com.example.trafykamerasikotlin.data.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Opt-out toggle for automatic crash reporting. Default **ON** — reports
 * contain only technical diagnostics (stack trace, device model, app log
 * excerpt), never location/GPS data, and are the only visibility we have
 * into failures on sideloaded installs. The user can disable it from
 * Settings at any time.
 *
 * Mirrors the `GpsLoggingPreferences` shape so other call sites read it the
 * same way.
 */
object CrashReportingPreferences {
    private const val PREFS_NAME = "trafy_ui_prefs"
    private const val KEY = "crash_reporting_enabled"
    const val DEFAULT = true

    @Volatile private var stateFlow: MutableStateFlow<Boolean>? = null

    private fun ensureFlow(context: Context): MutableStateFlow<Boolean> {
        stateFlow?.let { return it }
        return synchronized(this) {
            stateFlow ?: run {
                val initial = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, DEFAULT)
                MutableStateFlow(initial).also { stateFlow = it }
            }
        }
    }

    fun state(context: Context): StateFlow<Boolean> = ensureFlow(context)
    fun get(context: Context): Boolean = ensureFlow(context).value
    fun set(context: Context, enabled: Boolean) {
        val flow = ensureFlow(context)
        if (flow.value == enabled) return
        flow.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}

@Composable
fun rememberCrashReportingPreference(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val flow = remember { CrashReportingPreferences.state(context) }
    val current by flow.collectAsStateWithLifecycle()
    val setter = remember(context) { { v: Boolean -> CrashReportingPreferences.set(context, v) } }
    return current to setter
}