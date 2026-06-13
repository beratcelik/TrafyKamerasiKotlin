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
 * Opt-in toggle for the GPS-logging feature. Default **OFF** — recording
 * precise location continuously, even on-device only, is the most sensitive
 * data this app touches; the user must explicitly enable it from Settings.
 *
 * Mirrors the `AiOverlayPreferences` shape so other call sites read it the
 * same way.
 */
object GpsLoggingPreferences {
    private const val PREFS_NAME = "trafy_ui_prefs"
    private const val KEY = "gps_logging_enabled"
    const val DEFAULT = false

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
fun rememberGpsLoggingPreference(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val flow = remember { GpsLoggingPreferences.state(context) }
    val current by flow.collectAsStateWithLifecycle()
    val setter = remember(context) { { v: Boolean -> GpsLoggingPreferences.set(context, v) } }
    return current to setter
}
