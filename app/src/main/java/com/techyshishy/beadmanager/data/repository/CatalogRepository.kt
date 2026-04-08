package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.BeadDao
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.db.VendorPackDao
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    private val beadDao: BeadDao,
    private val vendorPackDao: VendorPackDao,
) {
    fun getAllBeadsWithVendors(): Flow<List<BeadWithVendors>> =
        beadDao.getAllBeadsWithVendors()

    fun getBeadWithVendors(code: String): Flow<BeadWithVendors?> =
        beadDao.getBeadWithVendors(code)

    fun allColorGroupJsonValues(): Flow<List<String>> =
        beadDao.allColorGroupJsonValues()

    fun distinctGlassGroups(): Flow<List<String>> =
        beadDao.distinctGlassGroups()

    /** Vendor keys that carry the given bead, alphabetically ordered. */
    fun vendorKeysForBead(beadCode: String): Flow<List<String>> =
        vendorPackDao.vendorKeysForBead(beadCode)

    /** All pack sizes for a bead at a specific vendor, smallest first. */
    fun packsForVendor(beadCode: String, vendorKey: String): Flow<List<VendorPackEntity>> =
        vendorPackDao.packsByVendor(beadCode, vendorKey)
}
