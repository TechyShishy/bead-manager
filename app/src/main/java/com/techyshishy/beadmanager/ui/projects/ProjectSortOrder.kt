package com.techyshishy.beadmanager.ui.projects

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.model.ProjectSatisfaction

enum class SortDirection { ASCENDING, DESCENDING }

enum class ProjectSortKey(val defaultDirection: SortDirection) {
    CREATED_AT(SortDirection.DESCENDING),
    NAME(SortDirection.ASCENDING),
    BEAD_TYPES(SortDirection.DESCENDING),
    GRID_SIZE(SortDirection.DESCENDING),
    SATISFACTION(SortDirection.ASCENDING),
}

/**
 * A sort specification combining a [key] and a [direction].
 *
 * Sentinel / null values always sort last regardless of direction:
 * - [ProjectSortKey.CREATED_AT]: null [ProjectEntry.createdAt] → always after real timestamps.
 * - [ProjectSortKey.GRID_SIZE]: [ProjectEntry.rowCount] == 0 (no imported grid) → always after
 *   grid-backed projects.
 * - [ProjectSortKey.SATISFACTION]: null satisfaction (no Delica beads / grid not yet loaded) →
 *   always after projects with a computable satisfaction ratio.
 */
data class ProjectSortOrder(
    val key: ProjectSortKey,
    val direction: SortDirection,
) {
    companion object {
        val DEFAULT = ProjectSortOrder(ProjectSortKey.CREATED_AT, SortDirection.DESCENDING)
    }

    fun comparator(
        satisfaction: Map<String, ProjectSatisfaction?> = emptyMap(),
    ): Comparator<ProjectEntry> = when (key) {
        ProjectSortKey.CREATED_AT -> Comparator { a, b ->
            val aTs = a.createdAt
            val bTs = b.createdAt
            when {
                aTs == null && bTs == null -> 0
                aTs == null -> 1   // nulls always last
                bTs == null -> -1
                direction == SortDirection.DESCENDING -> bTs.compareTo(aTs)
                else -> aTs.compareTo(bTs)
            }
        }
        ProjectSortKey.NAME -> Comparator { a, b ->
            val cmp = a.name.compareTo(b.name, ignoreCase = true)
            if (direction == SortDirection.DESCENDING) -cmp else cmp
        }
        ProjectSortKey.BEAD_TYPES -> Comparator { a, b ->
            val cmp = a.colorMapping.size.compareTo(b.colorMapping.size)
            if (direction == SortDirection.DESCENDING) -cmp else cmp
        }
        ProjectSortKey.GRID_SIZE -> Comparator { a, b ->
            when {
                a.rowCount == 0 && b.rowCount == 0 -> 0
                a.rowCount == 0 -> 1   // no-grid always last
                b.rowCount == 0 -> -1
                direction == SortDirection.DESCENDING -> b.rowCount.compareTo(a.rowCount)
                else -> a.rowCount.compareTo(b.rowCount)
            }
        }
        ProjectSortKey.SATISFACTION -> Comparator { a, b ->
            val aSat = satisfaction[a.projectId]
            val bSat = satisfaction[b.projectId]
            when {
                aSat == null && bSat == null -> 0
                aSat == null -> 1   // null always last
                bSat == null -> -1
                else -> {
                    // totalCount is always positive here: computeProjectSatisfaction returns null
                    // for empty beadStatuses, so the null-sentinel branch above handles that path.
                    val aRatio = (aSat.totalCount - aSat.deficitCount).toDouble() / aSat.totalCount
                    val bRatio = (bSat.totalCount - bSat.deficitCount).toDouble() / bSat.totalCount
                    val cmp = aRatio.compareTo(bRatio)
                    if (direction == SortDirection.DESCENDING) -cmp else cmp
                }
            }
        }
    }
}
