package com.techyshishy.beadmanager.ui.lowstock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class LowStockViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    inventoryRepository: InventoryRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val lowStockBeads: StateFlow<List<BeadWithInventory>> = combine(
        catalogRepository.getAllBeadsWithVendors(),
        inventoryRepository.inventoryStream(),
        preferencesRepository.globalLowStockThreshold,
    ) { catalogEntries, inventoryMap, globalThreshold ->
        catalogEntries
            .map { entry ->
                BeadWithInventory(
                    catalogEntry = entry,
                    inventory = inventoryMap[entry.bead.code],
                    globalThresholdGrams = globalThreshold,
                )
            }
            .filter { it.isLowStock }
            .sortedBy { it.code }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedCodes: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    // Intersection of the user's selection and the currently visible low-stock list.
    // Beads that replenish mid-session drop out of lowStockBeads and are automatically
    // removed from this set, preventing stale codes from reaching order-creation logic.
    val effectiveSelectedCodes: StateFlow<Set<String>> = combine(
        lowStockBeads,
        selectedCodes,
    ) { beads, selected ->
        val visible = beads.map { it.code }.toSet()
        selected intersect visible
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleSelection(code: String) {
        selectedCodes.update { current ->
            if (code in current) current - code else current + code
        }
    }

    fun selectAll(codes: Collection<String>) {
        selectedCodes.value = codes.toSet()
    }

    fun clearSelection() {
        selectedCodes.value = emptySet()
    }
}
