package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.firestore.InventoryEntry

/**
 * View-ready combination of catalog data and optional inventory state.
 *
 * Produced by combining the Room [BeadWithVendors] flow with the Firestore
 * inventory map and the app-wide low-stock threshold (from DataStore) in the
 * ViewModel layer — never persisted directly.
 *
 * [globalThresholdGrams] is the system-wide threshold configured in Settings.
 * A bead uses its own [InventoryEntry.lowStockThresholdGrams] when that value
 * is greater than zero; otherwise it falls back to [globalThresholdGrams].
 * Zero is the sentinel meaning "use the global default".
 */
data class BeadWithInventory(
    val catalogEntry: BeadWithVendors,
    val inventory: InventoryEntry?,
    val globalThresholdGrams: Double = 5.0,
) {
    val code: String get() = catalogEntry.bead.code
    val isOwned: Boolean get() = (inventory?.quantityGrams ?: 0.0) > 0.0
    val isLowStock: Boolean
        get() {
            val inv = inventory ?: return false
            val threshold = if (inv.lowStockThresholdGrams > 0.0) inv.lowStockThresholdGrams
                           else globalThresholdGrams
            return inv.quantityGrams > 0.0 && inv.quantityGrams <= threshold
        }
}
