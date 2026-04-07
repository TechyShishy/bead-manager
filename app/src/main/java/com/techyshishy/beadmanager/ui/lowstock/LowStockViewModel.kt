package com.techyshishy.beadmanager.ui.lowstock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LowStockViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    inventoryRepository: InventoryRepository,
) : ViewModel() {

    val lowStockBeads: StateFlow<List<BeadWithInventory>> = combine(
        catalogRepository.getAllBeadsWithVendors(),
        inventoryRepository.inventoryStream(),
    ) { catalogEntries, inventoryMap ->
        catalogEntries.mapNotNull { entry ->
            val inv = inventoryMap[entry.bead.code] ?: return@mapNotNull null
            val item = BeadWithInventory(catalogEntry = entry, inventory = inv)
            if (item.isLowStock) item else null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun buildShareText(): String = lowStockBeads.value.joinToString(separator = "\n") { item ->
        val grams = item.inventory?.quantityGrams ?: 0.0
        "${item.code} — ${item.catalogEntry.bead.colorGroup} — ${java.math.BigDecimal.valueOf(grams).stripTrailingZeros().toPlainString()}g remaining"
    }
}
