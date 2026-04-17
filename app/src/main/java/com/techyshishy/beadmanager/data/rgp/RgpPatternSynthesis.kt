package com.techyshishy.beadmanager.data.rgp

import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep

/**
 * Synthesizes a peyote-stitch bead pattern from a color mapping for projects that have no
 * persisted grid rows (e.g. projects assembled bead-by-bead via the FAB flow).
 *
 * The synthesized grid is sized at `n` buffer columns × `2n` buffer rows, where `n` is the
 * number of distinct bead colors. This produces a visual canvas of `n` peyote column-pairs
 * by `n` bead rows — roughly square — which shows each color at least once per cycle.
 *
 * The pattern is a diagonal stripe: the color assigned to buffer position (bx, by) is
 * `colorKeys[(bx + by/2) % n]`, where `by/2` is integer division (= the visual bead row).
 * In the peyote render, adjacent column-pairs are offset vertically by half a bead, so each
 * color band slopes gently from upper-left to lower-right across the grid.
 *
 * Even buffer rows are stored right-to-left in the RGP format to match pxlpxl's expectation:
 * pxlpxl's `importRgp` reverses even rows (0-indexed) when building the pixel buffer, so we
 * must pre-reverse them here. Odd rows are stored left-to-right.
 *
 * Colors are keyed by sorted key order for determinism: the same project always produces the
 * same exported pattern regardless of the map's internal iteration order.
 *
 * RLE step counts are always 1 with this pattern: the diagonal shift guarantees a color
 * change at every column position, so no runs of identical beads ever arise in a row.
 *
 * Returns an empty list if [colorMapping] is empty.
 */
fun synthesizeStripeRows(colorMapping: Map<String, String>): List<ProjectRgpRow> {
    if (colorMapping.isEmpty()) return emptyList()

    val colorKeys = colorMapping.keys.sorted()
    val n = colorKeys.size
    val bufferWidth = n
    val bufferHeight = n * 2

    return (0 until bufferHeight).map { by ->
        val isEvenRow = by % 2 == 0
        val visualRow = by / 2

        // Traverse columns in the storage direction for this row.
        // Even rows are stored RTL; odd rows are stored LTR.
        val columnRange = if (isEvenRow) {
            (bufferWidth - 1) downTo 0
        } else {
            0 until bufferWidth
        }

        val steps = mutableListOf<ProjectRgpStep>()
        var stepId = 1
        var currentKey: String? = null
        var currentCount = 0

        for (bx in columnRange) {
            val key = colorKeys[(bx + visualRow) % n]
            if (key == currentKey) {
                currentCount++
            } else {
                if (currentKey != null) {
                    steps += ProjectRgpStep(id = stepId++, count = currentCount, description = currentKey)
                }
                currentKey = key
                currentCount = 1
            }
        }
        if (currentKey != null) {
            steps += ProjectRgpStep(id = stepId, count = currentCount, description = currentKey)
        }

        ProjectRgpRow(id = by + 1, steps = steps)
    }
}
