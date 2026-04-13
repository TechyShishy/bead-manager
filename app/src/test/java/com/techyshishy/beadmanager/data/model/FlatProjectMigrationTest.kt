package com.techyshishy.beadmanager.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FlatProjectMigrationTest {

    // ── bijectiveKey ──────────────────────────────────────────────────────────

    @Test
    fun `bijectiveKey maps single-letter range`() {
        assertEquals("A", bijectiveKey(1))
        assertEquals("B", bijectiveKey(2))
        assertEquals("Z", bijectiveKey(26))
    }

    @Test
    fun `bijectiveKey rolls over to double letters at 27`() {
        assertEquals("AA", bijectiveKey(27))
        assertEquals("AB", bijectiveKey(28))
        assertEquals("AZ", bijectiveKey(52))
        assertEquals("BA", bijectiveKey(53))
    }

    // ── nextBijectiveKey ──────────────────────────────────────────────────────

    @Test
    fun `nextBijectiveKey returns A for empty map`() {
        assertEquals("A", nextBijectiveKey(emptyMap()))
    }

    @Test
    fun `nextBijectiveKey skips occupied keys`() {
        val existing = mapOf("A" to "DB0001", "B" to "DB0002")
        assertEquals("C", nextBijectiveKey(existing))
    }

    @Test
    fun `nextBijectiveKey skips non-contiguous occupied keys`() {
        val existing = mapOf("A" to "DB0001", "C" to "DB0003")
        assertEquals("B", nextBijectiveKey(existing))
    }

    // ── synthesizeFlatListGrid ────────────────────────────────────────────────

    @Test
    fun `synthesizeFlatListGrid empty input produces empty output`() {
        val (rows, colorMapping) = synthesizeFlatListGrid(emptyList())
        assertTrue(rows.isEmpty())
        assertTrue(colorMapping.isEmpty())
    }

    @Test
    fun `synthesizeFlatListGrid assigns sequential bijective keys`() {
        val input = listOf("DB0001" to 1.0, "DB0002" to 2.0, "DB0003" to 3.0)
        val (_, colorMapping) = synthesizeFlatListGrid(input)
        assertEquals("DB0001", colorMapping["A"])
        assertEquals("DB0002", colorMapping["B"])
        assertEquals("DB0003", colorMapping["C"])
    }

    @Test
    fun `synthesizeFlatListGrid produces a single row`() {
        val input = listOf("DB0001" to 5.0, "DB0002" to 10.0)
        val (rows, _) = synthesizeFlatListGrid(input)
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].steps.size)
    }

    // ── round-trip: synthesizeFlatListGrid → computeBeadRequirements ──────────

    @Test
    fun `round-trip preserves bead gram requirements within tolerance`() {
        val input = listOf(
            "DB0001" to 1.0,
            "DB0002" to 2.5,
            "DB0003" to 10.0,
        )
        val (rows, colorMapping) = synthesizeFlatListGrid(input)
        val result = computeBeadRequirements(rows, colorMapping)

        for ((code, expectedGrams) in input) {
            val actualGrams = result[code]
            assertTrue("$code not found in computed result", actualGrams != null)
            val delta = abs(actualGrams!! - expectedGrams)
            assertTrue(
                "$code: expected ${expectedGrams}g, got ${actualGrams}g, delta ${delta}g exceeds tolerance",
                delta < 0.01,
            )
        }
    }

    @Test
    fun `round-trip result set matches input bead codes exactly`() {
        val input = listOf("DB0011" to 3.0, "DB0022" to 7.5, "DB0033" to 1.2)
        val (rows, colorMapping) = synthesizeFlatListGrid(input)
        val result = computeBeadRequirements(rows, colorMapping)
        assertEquals(input.map { it.first }.toSet(), result.keys)
    }
}
