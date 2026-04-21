package com.techyshishy.beadmanager.data.model

/** Approximate Miyuki Delica 11/0 bead width in mm (tube-axis / horizontal pitch). */
const val DELICA_BEAD_WIDTH_MM = 1.3

/** Approximate Miyuki Delica 11/0 bead height in mm (vertical pitch). */
const val DELICA_BEAD_HEIGHT_MM = 1.6

/**
 * Summary statistics computed from a project's bead grid.
 *
 * Produced by [computeGridSummary] and exposed to the UI as a non-Firestore derived value.
 * All fields are computed from the grid rows and [com.techyshishy.beadmanager.data.firestore.ProjectEntry.colorMapping];
 * nothing is stored in Firestore.
 *
 * [totalBeads] — sum of all step counts for palette keys present in colorMapping.
 * [totalColors] — number of palette entries in colorMapping (not distinct bead codes;
 *   two keys mapping to the same DB code count as two colors).
 * [rowCount] — number of rows in the pattern (from [com.techyshishy.beadmanager.data.firestore.ProjectEntry.rowCount]).
 * [maxBeadsWide] — total bead count of the widest row (sum of step counts, not number of steps).
 * [beadCountsByKey] — maps each palette letter key to its total step count across all rows.
 *   Only keys present in colorMapping and appearing in at least one grid step are included.
 */
data class GridSummary(
    val totalBeads: Int,
    val totalColors: Int,
    val rowCount: Int,
    val maxBeadsWide: Int,
    val beadCountsByKey: Map<String, Int>,
) {
    /** Approximate pattern width in mm, assuming [DELICA_BEAD_WIDTH_MM] per bead. */
    val widthMm: Double get() = maxBeadsWide * DELICA_BEAD_WIDTH_MM

    /** Approximate pattern height in mm, assuming [DELICA_BEAD_HEIGHT_MM] per bead. */
    val heightMm: Double get() = rowCount * DELICA_BEAD_HEIGHT_MM
}
