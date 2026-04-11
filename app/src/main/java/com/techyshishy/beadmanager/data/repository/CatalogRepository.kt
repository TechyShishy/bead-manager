package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.BeadDao
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.db.VendorPackDao
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.scraper.ScrapingFailedException
import com.techyshishy.beadmanager.data.scraper.VendorPackPriceFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    private val beadDao: BeadDao,
    private val vendorPackDao: VendorPackDao,
    private val vendorPackPriceFetcher: VendorPackPriceFetcher,
) {
    fun getAllBeadsWithVendors(): Flow<List<BeadWithVendors>> =
        beadDao.getAllBeadsWithVendors()

    fun getBeadWithVendors(code: String): Flow<BeadWithVendors?> =
        beadDao.getBeadWithVendors(code)

    fun allColorGroupJsonValues(): Flow<List<String>> =
        beadDao.allColorGroupJsonValues()

    fun distinctGlassGroups(): Flow<List<String>> =
        beadDao.distinctGlassGroups()

    fun allBeadsLookup(): Flow<Map<String, BeadEntity>> =
        beadDao.allBeads().map { list -> list.associateBy { it.code } }

    suspend fun allBeadsAsMap(): Map<String, BeadEntity> =
        beadDao.allBeads().first().associateBy { it.code }

    /** Vendor keys that carry the given bead, alphabetically ordered. */
    fun vendorKeysForBead(beadCode: String): Flow<List<String>> =
        vendorPackDao.vendorKeysForBead(beadCode)

    /** All pack sizes for a bead at a specific vendor, smallest first. */
    fun packsForVendor(beadCode: String, vendorKey: String): Flow<List<VendorPackEntity>> =
        vendorPackDao.packsByVendor(beadCode, vendorKey)

    /** Look up a single pack SKU by its natural key. Returns null if not in catalog. */
    suspend fun packByKey(beadCode: String, vendorKey: String, grams: Double): VendorPackEntity? =
        vendorPackDao.packByKey(beadCode, vendorKey, grams)

    /** All packs for a bead across every vendor, for vendor auto-selection at finalize time. */
    suspend fun packsForBead(beadCode: String): List<VendorPackEntity> =
        vendorPackDao.packsForBead(beadCode)

    /**
     * All available, priced packs for a given vendor across the entire catalog.
     * Used by the buy-up analyzer to find the cheapest filler pack.
     */
    suspend fun allPacksByVendor(vendorKey: String): List<VendorPackEntity> =
        vendorPackDao.allPacksByVendor(vendorKey)

    /**
     * Live-checks [packs] against their vendor pages and writes the results to Room.
     *
     * Packs whose [VendorPackEntity.lastCheckedEpochSeconds] is within [staleThresholdSeconds]
     * of [nowEpochSeconds] are skipped (considered fresh). Stale packs are fetched in parallel.
     *
     * Packs with unsupported vendor keys (anything other than "ac" and "fmg") are skipped
     * silently — they are neither fetched nor counted as failures.
     *
     * @throws ScrapingFailedException if every stale-pack fetch fails and no fresh packs exist.
     */
    suspend fun checkAndUpdatePacks(
        packs: List<VendorPackEntity>,
        nowEpochSeconds: Long,
        staleThresholdSeconds: Long = 12L * 3600,
    ): PriceCheckResult = coroutineScope {
        val stale = packs.filter { pack ->
            val lastChecked = pack.lastCheckedEpochSeconds
            lastChecked == null || (nowEpochSeconds - lastChecked) >= staleThresholdSeconds
        }
        val checkable = stale.filter { it.vendorKey in vendorPackPriceFetcher.supportedVendorKeys }

        data class Outcome(val id: Long, val success: Boolean)

        val outcomes = checkable.map { pack ->
            async {
                val success = try {
                    val scraped = vendorPackPriceFetcher.fetch(pack.vendorKey, pack.url)
                    vendorPackDao.updatePackCheck(
                        id = pack.id,
                        priceCents = scraped.priceCents,
                        tier2PriceCents = scraped.tier2PriceCents,
                        tier3PriceCents = scraped.tier3PriceCents,
                        tier4PriceCents = scraped.tier4PriceCents,
                        available = scraped.available,
                        lastCheckedEpochSeconds = nowEpochSeconds,
                    )
                    true
                } catch (e: Exception) {
                    false
                }
                Outcome(pack.id, success)
            }
        }.awaitAll()

        val failedIds = outcomes.filter { !it.success }.map { it.id }.toSet()
        val freshCount = packs.size - stale.size

        if (failedIds.size == checkable.size && checkable.isNotEmpty() && freshCount == 0) {
            throw ScrapingFailedException()
        }

        PriceCheckResult(failedPackIds = failedIds)
    }
}
