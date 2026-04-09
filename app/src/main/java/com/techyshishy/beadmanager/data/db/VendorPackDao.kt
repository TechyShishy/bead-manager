package com.techyshishy.beadmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VendorPackDao {

    // REPLACE ensures CATALOG_VERSION bumps refresh pack URLs on existing rows.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packs: List<VendorPackEntity>)

    /**
     * Distinct vendor keys that carry a bead, ordered alphabetically.
     * Used to populate the vendor picker in the order line-item editor.
     */
    @Query(
        "SELECT DISTINCT vendorKey FROM vendor_packs WHERE beadCode = :beadCode ORDER BY vendorKey ASC"
    )
    fun vendorKeysForBead(beadCode: String): Flow<List<String>>

    /**
     * All pack sizes for a bead at a specific vendor, ordered smallest first.
     * Used to populate the pack-size picker in the order line-item editor.
     */
    @Query(
        "SELECT * FROM vendor_packs WHERE beadCode = :beadCode AND vendorKey = :vendorKey ORDER BY grams ASC"
    )
    fun packsByVendor(beadCode: String, vendorKey: String): Flow<List<VendorPackEntity>>

    /**
     * Resolve the purchase URL for a specific (bead, vendor, pack size) triple.
     * Returns null if the combination is not in the catalog — callers must handle this.
     */
    @Query(
        "SELECT url FROM vendor_packs WHERE beadCode = :beadCode AND vendorKey = :vendorKey AND grams = :grams LIMIT 1"
    )
    suspend fun packUrl(beadCode: String, vendorKey: String, grams: Double): String?

    /**
     * Look up a specific pack SKU by its (beadCode, vendorKey, grams) identity key.
     * Returns null if no matching row exists.
     */
    @Query(
        "SELECT * FROM vendor_packs WHERE beadCode = :beadCode AND vendorKey = :vendorKey AND grams = :grams LIMIT 1"
    )
    suspend fun packByKey(beadCode: String, vendorKey: String, grams: Double): VendorPackEntity?

    /**
     * Records the outcome of a live price check for a single SKU.
     * Called after a successful scrape; never called on fetch failure.
     */
    @Query(
        "UPDATE vendor_packs SET priceCents = :priceCents, available = :available, lastCheckedEpochSeconds = :lastCheckedEpochSeconds WHERE id = :id"
    )
    suspend fun updatePackCheck(
        id: Long,
        priceCents: Int,
        available: Boolean,
        lastCheckedEpochSeconds: Long,
    )
}
