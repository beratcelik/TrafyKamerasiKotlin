package com.example.trafykamerasikotlin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
}
