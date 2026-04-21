package com.techyshishy.beadmanager.data.model

/**
 * Horizontal bead-to-bead pitch (mm) for Miyuki Delica 11/0 in flat peyote stitch.
 *
 * Equal to the empirically measured tube length (~1.29 mm). Matches the
 * BEAD_WIDTH_PX pixel scale used by GenerateProjectPreviewUseCase.
 */
const val DELICA_PEYOTE_HORIZONTAL_PITCH_MM = 1.29

/**
 * Vertical bead-to-bead pitch (mm) for Miyuki Delica 11/0 in flat peyote stitch,
 * including thread tension (~1.73 mm empirically measured).
 *
 * Matches the BEAD_HEIGHT_PX pixel scale used by GenerateProjectPreviewUseCase.
 */
const val DELICA_PEYOTE_VERTICAL_PITCH_MM = 1.73

/**
 * Summary statistics computed from a project's bead grid.
 *
 * Produced by [computeGridSummary] and exposed to the UI as a non-Firestore derived value.
 * All fields are computed from the grid rows and [com.techyshishy.beadmanager.data.firestore.ProjectEntry.colorMapping];
 * nothing is stored in Firestore.
 *
 * The RGP format stores flat peyote stitch patterns using paired buffer rows: even buffer
 * rows run left-to-right and odd buffer rows run right-to-left, interleaving to fill the
 * full visual width. Two buffer rows together form one visual row.
 *
 * [totalBeads] — sum of all step counts for palette keys present in colorMapping.
 * [totalColors] — number of palette entries in colorMapping (not distinct bead codes;
 *   two keys mapping to the same DB code count as two colors).
 * [rowCount] — number of buffer rows (from [com.techyshishy.beadmanager.data.firestore.ProjectEntry.rowCount]).
 *   Visual row count is [visualRowCount].
 * [maxBeadsWide] — bead count of the widest buffer row (sum of step counts, not number of steps).
 *   Visual column count is [visualColumnCount].
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
    /**
     * Visual row count for display. Two buffer rows form one visual row in flat peyote
     * stitch, so this is [rowCount] / 2 (integer division).
     */
    val visualRowCount: Int get() = rowCount / 2

    /**
     * Visual column count for display. Even and odd buffer rows interleave to fill the
     * full width, so the visual column count is [maxBeadsWide] * 2.
     */
    val visualColumnCount: Int get() = maxBeadsWide * 2

    /** Approximate pattern width in mm, using flat peyote stitch geometry. */
    val widthMm: Double get() = visualColumnCount * DELICA_PEYOTE_HORIZONTAL_PITCH_MM

    /** Approximate pattern height in mm, using flat peyote stitch geometry. */
    val heightMm: Double get() = (visualRowCount + 0.5) * DELICA_PEYOTE_VERTICAL_PITCH_MM
}
