package com.example.trafykamerasikotlin.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.ui.theme.ColorBackground
import com.example.trafykamerasikotlin.ui.theme.ColorPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextPrimary
import com.example.trafykamerasikotlin.ui.theme.ColorTextSecondary

/** Package name of the Google Play build that replaces this sideloaded app. */
private const val PLAY_PACKAGE = "tr.trafy.kamera"
private const val PLAY_MARKET_URI = "market://details?id=$PLAY_PACKAGE"
private const val PLAY_WEB_URL =
    "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE"

/**
 * Final-release migration screen: this sideloaded build is end-of-life and the app now
 * lives on Google Play under a different applicationId ([PLAY_PACKAGE]).
 *
 * Because the package name differs, Play cannot update this install in place — the user
 * has to install the Play build as a separate app and then remove this one. The screen
 * therefore has two phases:
 *   1. Play app not installed → "install from Play" call to action.
 *   2. Play app detected      → "you can now remove this old version" + uninstall button.
 *
 * Phase 2 is re-evaluated on every ON_RESUME so returning from the Play Store flips the
 * screen without a relaunch.
 *
 * Soft block: [onContinue] lets the user into the old app for the current session, so a
 * driver mid-trip is never locked out of a working dashcam app.
 */
@Composable
fun MigrationScreen(onContinue: () -> Unit) {
    val context = LocalContext.current

    fun isPlayAppInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PLAY_PACKAGE, 0)
    }.isSuccess

    var playInstalled by remember { mutableStateOf(isPlayAppInstalled()) }

    // Re-check on resume: the user typically leaves for the Play Store and comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) playInstalled = isPlayAppInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text      = stringResource(R.string.migration_title),
            style     = MaterialTheme.typography.headlineSmall,
            color     = ColorTextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text      = stringResource(
                if (playInstalled) R.string.migration_body_installed
                else R.string.migration_body
            ),
            style     = MaterialTheme.typography.bodyMedium,
            color     = ColorTextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        if (playInstalled) {
            Button(
                onClick  = {
                    // ACTION_DELETE shows the system uninstall confirmation; the user
                    // stays in control and can back out.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
            ) {
                Text(stringResource(R.string.migration_uninstall_button))
            }
        } else {
            Button(
                onClick  = {
                    // Prefer the Play app; fall back to the browser when Play is absent
                    // (some devices ship without it, and market:// then has no handler).
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_MARKET_URI))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_WEB_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
            ) {
                Text(stringResource(R.string.migration_install_button))
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(
                text  = stringResource(R.string.migration_continue),
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
