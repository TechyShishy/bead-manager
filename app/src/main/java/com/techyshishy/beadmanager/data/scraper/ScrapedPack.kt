package com.techyshishy.beadmanager.data.scraper

/**
 * Result of a live price check for a single vendor pack SKU.
 *
 * [priceCents] is always the Tier 1 (qty 1–14) price for FMG; the per-unit price for AC.
 * [available] reflects the vendor's current in-stock state.
 */
data class ScrapedPack(val priceCents: Int, val available: Boolean)
