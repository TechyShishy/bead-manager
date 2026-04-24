package com.techyshishy.beadmanager.data.scraper

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches a price-check fetch to the correct vendor-specific implementation.
 *
 * Supported vendor keys: "ac" (Aura Crystals), "bob" (Barrel of Beads),
 * "fmg" (Fire Mountain Gems). Unsupported keys throw [UnsupportedOperationException];
 * callers should filter to only supported vendors before calling [fetch].
 */
@Singleton
class VendorPackPriceFetcher @Inject constructor(
    private val ac: AcPackPriceFetcher,
    private val bob: BobPackPriceFetcher,
    private val fmg: FmgPackPriceFetcher,
) {
    val supportedVendorKeys: Set<String> = setOf("ac", "bob", "fmg")

    suspend fun fetch(vendorKey: String, url: String): ScrapedPack = when (vendorKey) {
        "ac"  -> ac.fetch(url)
        "bob" -> bob.fetch(url)
        "fmg" -> fmg.fetch(url)
        else -> throw UnsupportedOperationException("No price fetcher for vendor '$vendorKey'")
    }
}
