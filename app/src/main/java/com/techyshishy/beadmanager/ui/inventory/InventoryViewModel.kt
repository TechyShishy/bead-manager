package com.techyshishy.beadmanager.ui.inventory

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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val inventoryRepository: InventoryRepository,
) : ViewModel() {

    // Only beads the user has logged (quantityGrams > 0), sorted by most recent update.
    val ownedBeads: StateFlow<List<BeadWithInventory>> = combine(
        catalogRepository.getAllBeadsWithVendors(),
        inventoryRepository.inventoryStream(),
    ) { catalogEntries, inventoryMap ->
        catalogEntries
            .mapNotNull { entry ->
                val inv = inventoryMap[entry.bead.code] ?: return@mapNotNull null
                if (inv.quantityGrams <= 0.0) return@mapNotNull null
                BeadWithInventory(catalogEntry = entry, inventory = inv)
            }
            .sortedByDescending { it.inventory?.lastUpdated?.seconds ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun adjustQuantity(beadCode: String, deltaGrams: Double) {
        viewModelScope.launch {
            val current = ownedBeads.value
                .firstOrNull { it.code == beadCode }
                ?.inventory
            inventoryRepository.adjustQuantity(beadCode, deltaGrams, current)
        }
    }
}
