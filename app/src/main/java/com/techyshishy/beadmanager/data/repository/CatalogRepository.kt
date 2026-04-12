package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.BeadDao
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.db.VendorLinkDao
import com.techyshishy.beadmanager.data.db.VendorPackDao
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.scraper.ScrapingFailedException
import com.techyshishy.beadmanager.data.scraper.VendorPackPriceFetcher
import com.techyshishy.beadmanager.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Canonical key for [CatalogRepository.vendorNamesForBeads]: `(beadCode, vendorKey)`. */
typealias BeadVendorKey = Pair<String, String>

@Singleton
class CatalogRepository @Inject constructor(
    private val beadDao: BeadDao,
    private val vendorLinkDao: VendorLinkDao,
    private val vendorPackDao: VendorPackDao,
    private val vendorPackPriceFetcher: VendorPackPriceFetcher,
    @AppScope private val appScope: CoroutineScope,
) {
    // Single shared observer for the full bead catalog. The catalog is read-only
    // post-seeding so this map is safe to hold for the lifetime of the process.
    // Lazily: start on first subscriber, never restart — catalog data never changes.
    private val beadsLookupFlow: StateFlow<Map<String, BeadEntity>> =
        beadDao.allBeads()
            .map { list -> list.associateBy { it.code } }
            .stateIn(appScope, SharingStarted.Lazily, emptyMap())

    // One-shot cache for suspend callers (FinalizeOrderUseCase, ImportRgpProjectUseCase).
    // Safe to cache forever: catalog is immutable after CatalogSeeder.seedIfNeeded() completes.
    @Volatile private var beadsMapCache: Map<String, BeadEntity>? = null
    fun getAllBeadsWithVendors(): Flow<List<BeadWithVendors>> =
        beadDao.getAllBeadsWithVendors()

    fun getBeadWithVendors(code: String): Flow<BeadWithVendors?> =
        beadDao.getBeadWithVendors(code)

    fun distinctGlassGroups(): Flow<List<String>> =
        beadDao.distinctGlassGroups()

    fun allBeadsLookup(): Flow<Map<String, BeadEntity>> = beadsLookupFlow

    suspend fun allBeadsAsMap(): Map<String, BeadEntity> {
        beadsMapCache?.let { return it }
        return beadDao.allBeads().first().associateBy { it.code }.also { beadsMapCache = it }
    }

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
     * Returns a map of (beadCode, vendorKey) → beadName for the given bead codes.
     * Missing or null names are excluded from the map.
     *
     * Returns an empty map immediately when [beadCodes] is empty (avoids an SQLite
     * "zero-argument IN" error on some API levels).
     *
     * The key is [BeadVendorKey] = `(beadCode to vendorKey)` — not the reverse. Both fields
     * are String so an inverted lookup compiles silently; the typealias makes the order explicit.
     */
    suspend fun vendorNamesForBeads(beadCodes: List<String>): Map<BeadVendorKey, String> {
        if (beadCodes.isEmpty()) return emptyMap()
        return vendorLinkDao.linksForBeads(beadCodes)
            .mapNotNull { link ->
                val name = link.beadName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                (link.beadCode to link.vendorKey) to name
            }
            .toMap()
    }

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
