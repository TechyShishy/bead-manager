package com.techyshishy.beadmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy

@Dao
interface VendorLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<VendorLinkEntity>)
}
