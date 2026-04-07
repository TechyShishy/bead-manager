package com.techyshishy.beadmanager.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
        val numericKey: (BeadWithInventory) -> Int = { item ->
            item.code.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
        }
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
                    bead.code.contains(query, ignoreCase = true)

                val matchesColorGroup = filter.colorGroups.isEmpty() ||
                    bead.colorGroup in filter.colorGroups

                val matchesGlassGroup = filter.glassGroups.isEmpty() ||
                    bead.glassGroup in filter.glassGroups

                val matchesFinish = filter.finishes.isEmpty() ||
                    finishesInBead.any { it in filter.finishes }

                val matchesOwned = !filter.ownedOnly || item.isOwned

                matchesQuery && matchesColorGroup && matchesGlassGroup && matchesFinish && matchesOwned
            }
            .let { filtered ->
                val asc = filter.sortDirection == SortDirection.ASCENDING
                when (filter.sortBy) {
                    SortBy.DB_NUMBER -> filtered.sortedWith(
                        if (asc) compareBy(numericKey) else compareByDescending(numericKey),
                    )
                    SortBy.COLOR_GROUP -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.catalogEntry.bead.colorGroup }
                        else compareByDescending { it.catalogEntry.bead.colorGroup })
                            .thenBy(numericKey),
                    )
                    SortBy.GLASS_GROUP -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.catalogEntry.bead.glassGroup }
                        else compareByDescending { it.catalogEntry.bead.glassGroup })
                            .thenBy(numericKey),
                    )
                    SortBy.DYED -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.catalogEntry.bead.dyed }
                        else compareByDescending { it.catalogEntry.bead.dyed })
                            .thenBy(numericKey),
                    )
                    SortBy.GALVANIZED -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.catalogEntry.bead.galvanized }
                        else compareByDescending { it.catalogEntry.bead.galvanized })
                            .thenBy(numericKey),
                    )
                    SortBy.PLATING -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.catalogEntry.bead.plating }
                        else compareByDescending { it.catalogEntry.bead.plating })
                            .thenBy(numericKey),
                    )
                    SortBy.COUNT -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.inventory?.quantityGrams ?: 0.0 }
                        else compareByDescending { it.inventory?.quantityGrams ?: 0.0 })
                            .thenBy(numericKey),
                    )
                }
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

    fun toggleOwnedOnly() {
        filterState.value = filterState.value.copy(ownedOnly = !filterState.value.ownedOnly)
    }

    fun setSortBy(sort: SortBy) {
        val current = filterState.value
        filterState.value = if (sort == current.sortBy) {
            current.copy(
                sortDirection = if (current.sortDirection == SortDirection.ASCENDING)
                    SortDirection.DESCENDING else SortDirection.ASCENDING,
            )
        } else {
            current.copy(sortBy = sort, sortDirection = SortDirection.ASCENDING)
        }
    }

    fun clearFilters() { filterState.value = FilterState() }
}
