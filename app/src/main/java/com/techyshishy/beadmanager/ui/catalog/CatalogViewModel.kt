package com.techyshishy.beadmanager.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.FirestoreCatalogPinsSource
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    inventoryRepository: InventoryRepository,
    preferencesRepository: PreferencesRepository,
    private val pinsSource: FirestoreCatalogPinsSource,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val filterState = MutableStateFlow(FilterState())

    // Comparison pin state. Kept in sync with Firestore via the pinsSource stream so pins
    // survive ViewModel destruction, process death, and device switches. Mutations write
    // optimistically to this flow first (instant UI feedback) then persist to Firestore.
    val pinnedCodes: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    init {
        // Keeps pinnedCodes in sync with the Firestore stream. On the originating device the
        // snapshot arrives after the optimistic local write, so the onEach update is a no-op.
        // Rapid back-to-back mutations can theoretically cause a brief intermediate snapshot
        // to overwrite a later write, but the sub-millisecond window makes this unobservable
        // in practice. If write ordering ever becomes a concern, replace with Firestore
        // FieldValue.arrayUnion/arrayRemove operations.
        pinsSource.pinnedCodesStream()
            .onEach { codes -> pinnedCodes.value = codes }
            .launchIn(viewModelScope)
    }
    val stockOnlyFilter: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // Set by AdaptiveScaffold when the catalog opens in swap-picker mode. null = not in swap mode.
    val enoughOnHandTargetGrams: MutableStateFlow<Double?> = MutableStateFlow(null)
    val enoughOnHandEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val colorGroups: StateFlow<List<String>> = catalogRepository.allBeadsLookup()
        .map { beadMap ->
            beadMap.values
                .flatMap { it.colorGroup }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val glassGroups: StateFlow<List<String>> = catalogRepository.distinctGlassGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Shared base: catalog + inventory + threshold merged into view-ready objects.
    // Consumed by both `beads` (with user filters applied) and `pinnedBeads` (unfiltered).
    private val allBeadsWithInventory: StateFlow<List<BeadWithInventory>> = combine(
        catalogRepository.getAllBeadsWithVendors(),
        inventoryRepository.inventoryStream(),
        preferencesRepository.globalLowStockThreshold,
    ) { catalogEntries, inventoryMap, globalThreshold ->
        catalogEntries.map { entry ->
            BeadWithInventory(
                catalogEntry = entry,
                inventory = inventoryMap[entry.bead.code],
                globalThresholdGrams = globalThreshold,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Ordered list of pinned BeadWithInventory objects, in pin order, with live inventory data.
    val pinnedBeads: StateFlow<List<BeadWithInventory>> = combine(
        allBeadsWithInventory,
        pinnedCodes,
    ) { allBeads, codes ->
        val lookup = allBeads.associateBy { it.code }
        codes.mapNotNull { lookup[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val enoughOnHandFilter: StateFlow<Pair<Double?, Boolean>> = combine(
        enoughOnHandTargetGrams,
        enoughOnHandEnabled,
    ) { targetGrams, enabled -> targetGrams to enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to false)

    val beads: StateFlow<List<BeadWithInventory>> = combine(
        allBeadsWithInventory,
        searchQuery,
        filterState,
        stockOnlyFilter,
        enoughOnHandFilter,
    ) { allBeads, query, filter, stockOnly, enoughOnHand ->
        val (enoughTargetGrams, enoughEnabled) = enoughOnHand
        val numericKey: (BeadWithInventory) -> Int = { item ->
            item.code.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
        }
        allBeads
            .filter { item ->
                val bead = item.catalogEntry.bead
                val finishesInBead = bead.finishes
                val colorGroupsInBead = bead.colorGroup

                val matchesQuery = query.isBlank() ||
                    bead.code.contains(query, ignoreCase = true)

                val matchesColorGroup = filter.colorGroups.isEmpty() ||
                    filter.colorGroups.all { it in colorGroupsInBead }

                val matchesGlassGroup = filter.glassGroups.isEmpty() ||
                    bead.glassGroup in filter.glassGroups

                val matchesFinish = filter.finishes.isEmpty() ||
                    finishesInBead.any { it in filter.finishes }

                val matchesDyed = filter.dyed.isEmpty() || bead.dyed in filter.dyed

                val matchesGalvanized = filter.galvanized.isEmpty() || bead.galvanized in filter.galvanized

                val matchesPlating = filter.plating.isEmpty() || bead.plating in filter.plating

                val matchesOwned = !filter.ownedOnly || item.isOwned

                val matchesStockOnly = !stockOnly || item.isOwned

                val matchesEnoughOnHand = !enoughEnabled || enoughTargetGrams == null || run {
                    val owned = item.inventory?.quantityGrams ?: 0.0
                    if (enoughTargetGrams == 0.0) owned > 0.0 else owned >= enoughTargetGrams
                }

                matchesQuery && matchesColorGroup && matchesGlassGroup &&
                    matchesFinish && matchesDyed && matchesGalvanized && matchesPlating &&
                    matchesOwned && matchesStockOnly && matchesEnoughOnHand
            }
            .let { filtered ->
                val asc = filter.sortDirection == SortDirection.ASCENDING
                when (filter.sortBy) {
                    SortBy.DB_NUMBER -> filtered.sortedWith(
                        if (asc) compareBy(numericKey) else compareByDescending(numericKey),
                    )
                    SortBy.COLOR_GROUP -> filtered.sortedWith(
                        // Sort by primary color group (first listed in the decoded list).
                        // Multi-group beads tie-break by DB number.
                        (if (asc) compareBy<BeadWithInventory> {
                            it.catalogEntry.bead.colorGroup.firstOrNull() ?: ""
                        } else compareByDescending {
                            it.catalogEntry.bead.colorGroup.firstOrNull() ?: ""
                        })
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
                    SortBy.COUNT_GRAMS, SortBy.COUNT_BEADS -> filtered.sortedWith(
                        (if (asc) compareBy<BeadWithInventory> { it.inventory?.quantityGrams ?: 0.0 }
                        else compareByDescending { it.inventory?.quantityGrams ?: 0.0 })
                            .thenBy(numericKey),
                    )
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sortBuckets: StateFlow<List<SortBucket>> = combine(beads, filterState) { sortedBeads, filter ->
        computeSortBuckets(
            beads = sortedBeads,
            sortBy = filter.sortBy,
            ascending = filter.sortDirection == SortDirection.ASCENDING,
        )
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

    fun toggleDyed(value: String) {
        filterState.value = filterState.value.let { f ->
            f.copy(
                dyed = if (value in f.dyed) f.dyed - value else f.dyed + value,
            )
        }
    }

    fun toggleGalvanized(value: String) {
        filterState.value = filterState.value.let { f ->
            f.copy(
                galvanized = if (value in f.galvanized) f.galvanized - value else f.galvanized + value,
            )
        }
    }

    fun togglePlating(value: String) {
        filterState.value = filterState.value.let { f ->
            f.copy(
                plating = if (value in f.plating) f.plating - value else f.plating + value,
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

    fun clearFilters() {
        filterState.value = FilterState()
        enoughOnHandEnabled.value = false
    }

    // --- Comparison pin actions ---

    fun togglePin(code: String) {
        val updated = pinnedCodes.value.let { if (code in it) it - code else it + code }
        pinnedCodes.value = updated
        viewModelScope.launch { pinsSource.setPinnedCodes(updated) }
    }

    fun unpinBead(code: String) {
        val updated = pinnedCodes.value - code
        pinnedCodes.value = updated
        viewModelScope.launch { pinsSource.setPinnedCodes(updated) }
    }

    fun clearAllPins() {
        pinnedCodes.value = emptyList()
        stockOnlyFilter.value = false
        viewModelScope.launch { pinsSource.setPinnedCodes(emptyList()) }
    }

    fun pinAll(codes: List<String>) {
        pinnedCodes.value = codes
        viewModelScope.launch { pinsSource.setPinnedCodes(codes) }
    }

    fun reorderPins(newOrder: List<String>) {
        pinnedCodes.value = newOrder
        viewModelScope.launch { pinsSource.setPinnedCodes(newOrder) }
    }

    fun toggleStockOnly() {
        stockOnlyFilter.value = !stockOnlyFilter.value
    }

    fun setEnoughOnHandContext(targetGrams: Double) {
        enoughOnHandTargetGrams.value = targetGrams
        enoughOnHandEnabled.value = false
    }

    fun clearEnoughOnHandFilter() {
        enoughOnHandTargetGrams.value = null
        enoughOnHandEnabled.value = false
    }

    fun toggleEnoughOnHand() {
        enoughOnHandEnabled.value = !enoughOnHandEnabled.value
    }
}
