package com.techyshishy.beadmanager.ui.projects

import com.google.firebase.Timestamp
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSortOrderTest {

    private fun project(
        id: String,
        name: String = "project",
        createdAt: Timestamp? = null,
        colorMappingSize: Int = 0,
        rowCount: Int = 0,
    ): ProjectEntry = ProjectEntry(
        projectId = id,
        name = name,
        createdAt = createdAt,
        colorMapping = (1..colorMappingSize).associate { "K$it" to "DB000$it" },
        rowCount = rowCount,
    )

    // ── CREATED_AT_DESCENDING ────────────────────────────────────────────────

    @Test
    fun `CREATED_AT_DESCENDING places newer project first`() {
        val older = project("a", createdAt = Timestamp(1_000, 0))
        val newer = project("b", createdAt = Timestamp(2_000, 0))
        val sorted = listOf(older, newer).sortedWith(ProjectSortOrder.CREATED_AT_DESCENDING.comparator)
        assertEquals(listOf(newer, older), sorted)
    }

    @Test
    fun `CREATED_AT_DESCENDING places null createdAt after real timestamp`() {
        val withDate = project("a", createdAt = Timestamp(1_000, 0))
        val nullDate = project("b", createdAt = null)
        val sorted = listOf(nullDate, withDate).sortedWith(ProjectSortOrder.CREATED_AT_DESCENDING.comparator)
        assertEquals(listOf(withDate, nullDate), sorted)
    }

    @Test
    fun `CREATED_AT_DESCENDING treats two null createdAt entries as equal`() {
        val p1 = project("a", createdAt = null)
        val p2 = project("b", createdAt = null)
        val result = ProjectSortOrder.CREATED_AT_DESCENDING.comparator.compare(p1, p2)
        assertEquals(0, result)
    }

    // ── NAME_ASCENDING ───────────────────────────────────────────────────────

    @Test
    fun `NAME_ASCENDING sorts alphabetically`() {
        val alpha = project("a", name = "Alpha")
        val beta = project("b", name = "Beta")
        val gamma = project("c", name = "Gamma")
        val sorted = listOf(gamma, alpha, beta).sortedWith(ProjectSortOrder.NAME_ASCENDING.comparator)
        assertEquals(listOf(alpha, beta, gamma), sorted)
    }

    @Test
    fun `NAME_ASCENDING is case-insensitive`() {
        val lower = project("a", name = "alpha")
        val upper = project("b", name = "Beta")
        val sorted = listOf(upper, lower).sortedWith(ProjectSortOrder.NAME_ASCENDING.comparator)
        assertEquals(listOf(lower, upper), sorted)
    }

    // ── BEAD_TYPES_DESCENDING ────────────────────────────────────────────────

    @Test
    fun `BEAD_TYPES_DESCENDING places project with more palette entries first`() {
        val few = project("a", colorMappingSize = 2)
        val many = project("b", colorMappingSize = 8)
        val sorted = listOf(few, many).sortedWith(ProjectSortOrder.BEAD_TYPES_DESCENDING.comparator)
        assertEquals(listOf(many, few), sorted)
    }

    @Test
    fun `BEAD_TYPES_DESCENDING treats equal palette sizes as equal`() {
        val p1 = project("a", colorMappingSize = 3)
        val p2 = project("b", colorMappingSize = 3)
        val result = ProjectSortOrder.BEAD_TYPES_DESCENDING.comparator.compare(p1, p2)
        assertEquals(0, result)
    }

    // ── GRID_SIZE_DESCENDING ─────────────────────────────────────────────────

    @Test
    fun `GRID_SIZE_DESCENDING places larger grid first`() {
        val small = project("a", rowCount = 10)
        val large = project("b", rowCount = 100)
        val sorted = listOf(small, large).sortedWith(ProjectSortOrder.GRID_SIZE_DESCENDING.comparator)
        assertEquals(listOf(large, small), sorted)
    }

    @Test
    fun `GRID_SIZE_DESCENDING places zero-row projects after grid projects`() {
        val noGrid = project("a", rowCount = 0)
        val withGrid = project("b", rowCount = 20)
        val sorted = listOf(noGrid, withGrid).sortedWith(ProjectSortOrder.GRID_SIZE_DESCENDING.comparator)
        assertEquals(listOf(withGrid, noGrid), sorted)
    }

    @Test
    fun `GRID_SIZE_DESCENDING treats two zero-row projects as equal`() {
        val p1 = project("a", rowCount = 0)
        val p2 = project("b", rowCount = 0)
        val result = ProjectSortOrder.GRID_SIZE_DESCENDING.comparator.compare(p1, p2)
        assertEquals(0, result)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `all sort orders handle an empty list`() {
        val empty = emptyList<ProjectEntry>()
        for (order in ProjectSortOrder.entries) {
            assertEquals(emptyList<ProjectEntry>(), empty.sortedWith(order.comparator))
        }
    }
}
