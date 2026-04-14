package com.techyshishy.beadmanager.ui.projects

import com.techyshishy.beadmanager.data.firestore.ProjectEntry

enum class ProjectSortOrder(val comparator: Comparator<ProjectEntry>) {
    /** Newest project first. Projects with no creation timestamp sort after those with one. */
    CREATED_AT_DESCENDING(
        Comparator { a, b ->
            val aTs = a.createdAt
            val bTs = b.createdAt
            when {
                aTs == null && bTs == null -> 0
                aTs == null -> 1  // null sorts after any real timestamp
                bTs == null -> -1
                else -> bTs.compareTo(aTs) // descending: later timestamp first
            }
        },
    ),

    /** A → Z, case-insensitive. */
    NAME_ASCENDING(
        Comparator { a, b ->
            a.name.compareTo(b.name, ignoreCase = true)
        },
    ),

    /** Project with the most palette entries first — counts all colorMapping keys. */
    BEAD_TYPES_DESCENDING(
        Comparator { a, b ->
            b.colorMapping.size.compareTo(a.colorMapping.size)
        },
    ),

    /** Largest grid (most rows) first; projects with no grid (rowCount == 0) sort last. */
    GRID_SIZE_DESCENDING(
        Comparator { a, b ->
            when {
                a.rowCount == 0 && b.rowCount == 0 -> 0
                a.rowCount == 0 -> 1  // no-grid projects after grid projects
                b.rowCount == 0 -> -1
                else -> b.rowCount.compareTo(a.rowCount)
            }
        },
    ),
}
