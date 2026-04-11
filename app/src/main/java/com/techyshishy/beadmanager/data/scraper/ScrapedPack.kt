package com.techyshishy.beadmanager.data.scraper

/**
 * Result of a live price check for a single vendor pack SKU.
 *
 * [priceCents] is the Tier 1 (qty 1–14) unit price for FMG; the flat unit price for AC.
 * [tier2PriceCents], [tier3PriceCents], [tier4PriceCents] are FMG quantity-break prices;
 * null for AC (which has no tier discounts).
 * [available] reflects the vendor's current in-stock state.
 */
data class ScrapedPack(
    val priceCents: Int,
    val tier2PriceCents: Int?,
    val tier3PriceCents: Int?,
    val tier4PriceCents: Int?,
    val available: Boolean,
)
