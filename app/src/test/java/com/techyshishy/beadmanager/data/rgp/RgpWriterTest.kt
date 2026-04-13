package com.techyshishy.beadmanager.data.rgp

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RgpWriterTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun writeToBytes(project: ProjectEntry): ByteArray {
        val out = ByteArrayOutputStream()
        writeRgp(out, project)
        return out.toByteArray()
    }

    private fun ProjectEntry.writeThenParse(): RgpProject {
        return parseRgp(ByteArrayInputStream(writeToBytes(this)))
    }

    private fun projectEntry(
        projectId: String = "test-id",
        name: String = "Test",
        rows: List<ProjectRgpRow> = emptyList(),
        colorMapping: Map<String, String> = emptyMap(),
        position: Map<String, Int> = emptyMap(),
        markedSteps: Map<String, Map<String, Int>> = emptyMap(),
        markedRows: Map<String, Int> = emptyMap(),
    ) = ProjectEntry(
        projectId = projectId,
        name = name,
        rows = rows,
        colorMapping = colorMapping,
        position = position,
        markedSteps = markedSteps,
        markedRows = markedRows,
    )

    private fun makeRows(vararg rowSteps: Pair<Int, List<Triple<Int, Int, String>>>): List<ProjectRgpRow> =
        rowSteps.map { (rowId, steps) ->
            ProjectRgpRow(
                id = rowId,
                steps = steps.map { (stepId, count, desc) ->
                    ProjectRgpStep(id = stepId, count = count, description = desc)
                },
            )
        }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `round-trip preserves name`() {
        val project = projectEntry(
            name = "My Pattern",
            rows = makeRows(1 to listOf(Triple(1, 5, "A"))),
            colorMapping = mapOf("A" to "DB0001"),
        )
        assertEquals("My Pattern", project.writeThenParse().name)
    }

    @Test
    fun `round-trip preserves rows and colorMapping`() {
        val project = projectEntry(
            rows = makeRows(
                1 to listOf(Triple(1, 3, "A"), Triple(2, 2, "B")),
                2 to listOf(Triple(3, 4, "A")),
            ),
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
        )
        val parsed = project.writeThenParse()

        assertEquals(mapOf("A" to "DB0001", "B" to "DB0002"), parsed.colorMapping)
        assertEquals(2, parsed.rows.size)

        val row1 = parsed.rows[0]
        assertEquals(1, row1.id)
        assertEquals(2, row1.steps.size)
        assertEquals(RgpStep(id = 1, count = 3, description = "A"), row1.steps[0])
        assertEquals(RgpStep(id = 2, count = 2, description = "B"), row1.steps[1])

        val row2 = parsed.rows[1]
        assertEquals(2, row2.id)
        assertEquals(1, row2.steps.size)
        assertEquals(RgpStep(id = 3, count = 4, description = "A"), row2.steps[0])
    }

    @Test
    fun `round-trip preserves non-empty position, markedSteps, markedRows`() {
        val project = projectEntry(
            rows = makeRows(1 to listOf(Triple(1, 1, "A"))),
            colorMapping = mapOf("A" to "DB0001"),
            position = mapOf("row" to 0, "step" to 1),
            markedSteps = mapOf("1" to mapOf("1" to 2)),
            markedRows = mapOf("1" to 3),
        )
        val parsed = project.writeThenParse()

        assertEquals(mapOf("row" to 0, "step" to 1), parsed.position)
        assertEquals(mapOf("1" to mapOf("1" to 2)), parsed.markedSteps)
        assertEquals(mapOf("1" to 3), parsed.markedRows)
    }

    // ── optional-field omission ───────────────────────────────────────────────

    @Test
    fun `empty position, markedSteps, markedRows are absent from JSON output`() {
        val project = projectEntry(
            rows = makeRows(1 to listOf(Triple(1, 1, "A"))),
            colorMapping = mapOf("A" to "DB0001"),
        // position, markedSteps, markedRows left as empty defaults
        )
        val parsed = project.writeThenParse()

        // parseRgp maps absent optional fields to null
        assertTrue("position should be absent/null when empty", parsed.position == null)
        assertTrue("markedSteps should be absent/null when empty", parsed.markedSteps == null)
        assertTrue("markedRows should be absent/null when empty", parsed.markedRows == null)
    }

    // ── djb2 determinism ─────────────────────────────────────────────────────

    @Test
    fun `djb2 is deterministic for the same input`() {
        val id1 = djb2("abc-123-project-id")
        val id2 = djb2("abc-123-project-id")
        assertEquals(id1, id2)
    }

    @Test
    fun `djb2 produces different results for project-alpha and project-beta`() {
        assertFalse("Expected different hashes for different inputs",
            djb2("project-alpha") == djb2("project-beta"))
    }

    @Test
    fun `djb2 hash written to file is deterministic across two writes`() {
        val project = projectEntry(
            projectId = "stable-project-id",
            rows = makeRows(1 to listOf(Triple(1, 1, "A"))),
            colorMapping = mapOf("A" to "DB0001"),
        )
        val id1 = project.writeThenParse().id
        val id2 = project.writeThenParse().id
        assertEquals(id1, id2)
    }
}
