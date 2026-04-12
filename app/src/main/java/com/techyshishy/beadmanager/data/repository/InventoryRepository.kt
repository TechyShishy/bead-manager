package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreInventorySource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val source: FirestoreInventorySource,
    @AppScope private val appScope: CoroutineScope,
) {
    // Single shared listener: replays the last known state to late subscribers and
    // tears down the underlying Firestore listener 5 s after all subscribers leave.
    private val sharedInventory =
        source.inventoryStream()
            .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun inventoryStream(): Flow<Map<String, InventoryEntry>> = sharedInventory

    suspend fun upsert(entry: InventoryEntry) =
        source.upsert(entry)

    suspend fun adjustQuantity(beadCode: String, deltaGrams: Double) =
        source.adjustQuantity(beadCode, deltaGrams)

    suspend fun migrateLegacyThresholds() =
        source.migrateLegacyThresholds()
}
