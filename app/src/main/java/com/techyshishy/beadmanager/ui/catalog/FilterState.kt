package com.techyshishy.beadmanager.ui.catalog

/**
 * The set of active filter chips on the catalog screen.
 * Empty sets mean "show all". All filtering happens in-memory in the ViewModel.
 */
data class FilterState(
    val colorGroups: Set<String> = emptySet(),
    val glassGroups: Set<String> = emptySet(),
    val finishes: Set<String> = emptySet(),
)
