package com.techyshishy.beadmanager.data.scraper

/**
 * Contract for fetching the current price and availability of a vendor pack SKU.
 *
 * Implementations are expected to use [url] as the product page or API endpoint.
 * On any retrieval failure the implementation should throw an [IOException] (or subclass);
 * the caller distinguishes fetch failure from a confirmed-unavailable result.
 */
interface PackPriceFetcher {
    suspend fun fetch(url: String): ScrapedPack
}

/** Thrown when all price-check requests fail and there are no recently-cached results. */
class NoConnectivityException : Exception("Network connectivity required to check prices")
