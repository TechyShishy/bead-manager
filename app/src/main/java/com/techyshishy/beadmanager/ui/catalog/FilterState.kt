package com.techyshishy.beadmanager.ui.catalog

enum class SortBy {
    DB_NUMBER, COLOR_GROUP, GLASS_GROUP, DYED, GALVANIZED, PLATING, COUNT
}

enum class SortDirection { ASCENDING, DESCENDING }

/**
 * The set of active filter chips and sort order for the catalog screen.
 * Empty sets mean "show all". All filtering and sorting happens in-memory in the ViewModel.
 *
 * colorGroups uses AND semantics: a bead must belong to every selected group.
 * glassGroups and finishes use OR semantics: a bead must match at least one selection.
 */
data class FilterState(
    val colorGroups: Set<String> = emptySet(),
    val glassGroups: Set<String> = emptySet(),
    val finishes: Set<String> = emptySet(),
    val ownedOnly: Boolean = false,
    val sortBy: SortBy = SortBy.DB_NUMBER,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
)

/**
 * A labeled segment of the sorted catalog list, used by the sort navigation bar.
 * [startIndex] is the position of the first item in this bucket within the sorted beads list.
 */
data class SortBucket(
    val label: String,
    val startIndex: Int,
)
