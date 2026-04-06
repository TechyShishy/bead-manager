package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.BeadDao
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    private val beadDao: BeadDao,
) {
    fun getAllBeadsWithVendors(): Flow<List<BeadWithVendors>> =
        beadDao.getAllBeadsWithVendors()

    fun getBeadWithVendors(code: String): Flow<BeadWithVendors?> =
        beadDao.getBeadWithVendors(code)

    fun distinctColorGroups(): Flow<List<String>> =
        beadDao.distinctColorGroups()

    fun distinctGlassGroups(): Flow<List<String>> =
        beadDao.distinctGlassGroups()
}
