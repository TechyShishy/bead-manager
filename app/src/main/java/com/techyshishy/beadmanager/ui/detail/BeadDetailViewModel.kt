package com.techyshishy.beadmanager.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.db.VendorLinkEntity
import com.techyshishy.beadmanager.data.repository.PreferencesRepository.Companion.DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the bead detail pane.
 *
 * Because this VM is used without Compose Navigation (the adaptive scaffold
 * places list and detail in the same composition), the bead code is delivered
 * via [initialize] rather than SavedStateHandle. The VM is keyed by code in
 * AdaptiveScaffold, so each unique code gets its own instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BeadDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val inventoryRepository: InventoryRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val beadCode = MutableStateFlow("")

    val globalThresholdGrams: StateFlow<Double> =
        preferencesRepository.globalLowStockThreshold
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD)

    private val vendorPriorityOrder: StateFlow<List<String>> =
        preferencesRepository.vendorPriorityOrder
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PreferencesRepository.DEFAULT_VENDOR_PRIORITY_ORDER,
            )

    fun initialize(code: String) {
        if (beadCode.value != code) beadCode.value = code
    }

    val bead: StateFlow<BeadWithInventory?> = beadCode
        .flatMapLatest { code ->
            if (code.isBlank()) return@flatMapLatest flowOf(null)
            combine(
                catalogRepository.getBeadWithVendors(code),
                inventoryRepository.inventoryStream(),
                globalThresholdGrams,
            ) { catalogEntry, inventoryMap, threshold ->
                catalogEntry?.let {
                    BeadWithInventory(
                        catalogEntry = it,
                        inventory = inventoryMap[code],
                        globalThresholdGrams = threshold,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The bead's display name resolved from the highest-priority vendor link that has a
     * non-blank [com.techyshishy.beadmanager.data.db.VendorLinkEntity.beadName].
     * Emits `null` when no vendor link carries a name.
     *
     * TODO: derive from combine(catalogRepository.getBeadWithVendors(beadCode), vendorPriorityOrder)
     *  instead of from [bead], so name resolution does not recompute on every inventory or
     *  threshold change.
     */
    val beadName: StateFlow<String?> =
        combine(bead, vendorPriorityOrder) { beadWithInventory, priorityOrder ->
            resolveBeadName(
                vendorLinks = beadWithInventory?.catalogEntry?.vendorLinks ?: emptyList(),
                priorityOrder = priorityOrder,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun resolveBeadName(
        vendorLinks: List<VendorLinkEntity>,
        priorityOrder: List<String>,
    ): String? {
        val byVendor = vendorLinks.associateBy { it.vendorKey }
        for (key in priorityOrder) {
            val name = byVendor[key]?.beadName?.takeIf { it.isNotBlank() }
            if (name != null) return name
        }
        // Fall back: pick first vendor with any non-blank name (handles vendor keys absent from priorityOrder)
        return vendorLinks.firstOrNull { !it.beadName.isNullOrBlank() }?.beadName
    }

    fun adjustQuantity(deltaGrams: Double) {
        viewModelScope.launch {
            inventoryRepository.adjustQuantity(
                beadCode = beadCode.value,
                deltaGrams = deltaGrams,
            )
        }
    }

    fun setQuantity(grams: Double) {
        viewModelScope.launch {
            val code = beadCode.value
            val current = bead.value?.inventory ?: InventoryEntry(beadCode = code)
            inventoryRepository.upsert(current.copy(quantityGrams = grams, lastUpdated = null))
        }
    }

    fun updateNotes(notes: String) {
        viewModelScope.launch {
            val code = beadCode.value
            val current = bead.value?.inventory ?: InventoryEntry(beadCode = code)
            inventoryRepository.upsert(current.copy(notes = notes, lastUpdated = null))
        }
    }

    fun updateThreshold(thresholdGrams: Double) {
        viewModelScope.launch {
            val code = beadCode.value
            val current = bead.value?.inventory ?: InventoryEntry(beadCode = code)
            inventoryRepository.upsert(
                current.copy(lowStockThresholdGrams = thresholdGrams, lastUpdated = null)
            )
        }
    }

    fun resetThresholdToGlobal() = updateThreshold(0.0)
}

