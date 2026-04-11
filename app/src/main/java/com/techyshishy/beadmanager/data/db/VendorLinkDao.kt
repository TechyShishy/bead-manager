package com.techyshishy.beadmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VendorLinkDao {

    // REPLACE (not IGNORE) so that CATALOG_VERSION bumps repopulate beadName on existing rows.
    // Safe: the only unique constraint is (beadCode, vendorKey); id is autoincrement so Room
    // omits it from the INSERT, preventing a spurious PK conflict on id=0.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<VendorLinkEntity>)

    @Query("SELECT * FROM vendor_links WHERE beadCode IN (:beadCodes)")
    suspend fun linksForBeads(beadCodes: List<String>): List<VendorLinkEntity>
}
