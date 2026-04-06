package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.firestore.InventoryEntry

/**
 * View-ready combination of catalog data and optional inventory state.
 *
 * Produced by combining the Room [BeadWithVendors] flow with the Firestore
 * inventory map in the ViewModel layer — never persisted directly.
 */
data class BeadWithInventory(
    val catalogEntry: BeadWithVendors,
    val inventory: InventoryEntry?,
) {
    val code: String get() = catalogEntry.bead.code
    val isOwned: Boolean get() = (inventory?.quantityGrams ?: 0.0) > 0.0
    val isLowStock: Boolean
        get() {
            val inv = inventory ?: return false
            return inv.quantityGrams > 0.0 && inv.quantityGrams <= inv.lowStockThresholdGrams
        }
}
