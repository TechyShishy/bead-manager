package com.techyshishy.beadmanager.ui.catalog

enum class SortBy {
    DB_NUMBER, COLOR_GROUP, GLASS_GROUP, DYED, GALVANIZED, PLATING, COUNT
}

enum class SortDirection { ASCENDING, DESCENDING }

/**
 * The set of active filter chips and sort order for the catalog screen.
 * Empty sets mean "show all". All filtering and sorting happens in-memory in the ViewModel.
 */
data class FilterState(
    val colorGroups: Set<String> = emptySet(),
    val glassGroups: Set<String> = emptySet(),
    val finishes: Set<String> = emptySet(),
    val ownedOnly: Boolean = false,
    val sortBy: SortBy = SortBy.DB_NUMBER,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
)
