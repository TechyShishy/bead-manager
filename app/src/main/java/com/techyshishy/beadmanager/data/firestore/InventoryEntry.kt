package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A user's inventory record for a single bead variant.
 *
 * Firestore path: users/{uid}/inventory/{beadCode}
 *
 * Default values are required for Firestore deserialization. [beadCode] is
 * injected from the document ID via @DocumentId and should not be set manually.
 */
data class InventoryEntry(
    @DocumentId val beadCode: String = "",
    val quantityGrams: Double = 0.0,
    val lowStockThresholdGrams: Double = 5.0,
    val notes: String = "",
    @ServerTimestamp val lastUpdated: Timestamp? = null,
)
