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
 * Single process-wide source of truth for the user's AI-overlay toggle.
 *
 * Backed by SharedPreferences so the choice persists across app restarts.
 * All toggle locations (Live, GP playback, Allwinner playback, HiDVR
 * playback, Media browsing burn-in) read and write the same flow, so
 * toggling on one screen is observed on the others without manual sync.
 *
 * Default: ON — most users want plates flagged out of the box.
 */
object AiOverlayPreferences {
    private const val PREFS_NAME = "trafy_ui_prefs"
    private const val KEY = "ai_overlay_enabled"
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

    /** Observable current value. Hot — collectors get the latest immediately. */
    fun state(context: Context): StateFlow<Boolean> = ensureFlow(context)

    /** Synchronous read for one-off non-Compose callers. */
    fun get(context: Context): Boolean = ensureFlow(context).value

    /** Atomic update — no-op if value is unchanged. Persists synchronously-ish via apply(). */
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

/**
 * Compose helper. Returns the current value (Compose-observed via Lifecycle)
 * and a setter that persists + propagates to all other observers.
 *
 * Usage:
 *   val (aiOverlayEnabled, setAiOverlay) = rememberAiOverlayPreference()
 *   Toggle(onClick = { setAiOverlay(!aiOverlayEnabled) })
 */
@Composable
fun rememberAiOverlayPreference(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val flow = remember { AiOverlayPreferences.state(context) }
    val current by flow.collectAsStateWithLifecycle()
    val setter = remember(context) { { v: Boolean -> AiOverlayPreferences.set(context, v) } }
    return current to setter
}
