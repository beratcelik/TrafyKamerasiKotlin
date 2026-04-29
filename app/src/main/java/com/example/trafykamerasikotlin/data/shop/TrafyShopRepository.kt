package com.example.trafykamerasikotlin.data.shop

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single Trafy product entry. Mirrors the JSON returned by `/api/products`
 * with all the future-extensible fields nullable so the app gracefully
 * handles versions of the API that don't yet expose images / description /
 * features.
 */
data class TrafyProduct(
    val slug: String,
    val name: String,
    val priceTl: Int,
    val inStock: Boolean,
    val imageUrl: String? = null,
    val description: String? = null,
    val features: List<String> = emptyList(),
) {
    /** Public detail page (kept for any "open in browser" deep link). */
    val publicUrl: String get() = "$BASE_URL/products/$slug"

    /** Direct checkout URL for in-stock products. */
    val checkoutUrl: String get() = "$BASE_URL/checkout.html?product=$slug"

    /** Pre-order URL for out-of-stock products — same slug parameter. */
    val preorderUrl: String get() = "$BASE_URL/on-siparis.html?product=$slug"

    /**
     * URL the "Buy / Pre-order" button should open. Branches purely on
     * [inStock] so the call site doesn't need its own logic.
     */
    val purchaseUrl: String get() = if (inStock) checkoutUrl else preorderUrl

    companion object {
        const val BASE_URL = "https://trafy.tr"
    }
}

/**
 * Fetches products from `https://trafy.tr/api/products`. The endpoint
 * currently returns a flat array of `{slug, name, price, priceTL, inStock}`
 * objects; we tolerate (and prefer when present) `imageUrl`, `description`,
 * `features[]`. If the API response doesn't have a field, the app falls
 * back to defaults — adding fields server-side is a non-breaking change.
 */
class TrafyShopRepository(private val context: Context) {

    suspend fun fetchProducts(): List<TrafyProduct> = withContext(Dispatchers.IO) {
        Log.i(TAG, "fetchProducts: GET $URL")
        // Pin the request to an internet-capable network (cellular in the
        // typical case where the phone is also bound to the dashcam's
        // no-internet hotspot). Falls back to the system default if no
        // internet network is identifiable.
        val net = TrafyInternetRouting.internetNetwork(context)
        val connection = if (net != null) net.openConnection(URL(URL))
                         else URL(URL).openConnection()
        val conn = (connection as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout    = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetchProducts: HTTP $code")
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseProducts(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProducts(body: String): List<TrafyProduct> {
        val arr = try { JSONArray(body) } catch (e: Exception) {
            Log.w(TAG, "fetchProducts: not a JSON array — ${e.message}")
            return emptyList()
        }
        val out = ArrayList<TrafyProduct>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
            // Defensive filter — "deneme" is the API team's dev/test product;
            // their own description even says "mobil mağazada gösterilmemelidir".
            // Skip it here so we don't depend on server-side gating.
            if (slug.equals("deneme", ignoreCase = true)) continue
            val featuresArr = obj.optJSONArray("features")
            val features = if (featuresArr != null) {
                List(featuresArr.length()) { featuresArr.optString(it) }.filter { it.isNotBlank() }
            } else emptyList()
            out += TrafyProduct(
                slug        = slug,
                name        = obj.optString("name", slug),
                priceTl     = obj.optInt("priceTL", 0),
                inStock     = obj.optBoolean("inStock", true),
                imageUrl    = obj.optString("imageUrl").takeIf { it.isNotBlank() },
                description = obj.optString("description").takeIf { it.isNotBlank() },
                features    = features,
            )
        }
        Log.i(TAG, "fetchProducts: parsed ${out.size} products")
        return out
    }

    companion object {
        private const val TAG = "Trafy.Shop"
        private const val URL = "${TrafyProduct.BASE_URL}/api/products"
    }
}
