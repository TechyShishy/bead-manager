package com.techyshishy.beadmanager.ui.projects

import com.google.firebase.Timestamp
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.model.ProjectSatisfaction
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSortOrderTest {

    private fun project(
        id: String,
        name: String = "project",
        createdAt: Timestamp? = null,
        lastUpdated: Timestamp? = null,
        colorMappingSize: Int = 0,
        rowCount: Int = 0,
    ): ProjectEntry = ProjectEntry(
        projectId = id,
        name = name,
        createdAt = createdAt,
        lastUpdated = lastUpdated,
        colorMapping = (1..colorMappingSize).associate { "K$it" to "DB000$it" },
        rowCount = rowCount,
    )

    private fun sort(key: ProjectSortKey, direction: SortDirection) =
        ProjectSortOrder(key, direction).comparator()

    // ── CREATED_AT ───────────────────────────────────────────────────────────

    @Test
    fun `CREATED_AT DESCENDING places newer project first`() {
        val older = project("a", createdAt = Timestamp(1_000, 0))
        val newer = project("b", createdAt = Timestamp(2_000, 0))
        val sorted = listOf(older, newer).sortedWith(sort(ProjectSortKey.CREATED_AT, SortDirection.DESCENDING))
        assertEquals(listOf(newer, older), sorted)
    }

    @Test
    fun `CREATED_AT ASCENDING places older project first`() {
        val older = project("a", createdAt = Timestamp(1_000, 0))
        val newer = project("b", createdAt = Timestamp(2_000, 0))
        val sorted = listOf(newer, older).sortedWith(sort(ProjectSortKey.CREATED_AT, SortDirection.ASCENDING))
        assertEquals(listOf(older, newer), sorted)
    }

    @Test
    fun `CREATED_AT DESCENDING places null createdAt after real timestamp`() {
        val withDate = project("a", createdAt = Timestamp(1_000, 0))
        val nullDate = project("b", createdAt = null)
        val sorted = listOf(nullDate, withDate).sortedWith(sort(ProjectSortKey.CREATED_AT, SortDirection.DESCENDING))
        assertEquals(listOf(withDate, nullDate), sorted)
    }

    @Test
    fun `CREATED_AT ASCENDING still places null createdAt after real timestamp`() {
        val withDate = project("a", createdAt = Timestamp(1_000, 0))
        val nullDate = project("b", createdAt = null)
        val sorted = listOf(nullDate, withDate).sortedWith(sort(ProjectSortKey.CREATED_AT, SortDirection.ASCENDING))
        assertEquals(listOf(withDate, nullDate), sorted)
    }

    @Test
    fun `CREATED_AT treats two null createdAt entries as equal`() {
        val p1 = project("a", createdAt = null)
        val p2 = project("b", createdAt = null)
        assertEquals(0, sort(ProjectSortKey.CREATED_AT, SortDirection.DESCENDING).compare(p1, p2))
    }

    // ── NAME ─────────────────────────────────────────────────────────────────

    @Test
    fun `NAME ASCENDING sorts alphabetically`() {
        val alpha = project("a", name = "Alpha")
        val beta = project("b", name = "Beta")
        val gamma = project("c", name = "Gamma")
        val sorted = listOf(gamma, alpha, beta).sortedWith(sort(ProjectSortKey.NAME, SortDirection.ASCENDING))
        assertEquals(listOf(alpha, beta, gamma), sorted)
    }

    @Test
    fun `NAME DESCENDING sorts reverse-alphabetically`() {
        val alpha = project("a", name = "Alpha")
        val beta = project("b", name = "Beta")
        val gamma = project("c", name = "Gamma")
        val sorted = listOf(alpha, gamma, beta).sortedWith(sort(ProjectSortKey.NAME, SortDirection.DESCENDING))
        assertEquals(listOf(gamma, beta, alpha), sorted)
    }

    @Test
    fun `NAME ASCENDING is case-insensitive`() {
        val lower = project("a", name = "alpha")
        val upper = project("b", name = "Beta")
        val sorted = listOf(upper, lower).sortedWith(sort(ProjectSortKey.NAME, SortDirection.ASCENDING))
        assertEquals(listOf(lower, upper), sorted)
    }

    // ── BEAD_TYPES ───────────────────────────────────────────────────────────

    @Test
    fun `BEAD_TYPES DESCENDING places project with more palette entries first`() {
        val few = project("a", colorMappingSize = 2)
        val many = project("b", colorMappingSize = 8)
        val sorted = listOf(few, many).sortedWith(sort(ProjectSortKey.BEAD_TYPES, SortDirection.DESCENDING))
        assertEquals(listOf(many, few), sorted)
    }

    @Test
    fun `BEAD_TYPES ASCENDING places project with fewer palette entries first`() {
        val few = project("a", colorMappingSize = 2)
        val many = project("b", colorMappingSize = 8)
        val sorted = listOf(many, few).sortedWith(sort(ProjectSortKey.BEAD_TYPES, SortDirection.ASCENDING))
        assertEquals(listOf(few, many), sorted)
    }

    @Test
    fun `BEAD_TYPES treats equal palette sizes as equal`() {
        val p1 = project("a", colorMappingSize = 3)
        val p2 = project("b", colorMappingSize = 3)
        assertEquals(0, sort(ProjectSortKey.BEAD_TYPES, SortDirection.DESCENDING).compare(p1, p2))
    }

    // ── GRID_SIZE ─────────────────────────────────────────────────────────────

    @Test
    fun `GRID_SIZE DESCENDING places larger grid first`() {
        val small = project("a", rowCount = 10)
        val large = project("b", rowCount = 100)
        val sorted = listOf(small, large).sortedWith(sort(ProjectSortKey.GRID_SIZE, SortDirection.DESCENDING))
        assertEquals(listOf(large, small), sorted)
    }

    @Test
    fun `GRID_SIZE ASCENDING places smaller grid first`() {
        val small = project("a", rowCount = 10)
        val large = project("b", rowCount = 100)
        val sorted = listOf(large, small).sortedWith(sort(ProjectSortKey.GRID_SIZE, SortDirection.ASCENDING))
        assertEquals(listOf(small, large), sorted)
    }

    @Test
    fun `GRID_SIZE DESCENDING places zero-row projects after grid projects`() {
        val noGrid = project("a", rowCount = 0)
        val withGrid = project("b", rowCount = 20)
        val sorted = listOf(noGrid, withGrid).sortedWith(sort(ProjectSortKey.GRID_SIZE, SortDirection.DESCENDING))
        assertEquals(listOf(withGrid, noGrid), sorted)
    }

    @Test
    fun `GRID_SIZE ASCENDING still places zero-row projects after grid projects`() {
        val noGrid = project("a", rowCount = 0)
        val withGrid = project("b", rowCount = 20)
        val sorted = listOf(noGrid, withGrid).sortedWith(sort(ProjectSortKey.GRID_SIZE, SortDirection.ASCENDING))
        assertEquals(listOf(withGrid, noGrid), sorted)
    }

    @Test
    fun `GRID_SIZE treats two zero-row projects as equal`() {
        val p1 = project("a", rowCount = 0)
        val p2 = project("b", rowCount = 0)
        assertEquals(0, sort(ProjectSortKey.GRID_SIZE, SortDirection.DESCENDING).compare(p1, p2))
    }

    // ── SATISFACTION ─────────────────────────────────────────────────────────

    /**
     * Builds a [ProjectSatisfaction] with [total] beads and [deficit] of them unsatisfied.
     * Returns a map entry keyed by [projectId] for direct use in `mapOf()`.
     */
    private fun satisfaction(
        projectId: String,
        total: Int,
        deficit: Int,
    ): Pair<String, ProjectSatisfaction?> =
        projectId to ProjectSatisfaction(
            (1..total).map { index -> index > deficit },
        )

    private fun sortSatisfaction(
        direction: SortDirection,
        satisfactionMap: Map<String, ProjectSatisfaction?>,
    ) = ProjectSortOrder(ProjectSortKey.SATISFACTION, direction).comparator(satisfactionMap)

    @Test
    fun `SATISFACTION ASCENDING places lower-ratio project first`() {
        val low = project("low")   // 1 of 5 satisfied → ratio 0.2
        val high = project("high") // 4 of 5 satisfied → ratio 0.8
        val sat = mapOf(satisfaction("low", total = 5, deficit = 4), satisfaction("high", total = 5, deficit = 1))
        val sorted = listOf(high, low).sortedWith(sortSatisfaction(SortDirection.ASCENDING, sat))
        assertEquals(listOf(low, high), sorted)
    }

    @Test
    fun `SATISFACTION DESCENDING places higher-ratio project first`() {
        val low = project("low")
        val high = project("high")
        val sat = mapOf(satisfaction("low", total = 5, deficit = 4), satisfaction("high", total = 5, deficit = 1))
        val sorted = listOf(low, high).sortedWith(sortSatisfaction(SortDirection.DESCENDING, sat))
        assertEquals(listOf(high, low), sorted)
    }

    @Test
    fun `SATISFACTION places null satisfaction after non-null regardless of direction`() {
        val withSat = project("a")
        val noSat = project("b")
        val sat = mapOf("a" to ProjectSatisfaction(listOf(true, false)), "b" to null)
        val sortedAsc = listOf(noSat, withSat).sortedWith(sortSatisfaction(SortDirection.ASCENDING, sat))
        val sortedDesc = listOf(noSat, withSat).sortedWith(sortSatisfaction(SortDirection.DESCENDING, sat))
        assertEquals(listOf(withSat, noSat), sortedAsc)
        assertEquals(listOf(withSat, noSat), sortedDesc)
    }

    @Test
    fun `SATISFACTION treats equal ratios as equal`() {
        val p1 = project("a")
        val p2 = project("b")
        val sat = mapOf(
            "a" to ProjectSatisfaction(listOf(true, false)),
            "b" to ProjectSatisfaction(listOf(true, false)),
        )
        assertEquals(0, sortSatisfaction(SortDirection.ASCENDING, sat).compare(p1, p2))
    }

    // ── LAST_UPDATED ──────────────────────────────────────────────────────────

    @Test
    fun `LAST_UPDATED DESCENDING places more recently updated project first`() {
        val older = project("a", lastUpdated = Timestamp(1_000, 0))
        val newer = project("b", lastUpdated = Timestamp(2_000, 0))
        val sorted = listOf(older, newer).sortedWith(sort(ProjectSortKey.LAST_UPDATED, SortDirection.DESCENDING))
        assertEquals(listOf(newer, older), sorted)
    }

    @Test
    fun `LAST_UPDATED ASCENDING places less recently updated project first`() {
        val older = project("a", lastUpdated = Timestamp(1_000, 0))
        val newer = project("b", lastUpdated = Timestamp(2_000, 0))
        val sorted = listOf(newer, older).sortedWith(sort(ProjectSortKey.LAST_UPDATED, SortDirection.ASCENDING))
        assertEquals(listOf(older, newer), sorted)
    }

    @Test
    fun `LAST_UPDATED places null lastUpdated after real timestamps regardless of direction`() {
        val withTs = project("a", lastUpdated = Timestamp(1_000, 0))
        val noTs = project("b", lastUpdated = null)
        val sortedAsc = listOf(noTs, withTs).sortedWith(sort(ProjectSortKey.LAST_UPDATED, SortDirection.ASCENDING))
        val sortedDesc = listOf(noTs, withTs).sortedWith(sort(ProjectSortKey.LAST_UPDATED, SortDirection.DESCENDING))
        assertEquals(listOf(withTs, noTs), sortedAsc)
        assertEquals(listOf(withTs, noTs), sortedDesc)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `all keys and directions handle empty list`() {
        val empty = emptyList<ProjectEntry>()
        for (key in ProjectSortKey.entries) {
            for (direction in SortDirection.entries) {
                assertEquals(emptyList<ProjectEntry>(), empty.sortedWith(sort(key, direction)))
            }
        }
    }
}
