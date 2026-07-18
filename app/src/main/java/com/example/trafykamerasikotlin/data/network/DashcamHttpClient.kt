package com.example.trafykamerasikotlin.data.network

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit

object DashcamHttpClient {

    private const val TAG = "Trafy.HttpClient"

    @Volatile
    private var client: OkHttpClient = buildClient(socketFactory = null)

    /**
     * Binds all subsequent HTTP requests to the given [network].
     * Pass null to restore the default (unbound) OkHttpClient.
     * Called by DashcamViewModel after a successful WiFi connection.
     */
    fun bindToNetwork(network: Network?) {
        client = buildClient(network?.socketFactory)
        Log.i(TAG, "bindToNetwork: ${if (network != null) "bound to $network" else "unbound (default)"}")
    }

    private fun buildClient(socketFactory: SocketFactory?): OkHttpClient =
        OkHttpClient.Builder()
            .apply { if (socketFactory != null) socketFactory(socketFactory) }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)   // longer for file downloads
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

    /**
     * Performs a GET with a tight per-call timeout — for "is this URL even
     * reachable?" style probes where we don't want to wait the default
     * 30-second read timeout on each miss.
     *
     * Used by the GPS sidecar discovery code which probes a handful of
     * candidate URLs back-to-back; with the default timeout, 5 candidate
     * misses become a 2.5-minute UX freeze.
     */
    suspend fun getQuick(url: String, timeoutMs: Long = 2_500L): String? =
        withContext(Dispatchers.IO) {
            val short = client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            try {
                val request = Request.Builder().url(url).build()
                short.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Performs a GET and returns the response body as a String,
     * or null if the request fails or status is not 2xx.
     */
    suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "GET $url")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string()
                Log.d(TAG, "  → HTTP $code | body=${body?.take(200)}")
                if (response.isSuccessful) body else {
                    Log.w(TAG, "  → Non-2xx response for GET $url: HTTP $code")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "  → EXCEPTION for GET $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Returns true if a GET to the URL yields a 2xx response.
     */
    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "PROBE $url")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string()
                Log.d(TAG, "  → HTTP $code | body=${body?.take(200)}")
                val success = response.isSuccessful
                if (!success) Log.w(TAG, "  → Non-2xx for PROBE $url: HTTP $code")
                success
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation must propagate. Catching it as a
            // generic Exception (the previous behaviour) let cancelled
            // cleanup chains keep firing more probes after their parent
            // coroutine had already been torn down, contributing to the
            // cam-firmware reboot during rapid app-kill flows.
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "  → EXCEPTION for PROBE $url: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * A probe for a request that intentionally tears down its own connection
     * on success — e.g. changing the Wi-Fi AP credentials makes the cam restart
     * the AP before it can answer, so the socket is aborted mid-response.
     *
     * Returns true if we either got a real response OR the connection was
     * established and then aborted/reset mid-flight (the request reached the
     * cam and it acted on it — the AP restart is the *expected* success signal).
     * Returns false only when we never reached the cam at all (connect refused,
     * timeout, or the bound network has already vanished — i.e. EPERM binding),
     * so a genuinely lost request still surfaces as a failure.
     */
    suspend fun probeExpectingReset(url: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "PROBE(reset-expected) $url")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "  → HTTP ${response.code} (answered before reset)")
                true
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            // Connection was up and then torn down while sending/reading the
            // response → the cam got the request and restarted its AP.
            val abortedAfterSend = e is java.net.SocketException &&
                (msg.contains("abort") || msg.contains("reset") ||
                    msg.contains("broken pipe") || msg.contains("epipe"))
            Log.i(
                TAG,
                "  → ${e.javaClass.simpleName}: ${e.message} → " +
                    if (abortedAfterSend) "SUCCESS (AP restarted mid-response)" else "FAILURE (never reached cam)"
            )
            abortedAfterSend
        }
    }

    /**
     * Opens a streaming GET for a file download.
     * Caller is responsible for closing the returned ResponseBody.
     * Returns null on failure.
     */
    suspend fun openStream(url: String): ResponseBody? = withContext(Dispatchers.IO) {
        Log.d(TAG, "STREAM $url")
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body else {
                Log.w(TAG, "  → HTTP ${response.code} for STREAM $url")
                response.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "  → EXCEPTION for STREAM $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

}
