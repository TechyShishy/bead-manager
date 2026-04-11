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
 * Price: locates the four tier price blocks by [data-price-book-id] attribute:
 *   - Tier 1 (qty 1–14):  usd-fmg-tier1-prices
 *   - Tier 2 (qty 15–49): usd-fmg-tier2-prices
 *   - Tier 3 (qty 50–99): usd-fmg-tier3-prices
 *   - Tier 4 (qty 100+): usd-fmg-tier4-prices
 * Each block's text is matched against the first "$X.XX" pattern.
 *
 * Availability: locates any element with a [data-available] attribute and reads its
 * value. "true" → available; any other value → out of stock. If the attribute element
 * is absent, throws [IOException] (treated as fetch failure, not confirmed-unavailable).
 *
 * If any required price block element is absent, throws [IOException].
 */
@Singleton
class FmgPackPriceFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : PackPriceFetcher {

    private val priceRegex = Regex("""\$\s*(\d+\.\d{2})""")

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

            fun scrapeTierPrice(tierId: String): Int {
                val block = doc.selectFirst("[data-price-book-id=$tierId]")
                    ?: throw IOException("Tier price block '$tierId' not found in FMG page: $url")
                val priceStr = priceRegex.find(block.text())?.groupValues?.get(1)
                    ?: throw IOException("No price pattern found in block '$tierId' for: $url")
                return (priceStr.toDouble() * 100).roundToInt()
            }

            val priceCents = scrapeTierPrice("usd-fmg-tier1-prices")
            val tier2PriceCents = scrapeTierPrice("usd-fmg-tier2-prices")
            val tier3PriceCents = scrapeTierPrice("usd-fmg-tier3-prices")
            val tier4PriceCents = scrapeTierPrice("usd-fmg-tier4-prices")

            val availableEl = doc.selectFirst("[data-available]")
                ?: throw IOException("data-available attribute not found in FMG page: $url")
            val available = availableEl.attr("data-available") == "true"

            ScrapedPack(
                priceCents = priceCents,
                tier2PriceCents = tier2PriceCents,
                tier3PriceCents = tier3PriceCents,
                tier4PriceCents = tier4PriceCents,
                available = available,
            )
        }
    }
}
