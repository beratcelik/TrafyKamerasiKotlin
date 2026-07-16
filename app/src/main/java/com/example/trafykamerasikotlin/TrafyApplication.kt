package com.example.trafykamerasikotlin

import android.app.Application
import com.example.trafykamerasikotlin.data.report.CrashHandler
import com.example.trafykamerasikotlin.data.report.ReportQueue

/**
 * Custom Application: installs the crash-reporting hook before any Activity
 * code runs, so even crashes during MainActivity.onCreate are captured.
 */
class TrafyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Wrap (never replace) the platform default handler: CrashHandler
        // chains to it in `finally`, keeping the OS crash flow intact.
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
        // Queue hygiene (orphan .tmp files, retention, file cap) off the main
        // thread — cold start shouldn't pay for a directory listing.
        Thread { ReportQueue.sweep(this) }.start()
    }
}