package com.example.trafykamerasikotlin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.trafykamerasikotlin.data.sensors.GpsLogger
import com.example.trafykamerasikotlin.ui.navigation.AppNavigation
import com.example.trafykamerasikotlin.ui.theme.TrafyKamerasiKotlinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrafyKamerasiKotlinTheme {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Phase 1: foreground-only GPS logging. Idempotent — no-op if the
        // user hasn't opted in or the phone isn't on a dashcam Wi-Fi.
        GpsLogger.startIfEnabled(applicationContext)
    }

    override fun onPause() {
        // Releasing the GPS listener saves battery the moment the user
        // backgrounds the app or switches to another activity. Phase 2
        // (a foreground service) is what would survive this.
        GpsLogger.stop()
        super.onPause()
    }
}
