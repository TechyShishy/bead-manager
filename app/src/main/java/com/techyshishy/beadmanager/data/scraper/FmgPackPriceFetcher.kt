package com.techyshishy.beadmanager.data.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fetches price and availability for a Fire Mountain Gems SKU using Jsoup HTML parsing.
 *
 * Price: locates the element with [data-price-book-id="usd-fmg-tier1-prices"] and
 * extracts the first "$X.XX" pattern from its text content. This is the Tier 1
 * (qty 1–14) price — the baseline stored in the catalog.
 *
 * Availability: locates any element with a [data-available] attribute and reads its
 * value. "true" → available; any other value → out of stock. If the attribute element
 * is absent, throws [IOException] (treated as fetch failure, not confirmed-unavailable).
 *
 * If the Tier 1 price block element is absent, throws [IOException].
 */
@Singleton
class FmgPackPriceFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : PackPriceFetcher {

    private val priceRegex = Regex("""\$(\d+\.\d{2})""")

    override suspend fun fetch(url: String): ScrapedPack = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("FMG HTTP ${response.code} for $url")
            val body = response.body?.string() ?: throw IOException("FMG empty response body")
            val doc = Jsoup.parse(body, url)

            val priceBlock = doc.selectFirst("[data-price-book-id=usd-fmg-tier1-prices]")
                ?: throw IOException("Tier 1 price block not found in FMG page: $url")
            val priceStr = priceRegex.find(priceBlock.text())?.groupValues?.get(1)
                ?: throw IOException("No price pattern found in Tier 1 block for: $url")
            val priceCents = (priceStr.toDouble() * 100).roundToInt()

            val availableEl = doc.selectFirst("[data-available]")
                ?: throw IOException("data-available attribute not found in FMG page: $url")
            val available = availableEl.attr("data-available") == "true"

            ScrapedPack(priceCents = priceCents, available = available)
        }
    }
}
