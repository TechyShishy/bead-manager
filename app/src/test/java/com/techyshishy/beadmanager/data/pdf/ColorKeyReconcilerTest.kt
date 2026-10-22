package com.techyshishy.beadmanager.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorKeyReconcilerTest {

    private fun key(vararg letters: String) = letters.associateWith { "DB-$it" }

    private val used = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I")

    private val tally = mapOf(
        "A" to 100, "B" to 200, "C" to 300, "D" to 400, "E" to 500,
        "F" to 600, "G" to 700, "H" to 800, "I" to 900,
    )

    // Stray XH (an OCR-garbled H) sits last in the read order, so it has no following
    // neighbor and the neighbor evidence abstains.
    private val strayAtColumnEnd = key("A", "D", "G", "B", "E", "C", "F", "I", "XH")

    // Stray XH sits between G and I in read order.
    private val strayBetweenNeighbors = key("A", "B", "C", "D", "E", "F", "G", "XH", "I")

    @Test
    fun `relabels a stray when neighbors and counts both identify the missing letter`() {
        val result = ColorKeyReconciler.reconcile(
            strayBetweenNeighbors, tally.values.toList(), used, listOf(tally),
        )
        assertEquals(mapOf("XH" to "H"), result.corrections)
        assertEquals("DB-XH", result.colorMap["H"])
        assertTrue("XH" !in result.colorMap)
    }

    @Test
    fun `relabeled entry keeps its position in read order`() {
        val result = ColorKeyReconciler.reconcile(
            strayBetweenNeighbors, tally.values.toList(), used, listOf(tally),
        )
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H", "I"), result.colorMap.keys.toList())
    }

    @Test
    fun `resolves by neighbor order alone when no counts were read`() {
        val result = ColorKeyReconciler.reconcile(strayBetweenNeighbors, emptyList(), used, listOf(tally))
        assertEquals(mapOf("XH" to "H"), result.corrections)
    }

    @Test
    fun `resolves by counts alone when neighbors abstain`() {
        val result = ColorKeyReconciler.reconcile(
            strayAtColumnEnd, tally.values.toList(), used, listOf(tally),
        )
        assertEquals(mapOf("XH" to "H"), result.corrections)
    }

    @Test
    fun `leaves stray uncorrected when no evidence can run`() {
        val result = ColorKeyReconciler.reconcile(strayAtColumnEnd, emptyList(), used, listOf(tally))
        assertTrue(result.corrections.isEmpty())
        assertEquals(strayAtColumnEnd, result.colorMap)
    }

    @Test
    fun `leaves stray uncorrected when counts do not support the candidate`() {
        val wrongCounts = tally.values.map { if (it == 800) 801 else it }
        val result = ColorKeyReconciler.reconcile(strayAtColumnEnd, wrongCounts, used, listOf(tally))
        assertTrue(result.corrections.isEmpty())
        assertEquals(strayAtColumnEnd, result.colorMap)
    }

    @Test
    fun `neighbor order vetoes a candidate the counts support`() {
        // Stray sits between B and D, so it can only be C even though I's count was read too.
        val strayBetweenBAndD = key("A", "B", "II", "D", "E", "F", "G", "H")
        val result = ColorKeyReconciler.reconcile(
            strayBetweenBAndD, tally.values.toList(), used, listOf(tally),
        )
        assertEquals(mapOf("II" to "C"), result.corrections)
    }

    @Test
    fun `neighbor order rejects a stray when no missing letter fits between its neighbors`() {
        // Stray sits between G and H, but the only missing letter is C.
        val result = ColorKeyReconciler.reconcile(
            key("A", "B", "D", "E", "F", "G", "II", "H", "I"),
            tally.values.toList(), used, listOf(tally),
        )
        assertTrue(result.corrections.isEmpty())
    }

    @Test
    fun `leaves strays uncorrected when two of them resolve to the same letter`() {
        // Both strays sit between B and D, so both can only be C; missing C and I.
        val result = ColorKeyReconciler.reconcile(
            key("A", "B", "X1", "X2", "D", "E", "F", "G", "H"),
            tally.values.toList(), used, listOf(tally),
        )
        assertTrue(result.corrections.isEmpty())
    }

    @Test
    fun `counts already accounted for by matched letters do not support a candidate`() {
        // H's graph count equals B's; only one 200 was read and matched B consumes it.
        val shared = tally + ("H" to 200)
        val counts = shared.values.toMutableList().also { it.remove(200) }
        val result = ColorKeyReconciler.reconcile(strayAtColumnEnd, counts, used, listOf(shared))
        assertTrue(result.corrections.isEmpty())
    }

    @Test
    fun `does nothing when no letter is missing`() {
        val full = key("A", "B", "C", "D", "E", "F", "G", "H", "I")
        val result = ColorKeyReconciler.reconcile(full, tally.values.toList(), used, listOf(tally))
        assertTrue(result.corrections.isEmpty())
        assertEquals(full, result.colorMap)
    }

    @Test
    fun `counts are matched against any variant`() {
        val variantB = tally + ("H" to 55)
        val result = ColorKeyReconciler.reconcile(
            strayAtColumnEnd, variantB.values.toList(), used, listOf(tally, variantB),
        )
        assertEquals(mapOf("XH" to "H"), result.corrections)
    }
}
