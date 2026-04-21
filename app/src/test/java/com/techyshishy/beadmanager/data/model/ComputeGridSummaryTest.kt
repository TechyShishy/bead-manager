package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeGridSummaryTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun row(id: Int, vararg steps: Pair<String, Int>): ProjectRgpRow =
        ProjectRgpRow(
            id = id,
            steps = steps.mapIndexed { i, (desc, count) ->
                ProjectRgpStep(id = i + 1, count = count, description = desc)
            },
        )

    // ── null cases ────────────────────────────────────────────────────────────

    @Test
    fun `returns null when rowCount is zero`() {
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 10)),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 0,
        )
        assertNull(result)
    }

    @Test
    fun `returns null when rows list is empty`() {
        val result = computeGridSummary(
            rows = emptyList(),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 5,
        )
        assertNull(result)
    }

    // ── totalBeads ────────────────────────────────────────────────────────────

    @Test
    fun `totalBeads is the sum of all step counts across all rows`() {
        val result = computeGridSummary(
            rows = listOf(
                row(1, "A" to 3, "B" to 7),
                row(2, "A" to 5, "C" to 2),
            ),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002", "C" to "DB0003"),
            rowCount = 2,
        )!!
        assertEquals(17, result.totalBeads)
    }

    @Test
    fun `totalBeads is zero when all steps have zero count`() {
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 0, "B" to 0)),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
            rowCount = 1,
        )!!
        assertEquals(0, result.totalBeads)
    }

    // ── beadCountsByKey ───────────────────────────────────────────────────────

    @Test
    fun `beadCountsByKey accumulates counts for the same key across rows`() {
        val result = computeGridSummary(
            rows = listOf(
                row(1, "A" to 4),
                row(2, "A" to 6),
            ),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 2,
        )!!
        assertEquals(10, result.beadCountsByKey["A"])
    }

    @Test
    fun `beadCountsByKey tracks each palette key independently`() {
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 3, "B" to 7)),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
            rowCount = 1,
        )!!
        assertEquals(3, result.beadCountsByKey["A"])
        assertEquals(7, result.beadCountsByKey["B"])
    }

    @Test
    fun `beadCountsByKey excludes steps with empty description`() {
        val result = computeGridSummary(
            rows = listOf(
                ProjectRgpRow(
                    id = 1,
                    steps = listOf(
                        ProjectRgpStep(id = 1, count = 5, description = "A"),
                        ProjectRgpStep(id = 2, count = 99, description = ""),
                    ),
                )
            ),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 1,
        )!!
        assertEquals(5, result.totalBeads)
        assertEquals(setOf("A"), result.beadCountsByKey.keys)
    }

    @Test
    fun `totalBeads excludes steps with keys absent from colorMapping`() {
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 10, "UNKNOWN" to 5)),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 1,
        )!!
        assertEquals(10, result.totalBeads)
        assertTrue("UNKNOWN" !in result.beadCountsByKey)
    }

    @Test
    fun `beadCountsByKey excludes keys absent from all steps`() {
        // colorMapping has "B" but no step uses "B"
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 10)),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
            rowCount = 1,
        )!!
        assertTrue("B should not appear in beadCountsByKey", "B" !in result.beadCountsByKey)
    }

    // ── totalColors ───────────────────────────────────────────────────────────

    @Test
    fun `totalColors equals the number of colorMapping entries`() {
        val colorMapping = mapOf("A" to "DB0001", "B" to "DB0002", "C" to "#aabbcc")
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 1, "B" to 1, "C" to 1)),
            colorMapping = colorMapping,
            rowCount = 1,
        )!!
        assertEquals(3, result.totalColors)
    }

    // ── rowCount and maxBeadsWide ──────────────────────────────────────────────

    @Test
    fun `rowCount is passed through from the parameter`() {
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 5)),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 42,
        )!!
        assertEquals(42, result.rowCount)
    }

    @Test
    fun `maxBeadsWide is the sum of step counts in the widest row`() {
        // Row 1: A=3, B=4 → 7 beads wide. Row 2: A=10, B=15, C=5 → 30 beads wide.
        val result = computeGridSummary(
            rows = listOf(
                row(1, "A" to 3, "B" to 4),
                row(2, "A" to 10, "B" to 15, "C" to 5),
                row(3, "A" to 1),
            ),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002", "C" to "DB0003"),
            rowCount = 3,
        )!!
        assertEquals(30, result.maxBeadsWide)
    }

    // ── physical dimensions ───────────────────────────────────────────────────

    @Test
    fun `widthMm equals visualColumnCount times horizontal pitch`() {
        // Row with 5 steps each count 2 → maxBeadsWide = 10; visualColumnCount = 20
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 2, "B" to 2, "C" to 2, "D" to 2, "E" to 2)),
            colorMapping = mapOf(
                "A" to "DB0001", "B" to "DB0002", "C" to "DB0003",
                "D" to "DB0004", "E" to "DB0005",
            ),
            rowCount = 10,
        )!!
        assertEquals(10, result.maxBeadsWide)
        assertEquals(20, result.visualColumnCount)
        assertEquals(20 * DELICA_PEYOTE_HORIZONTAL_PITCH_MM, result.widthMm, 0.001)
    }

    @Test
    fun `heightMm equals visualRowCount times vertical pitch`() {
        // rowCount = 20 buffer rows → visualRowCount = 10 visual rows
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 1)),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 20,
        )!!
        assertEquals(10, result.visualRowCount)
        assertEquals((10 + 0.5) * DELICA_PEYOTE_VERTICAL_PITCH_MM, result.heightMm, 0.001)
    }

    @Test
    fun `visualRowCount floors on odd rowCount`() {
        // A peyote grid should always have even rowCount, but verify floor-division
        // behaviour matches the preview renderer for any malformed input.
        val result = computeGridSummary(
            rows = listOf(row(1, "A" to 1)),
            colorMapping = mapOf("A" to "DB0001"),
            rowCount = 7,
        )!!
        assertEquals(3, result.visualRowCount) // floor(7/2), not ceiling
    }
}
