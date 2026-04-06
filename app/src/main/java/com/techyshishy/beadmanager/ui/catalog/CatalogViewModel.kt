package com.techyshishy.beadmanager.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class CatalogViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    inventoryRepository: InventoryRepository,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val filterState = MutableStateFlow(FilterState())

    val colorGroups: StateFlow<List<String>> = catalogRepository.distinctColorGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val glassGroups: StateFlow<List<String>> = catalogRepository.distinctGlassGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    val beads: StateFlow<List<BeadWithInventory>> = combine(
        catalogRepository.getAllBeadsWithVendors(),
        inventoryRepository.inventoryStream(),
        searchQuery,
        filterState,
    ) { catalogEntries, inventoryMap, query, filter ->
        catalogEntries
            .map { entry ->
                BeadWithInventory(
                    catalogEntry = entry,
                    inventory = inventoryMap[entry.bead.code],
                )
            }
            .filter { item ->
                val bead = item.catalogEntry.bead
                val finishesInBead = runCatching {
                    json.decodeFromString<List<String>>(bead.finishes)
                }.getOrDefault(emptyList())

                val matchesQuery = query.isBlank() ||
                    bead.code.contains(query, ignoreCase = true) ||
                    bead.colorGroup.contains(query, ignoreCase = true)

                val matchesColorGroup = filter.colorGroups.isEmpty() ||
                    bead.colorGroup in filter.colorGroups

                val matchesGlassGroup = filter.glassGroups.isEmpty() ||
                    bead.glassGroup in filter.glassGroups

                val matchesFinish = filter.finishes.isEmpty() ||
                    finishesInBead.any { it in filter.finishes }

                matchesQuery && matchesColorGroup && matchesGlassGroup && matchesFinish
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateSearch(query: String) { searchQuery.value = query }

    fun toggleColorGroup(group: String) {
        filterState.value = filterState.value.let { f ->
            f.copy(
                colorGroups = if (group in f.colorGroups) f.colorGroups - group
                else f.colorGroups + group,
            )
        }
    }

    fun toggleGlassGroup(group: String) {
        filterState.value = filterState.value.let { f ->
            f.copy(
                glassGroups = if (group in f.glassGroups) f.glassGroups - group
                else f.glassGroups + group,
            )
        }
    }

    fun toggleFinish(finish: String) {
        filterState.value = filterState.value.let { f ->
            f.copy(
                finishes = if (finish in f.finishes) f.finishes - finish
                else f.finishes + finish,
            )
        }
    }

    fun clearFilters() { filterState.value = FilterState() }
}
