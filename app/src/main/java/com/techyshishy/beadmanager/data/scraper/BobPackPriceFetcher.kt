package com.techyshishy.beadmanager.data.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fetches price and availability for a Barrel of Beads SKU.
 *
 * Barrel of Beads runs on Shopify. Appending ".json" to any product URL returns a
 * structured JSON payload. The variant's [price] is a decimal string; we multiply
 * by 100 and round to cents. Barrel of Beads uses flat (non-tiered) pricing, so
 * tier2/3/4PriceCents are always null.
 *
 * Availability: [inventoryQuantity] > 0, OR [inventoryPolicy] == "continue" (backorders
 * allowed — the item ships when restocked but can be ordered now).
 */
@Singleton
class BobPackPriceFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : PackPriceFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(url: String): ScrapedPack = withContext(Dispatchers.IO) {
        val jsonUrl = url.trimEnd('/') + ".json"
        val request = Request.Builder().url(jsonUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("BoB HTTP ${response.code} for $jsonUrl")
            val body = response.body?.string() ?: throw IOException("BoB empty response body")
            val wrapper = json.decodeFromString<ShopifyProductWrapper>(body)
            val variant = wrapper.product.variants.firstOrNull()
                ?: throw IOException("No variants in BoB Shopify response")
            val priceCents = (variant.price.toDouble() * 100).roundToInt()
            val available = variant.inventoryQuantity > 0 || variant.inventoryPolicy == "continue"
            ScrapedPack(
                priceCents = priceCents,
                tier2PriceCents = null,
                tier3PriceCents = null,
                tier4PriceCents = null,
                available = available,
            )
        }
    }

    @Serializable
    private data class ShopifyProductWrapper(
        @SerialName("product") val product: ShopifyProduct,
    )

    @Serializable
    private data class ShopifyProduct(
        @SerialName("variants") val variants: List<ShopifyVariant>,
    )

    @Serializable
    private data class ShopifyVariant(
        val price: String,
        @SerialName("inventory_quantity") val inventoryQuantity: Int,
        @SerialName("inventory_policy") val inventoryPolicy: String,
    )
}
