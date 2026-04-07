package com.techyshishy.beadmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BeadDao {

    @Transaction
    @Query("SELECT * FROM beads ORDER BY code ASC")
    fun getAllBeadsWithVendors(): Flow<List<BeadWithVendors>>

    @Transaction
    @Query("SELECT * FROM beads WHERE code = :code")
    fun getBeadWithVendors(code: String): Flow<BeadWithVendors?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(beads: List<BeadEntity>)

    @Query("SELECT COUNT(*) FROM beads")
    suspend fun count(): Int

    // colorGroup is a JSON array but json_each() is not available on all Android
    // builds (JSON1 is an OEM compile-time option). Raw values are returned here
    // and decoded + flattened in CatalogViewModel.
    @Query("SELECT colorGroup FROM beads")
    fun allColorGroupJsonValues(): Flow<List<String>>

    @Query("SELECT DISTINCT glassGroup FROM beads ORDER BY glassGroup ASC")
    fun distinctGlassGroups(): Flow<List<String>>
}
