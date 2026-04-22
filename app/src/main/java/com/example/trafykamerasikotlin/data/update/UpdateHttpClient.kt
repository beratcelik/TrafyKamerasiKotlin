package com.example.trafykamerasikotlin.data.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client dedicated to update-server traffic.
 *
 * The main [com.example.trafykamerasikotlin.data.network.DashcamHttpClient] gets bound to
 * the dashcam's Wi-Fi network (no internet) while the user is connected to the camera.
 * This one always uses the default network so update checks work regardless of dashcam state.
 */
object UpdateHttpClient {

    private const val TAG = "Trafy.UpdateHttp"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getString(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "getString $url → HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getString $url failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Streams a download to [destination]. Reports progress as (bytesSoFar, totalBytes).
     * Total is -1 when the server omits Content-Length.
     * Returns true on success, false on any failure (partial file is deleted).
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "download $url → HTTP ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                val total = body.contentLength()
                destination.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        var soFar = 0L
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            soFar += read
                            onProgress(soFar, total)
                            read = input.read(buffer)
                        }
                        output.flush()
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "download $url failed: ${e.javaClass.simpleName}: ${e.message}")
            if (destination.exists()) destination.delete()
            false
        }
    }
}
