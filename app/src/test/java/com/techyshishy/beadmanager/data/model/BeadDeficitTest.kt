package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeadDeficitTest {

    private val globalThreshold = 5.0

    // ── effectiveThresholdFor ────────────────────────────────────────────────

    @Test
    fun `effectiveThresholdFor returns per-bead override when set`() {
        val entry = InventoryEntry(beadCode = "DB0001", lowStockThresholdGrams = 10.0)
        assertEquals(10.0, effectiveThresholdFor(entry, globalThreshold), 0.0001)
    }

    @Test
    fun `effectiveThresholdFor falls back to global threshold when per-bead is zero`() {
        val entry = InventoryEntry(beadCode = "DB0001", lowStockThresholdGrams = 0.0)
        assertEquals(globalThreshold, effectiveThresholdFor(entry, globalThreshold), 0.0001)
    }

    @Test
    fun `effectiveThresholdFor falls back to global threshold when entry is null`() {
        assertEquals(globalThreshold, effectiveThresholdFor(null, globalThreshold), 0.0001)
    }

    // ── effectiveDeficitFor ──────────────────────────────────────────────────

    @Test
    fun `effectiveDeficitFor returns zero when inventory covers target plus threshold`() {
        val bead = ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0)
        val entry = InventoryEntry(beadCode = "DB0001", quantityGrams = 16.0)
        // deficit = max(0, 10 + 5 - 16) = 0
        assertEquals(0.0, effectiveDeficitFor(bead, entry, globalThreshold), 0.0001)
    }

    @Test
    fun `effectiveDeficitFor returns positive deficit when inventory is short`() {
        val bead = ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0)
        val entry = InventoryEntry(beadCode = "DB0001", quantityGrams = 8.0)
        // deficit = max(0, 10 + 5 - 8) = 7
        assertEquals(7.0, effectiveDeficitFor(bead, entry, globalThreshold), 0.0001)
    }

    @Test
    fun `effectiveDeficitFor returns zero when deficit is below floating-point noise floor`() {
        val bead = ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0)
        // Exact: 10 + 5 - 15 = 0. Supply just slightly less to trigger noise-floor guard.
        val entry = InventoryEntry(beadCode = "DB0001", quantityGrams = 14.9995)
        // raw = 10 + 5 - 14.9995 = 0.0005, still below SUFFICIENT_THRESHOLD_GRAMS (0.001)
        assertEquals(0.0, effectiveDeficitFor(bead, entry, globalThreshold), 0.0001)
    }

    @Test
    fun `effectiveDeficitFor with null entry treats inventory as zero`() {
        val bead = ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0)
        // deficit = max(0, 10 + 5 - 0) = 15
        assertEquals(15.0, effectiveDeficitFor(bead, null, globalThreshold), 0.0001)
    }

    // ── computeProjectSatisfaction ───────────────────────────────────────────

    @Test
    fun `computeProjectSatisfaction returns null when bead list is empty`() {
        assertNull(computeProjectSatisfaction(emptyList(), emptyMap(), globalThreshold))
    }

    @Test
    fun `computeProjectSatisfaction returns null when no DB-code beads are present`() {
        val beads = listOf(ProjectBeadEntry(beadCode = "#FF0000", targetGrams = 5.0))
        assertNull(computeProjectSatisfaction(beads, emptyMap(), globalThreshold))
    }

    @Test
    fun `computeProjectSatisfaction returns 0 when all beads are satisfied`() {
        val beads = listOf(
            ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0),
            ProjectBeadEntry(beadCode = "DB0010", targetGrams = 8.0),
        )
        val inventory = mapOf(
            "DB0001" to InventoryEntry(beadCode = "DB0001", quantityGrams = 20.0),
            "DB0010" to InventoryEntry(beadCode = "DB0010", quantityGrams = 20.0),
        )
        assertEquals(0, computeProjectSatisfaction(beads, inventory, globalThreshold))
    }

    @Test
    fun `computeProjectSatisfaction returns 1 when one bead has a deficit`() {
        val beads = listOf(
            ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0),
            ProjectBeadEntry(beadCode = "DB0010", targetGrams = 8.0),
        )
        val inventory = mapOf(
            "DB0001" to InventoryEntry(beadCode = "DB0001", quantityGrams = 20.0),
            // DB0010 missing - effective deficit = 8 + 5 - 0 = 13
        )
        assertEquals(1, computeProjectSatisfaction(beads, inventory, globalThreshold))
    }

    @Test
    fun `computeProjectSatisfaction counts all beads with deficits`() {
        val beads = listOf(
            ProjectBeadEntry(beadCode = "DB0001", targetGrams = 10.0),
            ProjectBeadEntry(beadCode = "DB0010", targetGrams = 8.0),
            ProjectBeadEntry(beadCode = "DB0020", targetGrams = 5.0),
        )
        // All missing from inventory
        assertEquals(3, computeProjectSatisfaction(beads, emptyMap(), globalThreshold))
    }

    @Test
    fun `computeProjectSatisfaction returns 0 for zero-target beads with no inventory threshold`() {
        // A bead with 0g target and no per-bead threshold: 0 + globalThreshold - 0 = 5 (deficit!)
        // Expect: 1 (the bead has a deficit because restocking buffer is applied)
        val beads = listOf(ProjectBeadEntry(beadCode = "DB0001", targetGrams = 0.0))
        assertEquals(1, computeProjectSatisfaction(beads, emptyMap(), globalThreshold))
    }

    @Test
    fun `computeProjectSatisfaction uses per-bead threshold override`() {
        val beads = listOf(ProjectBeadEntry(beadCode = "DB0001", targetGrams = 0.0))
        val inventory = mapOf(
            "DB0001" to InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = 3.0,
                lowStockThresholdGrams = 2.0,
            ),
        )
        // deficit = max(0, 0 + 2 - 3) = 0 — satisfied because per-bead override is lower
        assertEquals(0, computeProjectSatisfaction(beads, inventory, globalThreshold))
    }
}
