package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreInventorySource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val source: FirestoreInventorySource,
) {
    fun inventoryStream(): Flow<Map<String, InventoryEntry>> =
        source.inventoryStream()

    suspend fun upsert(entry: InventoryEntry) =
        source.upsert(entry)

    suspend fun adjustQuantity(beadCode: String, deltaGrams: Double, current: InventoryEntry?) =
        source.adjustQuantity(beadCode, deltaGrams, current)
}
