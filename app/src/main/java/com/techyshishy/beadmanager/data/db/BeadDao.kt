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

    // Distinct filter values used to populate filter chips in the UI.
    // json_each() expands the JSON array stored in colorGroup so each element
    // is returned as a separate row; DISTINCT then collapses duplicates across
    // all beads, giving the full set of individual color-group strings.
    @Query("SELECT DISTINCT value FROM beads, json_each(beads.colorGroup) ORDER BY value ASC")
    fun distinctColorGroups(): Flow<List<String>>

    @Query("SELECT DISTINCT glassGroup FROM beads ORDER BY glassGroup ASC")
    fun distinctGlassGroups(): Flow<List<String>>
}
