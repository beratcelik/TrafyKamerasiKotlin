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
        } catch (e: Exception) {
            Log.e(TAG, "  → EXCEPTION for PROBE $url: ${e.javaClass.simpleName}: ${e.message}")
            false
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
