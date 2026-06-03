package com.example.trafykamerasikotlin.data.sensors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compose helper: spin up a [LiveSensorProvider] for the duration of the
 * Composable's life, collect its [LiveSensorData] flow, return as State.
 * Caller can just read `sensorData.value` in a `Canvas` lambda.
 *
 * The provider only listens while the Composable is active — leaving the
 * screen detaches the GPS listener and the rotation-vector sensor so it
 * stops draining battery.
 */
@Composable
fun rememberLiveSensorData(): State<LiveSensorData> {
    val context = LocalContext.current
    val provider = remember(context) { LiveSensorProvider(context.applicationContext) }
    DisposableEffect(provider) {
        provider.start()
        onDispose { provider.stop() }
    }
    return provider.data.collectAsStateWithLifecycle()
}
