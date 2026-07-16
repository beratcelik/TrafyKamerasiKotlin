package com.example.trafykamerasikotlin.data.report

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.shop.TrafyInternetRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal JSON POST client for the reports endpoint. Pins the request to an
 * internet-capable network via [TrafyInternetRouting] — the proven
 * `TrafyShopRepository` pattern — so uploads go out over cellular even while
 * the app is bound to the dashcam's no-internet Wi-Fi hotspot. (Deliberately
 * NOT `UpdateHttpClient`/OkHttp, which don't bind to the internet network.)
 */
object ReportHttpClient {
    private const val TAG = "Trafy.ReportHttp"

    /**
     * Maps HTTP outcomes onto the queue policy:
     * - [Accepted] (2xx) and [PermanentReject] (400/413 — the server will
     *   never take this payload) → caller deletes the queued file.
     * - [RateLimited] (429) and [NetworkError] (5xx, unexpected codes,
     *   exceptions) → caller keeps the file and stops flushing for now.
     */
    sealed class Result {
        data object Accepted : Result()
        data object PermanentReject : Result()
        data object RateLimited : Result()
        data object NetworkError : Result()
    }

    suspend fun post(context: Context, url: String, jsonBody: String): Result = withContext(Dispatchers.IO) {
        try {
            val net = TrafyInternetRouting.internetNetwork(context)
            val connection = if (net != null) net.openConnection(URL(url))
                             else URL(url).openConnection()
            val conn = (connection as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout    = 10_000
                doOutput       = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                // Drain the body so the connection closes cleanly.
                runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
                }
                Log.i(TAG, "POST → HTTP $code")
                when {
                    code in 200..299           -> Result.Accepted
                    code == 400 || code == 413 -> Result.PermanentReject
                    code == 429                -> Result.RateLimited
                    // 5xx and anything unexpected: transient — retry later.
                    else                       -> Result.NetworkError
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            // Never map coroutine cancellation to a queue decision.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "POST failed: ${e.message}")
            Result.NetworkError
        }
    }
}