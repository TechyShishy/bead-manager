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

    @Query("SELECT DISTINCT glassGroup FROM beads ORDER BY glassGroup ASC")
    fun distinctGlassGroups(): Flow<List<String>>

    @Query("SELECT * FROM beads ORDER BY code ASC")
    fun allBeads(): Flow<List<BeadEntity>>
}
