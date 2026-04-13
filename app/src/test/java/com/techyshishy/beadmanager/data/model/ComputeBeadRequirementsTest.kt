package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeBeadRequirementsTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun row(id: Int, vararg steps: Pair<String, Int>): ProjectRgpRow =
        ProjectRgpRow(
            id = id,
            steps = steps.mapIndexed { i, (desc, count) ->
                ProjectRgpStep(id = i + 1, count = count, description = desc)
            },
        )

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `empty rows returns empty map`() {
        val result = computeBeadRequirements(emptyList(), mapOf("A" to "DB0001"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty colorMapping returns empty map`() {
        val result = computeBeadRequirements(listOf(row(1, "A" to 10)), emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `hex-only colorMapping returns empty map`() {
        val result = computeBeadRequirements(
            rows = listOf(row(1, "A" to 100)),
            colorMapping = mapOf("A" to "#ff0000ff"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single row single bead converts count to grams correctly`() {
        // 208 beads = 1 gram exactly; 208 steps for key A → 1.0g
        val result = computeBeadRequirements(
            rows = listOf(row(1, "A" to 208)),
            colorMapping = mapOf("A" to "DB0001"),
        )
        assertEquals(setOf("DB0001"), result.keys)
        assertEquals(1.0, result.getValue("DB0001"), 0.001)
    }

    @Test
    fun `multi-row accumulation — same palette key sums across rows`() {
        // Row 1: A=3, Row 2: A=4 → total 7 steps for DB0001
        val result = computeBeadRequirements(
            rows = listOf(
                row(1, "A" to 3),
                row(2, "A" to 4),
            ),
            colorMapping = mapOf("A" to "DB0001"),
        )
        val expected = (7.0 / BEADS_PER_GRAM * 100.0).roundToLong() / 100.0
        assertEquals(expected, result.getValue("DB0001"), 0.001)
    }

    @Test
    fun `deduplication — two palette keys mapping to the same DB code are merged`() {
        // A=3, B=4 both map to DB0001 → count 7 before grams conversion
        val result = computeBeadRequirements(
            rows = listOf(row(1, "A" to 3, "B" to 4)),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0001"),
        )
        assertEquals(setOf("DB0001"), result.keys)
        val expected = (7.0 / BEADS_PER_GRAM * 100.0).roundToLong() / 100.0
        assertEquals(expected, result.getValue("DB0001"), 0.001)
    }

    @Test
    fun `hex entries are skipped — DB entries still processed`() {
        // A → DB0001 (counted), B → hex (skipped)
        val result = computeBeadRequirements(
            rows = listOf(row(1, "A" to 100, "B" to 50)),
            colorMapping = mapOf("A" to "DB0001", "B" to "#aabbccff"),
        )
        assertEquals(setOf("DB0001"), result.keys)
    }

    @Test
    fun `palette keys absent from any step produce no output`() {
        // colorMapping has "B" → DB0002, but no step uses "B"
        val result = computeBeadRequirements(
            rows = listOf(row(1, "A" to 10)),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
        )
        assertEquals(setOf("DB0001"), result.keys)
    }

    @Test
    fun `multi-bead multi-row — each code accumulates independently`() {
        // A=100, B=50 in row 1; A=50, B=100 in row 2 → A total 150, B total 150
        val result = computeBeadRequirements(
            rows = listOf(
                row(1, "A" to 100, "B" to 50),
                row(2, "A" to 50, "B" to 100),
            ),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
        )
        assertEquals(setOf("DB0001", "DB0002"), result.keys)
        val expected = (150.0 / BEADS_PER_GRAM * 100.0).roundToLong() / 100.0
        assertEquals(expected, result.getValue("DB0001"), 0.001)
        assertEquals(expected, result.getValue("DB0002"), 0.001)
    }
}
