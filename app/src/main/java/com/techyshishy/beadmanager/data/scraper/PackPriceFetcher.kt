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

/** Thrown when the device does not have a validated internet connection. */
class NoConnectivityException : Exception("Network connectivity required to check prices")

/**
 * Thrown by [com.techyshishy.beadmanager.data.repository.CatalogRepository.checkAndUpdatePacks]
 * when every attempted scrape fails and no recently-cached results exist.
 *
 * Distinct from [NoConnectivityException]: the device has a validated internet connection,
 * but every vendor page request threw an exception (parse failure, HTTP error, etc.).
 */
class ScrapingFailedException : Exception("All vendor price scrapes failed; no cached results available")
