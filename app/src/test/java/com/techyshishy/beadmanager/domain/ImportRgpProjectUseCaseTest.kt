package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectImageRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class ImportRgpProjectUseCaseTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Gzip-compresses [json] and wraps it as a [ByteArrayInputStream]. */
    private fun gzipStream(json: String): ByteArrayInputStream {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return ByteArrayInputStream(bos.toByteArray())
    }

    private fun catalogWith(vararg codes: String): CatalogRepository = mockk {
        val beadMap: Map<String, BeadEntity> = codes.associateWith { mockk(relaxed = true) }
        coEvery { allBeadsAsMap() } returns beadMap
    }

    /**
     * Runs the full import pipeline.
     * Captures the [ProjectEntry] passed to [ProjectRepository.createProject] and the
     * [List<ProjectRgpRow>] passed to [ProjectRepository.writeProjectGrid] into the
     * supplied slots so tests can assert on both.
     *
     * [projectImageRepository] and [generatePreview] default to relaxed mocks so existing
     * tests that do not care about cover generation continue to pass without changes.
     */
    private suspend fun runImport(
        json: String,
        catalog: CatalogRepository,
        capturedEntry: io.mockk.CapturingSlot<ProjectEntry>,
        capturedRows: io.mockk.CapturingSlot<List<ProjectRgpRow>>,
        projectImageRepository: ProjectImageRepository = mockk(relaxed = true),
        generatePreview: GenerateProjectPreviewUseCase = mockk(relaxed = true),
    ): ImportResult {
        val uri: Uri = mockk(relaxed = true)
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns gzipStream(json)
        }
        val projectRepository: ProjectRepository = mockk {
            coEvery { createProject(capture(capturedEntry)) } returns "proj-123"
            coEvery { writeProjectGrid("proj-123", capture(capturedRows)) } returns Unit
            coEvery { setProjectImageUrl(any(), any()) } returns Unit
        }
        return ImportRgpProjectUseCase(
            contentResolver = contentResolver,
            catalogRepository = catalog,
            projectRepository = projectRepository,
            projectImageRepository = projectImageRepository,
            generateProjectPreview = generatePreview,
        ).import(uri)
    }

    // ── JSON fixtures ─────────────────────────────────────────────────────────

    /** Full rowguide-style file: two rows, colorMapping, position, markedSteps, markedRows. */
    private val fullGridJson = """
        {
          "id": 1,
          "name": "Test Pattern",
          "rows": [
            { "id": 1, "steps": [
                { "id": 1, "count": 3, "description": "A" },
                { "id": 2, "count": 2, "description": "B" }
            ]},
            { "id": 2, "steps": [
                { "id": 3, "count": 4, "description": "A" }
            ]}
          ],
          "colorMapping": { "A": "DB0001", "B": "DB0002" },
          "position": { "row": 1, "step": 0 },
          "markedSteps": { "1": { "1": 2, "2": 1 } },
          "markedRows": { "1": 3 }
        }
    """.trimIndent()

    /**
     * Minimal pxlpxl-style file: rows + colorMapping only.
     * position, markedSteps, and markedRows are absent.
     */
    private val pxlpxlJson = """
        {
          "id": 2,
          "name": "Pixel Art",
          "rows": [
            { "id": 1, "steps": [
                { "id": 1, "count": 5, "description": "A" }
            ]}
          ],
          "colorMapping": { "A": "DB0001" }
        }
    """.trimIndent()

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `rows and colorMapping land in created ProjectEntry`() = runTest {
        val captured = slot<ProjectEntry>()
        val capturedRows = slot<List<ProjectRgpRow>>()
        val result = runImport(fullGridJson, catalogWith("DB0001", "DB0002"), captured, capturedRows)

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        val entry = captured.captured
        val rows = capturedRows.captured

        // colorMapping round-trips verbatim
        assertEquals(mapOf("A" to "DB0001", "B" to "DB0002"), entry.colorMapping)

        // rows are passed to writeProjectGrid (not inline in ProjectEntry)
        assertEquals(2, rows.size)
        val row1 = rows[0]
        assertEquals(1, row1.id)
        assertEquals(2, row1.steps.size)
        assertEquals(1, row1.steps[0].id)
        assertEquals(3, row1.steps[0].count)
        assertEquals("A", row1.steps[0].description)
        assertEquals(2, row1.steps[1].id)
        assertEquals(2, row1.steps[1].count)
        assertEquals("B", row1.steps[1].description)

        val row2 = rows[1]
        assertEquals(2, row2.id)
        assertEquals(1, row2.steps.size)
        assertEquals(3, row2.steps[0].id)
        assertEquals(4, row2.steps[0].count)
        assertEquals("A", row2.steps[0].description)
    }

    @Test
    fun `position, markedSteps, markedRows are forwarded when present`() = runTest {
        val captured = slot<ProjectEntry>()
        val capturedRows = slot<List<ProjectRgpRow>>()
        val result = runImport(fullGridJson, catalogWith("DB0001", "DB0002"), captured, capturedRows)

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        val entry = captured.captured

        assertEquals(mapOf("row" to 1, "step" to 0), entry.position)
        assertEquals(mapOf("1" to mapOf("1" to 2, "2" to 1)), entry.markedSteps)
        assertEquals(mapOf("1" to 3), entry.markedRows)
    }

    @Test
    fun `pxlpxl file without rowguide fields imports cleanly with empty maps`() = runTest {
        val captured = slot<ProjectEntry>()
        val capturedRows = slot<List<ProjectRgpRow>>()
        val result = runImport(pxlpxlJson, catalogWith("DB0001"), captured, capturedRows)

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        val entry = captured.captured

        // Optional fields absent in the JSON → empty maps (not null) in the stored entry
        assertTrue("position should be empty map", entry.position.isEmpty())
        assertTrue("markedSteps should be empty map", entry.markedSteps.isEmpty())
        assertTrue("markedRows should be empty map", entry.markedRows.isEmpty())

        // Core grid fields still correct
        assertEquals("Pixel Art", entry.name)
        assertEquals(mapOf("A" to "DB0001"), entry.colorMapping)
        val rows = capturedRows.captured
        assertEquals(1, rows.size)
        assertEquals(5, rows[0].steps[0].count)
    }

    @Test
    fun `grid write failure returns WriteError and deletes orphaned project`() = runTest {
        val uri: Uri = mockk(relaxed = true)
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns gzipStream(pxlpxlJson)
        }
        val projectRepository: ProjectRepository = mockk {
            coEvery { createProject(any()) } returns "proj-orphan"
            coEvery { writeProjectGrid(any(), any()) } throws RuntimeException("network error")
            coEvery { deleteProject("proj-orphan") } returns Unit
        }
        val result = ImportRgpProjectUseCase(
            contentResolver = contentResolver,
            catalogRepository = catalogWith("DB0001"),
            projectRepository = projectRepository,
            projectImageRepository = mockk(relaxed = true),
            generateProjectPreview = mockk(relaxed = true),
        ).import(uri)

        assertEquals(ImportResult.Failure.WriteError, result)
        coVerify(exactly = 1) { projectRepository.deleteProject("proj-orphan") }
    }

    // ── cover image generation tests ──────────────────────────────────────────

    @Test
    fun `embedded image field uploads bytes directly and skips grid render`() = runTest {
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
        val jsonWithImage = """
            {
              "id": 1,
              "name": "Cover Pattern",
              "rows": [{"id":1,"steps":[{"id":1,"count":2,"description":"A"}]}],
              "colorMapping": {"A": "DB0001"},
              "image": "$imageBase64"
            }
        """.trimIndent()
        val capturedBytes = slot<ByteArray>()
        val imageRepo: ProjectImageRepository = mockk {
            coEvery { uploadCoverBytes("proj-cover", capture(capturedBytes)) } returns "https://cdn.example.com/cover"
        }
        val previewUseCase: GenerateProjectPreviewUseCase = mockk(relaxed = true)
        val uri: Uri = mockk(relaxed = true)
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns gzipStream(jsonWithImage)
        }
        val projectRepository: ProjectRepository = mockk {
            coEvery { createProject(any()) } returns "proj-cover"
            coEvery { writeProjectGrid("proj-cover", any()) } returns Unit
            coEvery { setProjectImageUrl("proj-cover", "https://cdn.example.com/cover") } returns Unit
        }
        val result = ImportRgpProjectUseCase(
            contentResolver = contentResolver,
            catalogRepository = catalogWith("DB0001"),
            projectRepository = projectRepository,
            projectImageRepository = imageRepo,
            generateProjectPreview = previewUseCase,
        ).import(uri)

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertTrue(
            "Uploaded bytes do not match embedded image bytes",
            imageBytes.contentEquals(capturedBytes.captured),
        )
        coVerify(exactly = 1) { projectRepository.setProjectImageUrl("proj-cover", "https://cdn.example.com/cover") }
        coVerify(exactly = 0) { previewUseCase.render(any(), any(), any()) }
    }

    @Test
    fun `no embedded image renders grid and uploads cover`() = runTest {
        val renderedBytes = byteArrayOf(10, 20, 30)
        val previewUseCase: GenerateProjectPreviewUseCase = mockk {
            coEvery { render(any(), any(), any()) } returns renderedBytes
        }
        val imageRepo: ProjectImageRepository = mockk {
            coEvery { uploadCoverBytes("proj-grid", renderedBytes) } returns "https://cdn.example.com/rendered"
        }
        val uri: Uri = mockk(relaxed = true)
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns gzipStream(fullGridJson)
        }
        val projectRepository: ProjectRepository = mockk {
            coEvery { createProject(any()) } returns "proj-grid"
            coEvery { writeProjectGrid("proj-grid", any()) } returns Unit
            coEvery { setProjectImageUrl("proj-grid", "https://cdn.example.com/rendered") } returns Unit
        }
        val result = ImportRgpProjectUseCase(
            contentResolver = contentResolver,
            catalogRepository = catalogWith("DB0001", "DB0002"),
            projectRepository = projectRepository,
            projectImageRepository = imageRepo,
            generateProjectPreview = previewUseCase,
        ).import(uri)

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        coVerify(exactly = 1) { previewUseCase.render(any(), any(), any()) }
        coVerify(exactly = 1) { imageRepo.uploadCoverBytes("proj-grid", renderedBytes) }
        coVerify(exactly = 1) { projectRepository.setProjectImageUrl("proj-grid", "https://cdn.example.com/rendered") }
    }

    @Test
    fun `cover render failure still returns Success`() = runTest {
        val previewUseCase: GenerateProjectPreviewUseCase = mockk {
            coEvery { render(any(), any(), any()) } throws RuntimeException("render failed")
        }
        val imageRepo: ProjectImageRepository = mockk(relaxed = true)
        val capturedEntry = slot<ProjectEntry>()
        val capturedRows = slot<List<ProjectRgpRow>>()
        val result = runImport(
            fullGridJson,
            catalogWith("DB0001", "DB0002"),
            capturedEntry,
            capturedRows,
            projectImageRepository = imageRepo,
            generatePreview = previewUseCase,
        )

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        coVerify(exactly = 0) { imageRepo.uploadCoverBytes(any(), any()) }
    }
}
