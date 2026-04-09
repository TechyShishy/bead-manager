package com.techyshishy.beadmanager.data.scraper

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches a price-check fetch to the correct vendor-specific implementation.
 *
 * Supported vendor keys: "ac" (Aura Crystals), "fmg" (Fire Mountain Gems).
 * Unsupported keys throw [UnsupportedOperationException]; callers should filter
 * to only supported vendors before calling [fetch].
 */
@Singleton
class VendorPackPriceFetcher @Inject constructor(
    private val ac: AcPackPriceFetcher,
    private val fmg: FmgPackPriceFetcher,
) {
    val supportedVendorKeys: Set<String> = setOf("ac", "fmg")

    suspend fun fetch(vendorKey: String, url: String): ScrapedPack = when (vendorKey) {
        "ac" -> ac.fetch(url)
        "fmg" -> fmg.fetch(url)
        else -> throw UnsupportedOperationException("No price fetcher for vendor '$vendorKey'")
    }
}
