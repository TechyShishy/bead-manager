package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.repository.ProjectImageRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.data.rgp.parseRgp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ExportRgpProjectUseCaseTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private val uri: Uri = mockk(relaxed = true)

    private fun minimalProject(projectId: String = "proj-abc", name: String = "Foo") = ProjectEntry(
        projectId = projectId,
        name = name,
        rowCount = 1,
        colorMapping = mapOf("A" to "DB0001"),
    )

    private val minimalRows = listOf(
        ProjectRgpRow(
            id = 1,
            steps = listOf(ProjectRgpStep(id = 1, count = 5, description = "A")),
        )
    )

    private fun buildUseCase(
        project: ProjectEntry?,
        rows: List<ProjectRgpRow> = minimalRows,
        outputStream: java.io.OutputStream? = ByteArrayOutputStream(),
        projectImageRepository: ProjectImageRepository = mockk {
            coEvery { downloadCoverBytes(any()) } returns null
        },
    ): ExportRgpProjectUseCase {
        val projectRepository: ProjectRepository = mockk {
            every { projectStream(project?.projectId ?: "missing-id") } returns flowOf(project)
            coEvery { readProjectGrid(project?.projectId ?: "missing-id") } returns rows
        }
        val contentResolver: ContentResolver = mockk {
            every { openOutputStream(uri) } returns outputStream
            // query() returns null → use case falls back to "${project.name}.rgp"
            every { query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } returns null
        }
        return ExportRgpProjectUseCase(
            contentResolver = contentResolver,
            projectRepository = projectRepository,
            projectImageRepository = projectImageRepository,
        )
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `returns NotFound when project stream emits null`() = runTest {
        val projectRepository: ProjectRepository = mockk {
            every { projectStream("missing-id") } returns flowOf(null)
        }
        val useCase = ExportRgpProjectUseCase(
            contentResolver = mockk(),
            projectRepository = projectRepository,
            projectImageRepository = mockk(),
        )
        val result = useCase.export("missing-id", uri)
        assertEquals(ExportResult.Failure.NotFound, result)
    }

    @Test
    fun `returns NoGrid when project has no rows and no colorMapping`() = runTest {
        val flatProject = ProjectEntry(projectId = "flat-id", name = "Flat", rowCount = 0)
        val projectRepository: ProjectRepository = mockk {
            every { projectStream("flat-id") } returns flowOf(flatProject)
            coEvery { readProjectGrid("flat-id") } returns emptyList()
        }
        val useCase = ExportRgpProjectUseCase(
            contentResolver = mockk(),
            projectRepository = projectRepository,
            projectImageRepository = mockk(),
        )
        val result = useCase.export("flat-id", uri)
        assertEquals(ExportResult.Failure.NoGrid, result)
    }

    @Test
    fun `returns Success for FAB-created project with colorMapping and no grid rows`() = runTest {
        val fabProject = ProjectEntry(
            projectId = "fab-id",
            name = "Hand-built",
            rowCount = 0,
            colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"),
        )
        val projectRepository: ProjectRepository = mockk {
            every { projectStream("fab-id") } returns flowOf(fabProject)
            coEvery { readProjectGrid("fab-id") } returns emptyList()
        }
        val out = ByteArrayOutputStream()
        val contentResolver: ContentResolver = mockk {
            every { openOutputStream(uri) } returns out
            every { query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } returns null
        }
        val useCase = ExportRgpProjectUseCase(
            contentResolver = contentResolver,
            projectRepository = projectRepository,
            projectImageRepository = mockk { coEvery { downloadCoverBytes(any()) } returns null },
        )
        val result = useCase.export("fab-id", uri)
        assertTrue("Expected Success for FAB project with beads but got $result", result is ExportResult.Success)
        assertEquals("Hand-built.rgp", (result as ExportResult.Success).suggestedFilename)
        assertTrue("Expected non-empty output stream", out.size() > 0)

        // The synthesized output must be a valid RGP with bead rows — not empty rows.
        val parsed = parseRgp(ByteArrayInputStream(out.toByteArray()))
        assertTrue("Expected synthesized rows in exported RGP", parsed.rows.isNotEmpty())
        // n=2 colors → 2n=4 buffer rows, each 2 beads wide
        assertEquals("Expected 4 synthesized rows for 2-color project", 4, parsed.rows.size)
        parsed.rows.forEach { row ->
            val beadsInRow = row.steps.sumOf { it.count }
            assertEquals("Expected 2 beads per row for 2-column buffer", 2, beadsInRow)
        }
    }

    @Test
    fun `returns Success with suggestedFilename derived from project name`() = runTest {
        val project = minimalProject(name = "Autumn Leaves")
        val useCase = buildUseCase(project)
        val result = useCase.export(project.projectId, uri)
        assertTrue("Expected Success but got $result", result is ExportResult.Success)
        assertEquals("Autumn Leaves.rgp", (result as ExportResult.Success).suggestedFilename)
    }

    @Test
    fun `returns IoError when openOutputStream returns null`() = runTest {
        val project = minimalProject()
        val useCase = buildUseCase(project, outputStream = null)
        val result = useCase.export(project.projectId, uri)
        assertEquals(ExportResult.Failure.IoError, result)
    }

    @Test
    fun `returns IoError when openOutputStream throws IOException`() = runTest {
        val project = minimalProject()
        val contentResolver: ContentResolver = mockk {
            every { openOutputStream(uri) } throws IOException("disk full")
        }
        val projectRepository: ProjectRepository = mockk {
            every { projectStream(project.projectId) } returns flowOf(project)
            coEvery { readProjectGrid(project.projectId) } returns minimalRows
        }
        val useCase = ExportRgpProjectUseCase(
            contentResolver = contentResolver,
            projectRepository = projectRepository,
            projectImageRepository = mockk { coEvery { downloadCoverBytes(any()) } returns null },
        )
        val result = useCase.export(project.projectId, uri)
        assertEquals(ExportResult.Failure.IoError, result)
    }

    @Test
    fun `writes non-empty bytes to output stream on success`() = runTest {
        val project = minimalProject()
        val out = ByteArrayOutputStream()
        val useCase = buildUseCase(project, outputStream = out)
        useCase.export(project.projectId, uri)
        assertTrue("Expected output stream to contain bytes", out.size() > 0)
    }

    // ── image embedding ───────────────────────────────────────────────────────

    @Test
    fun `embeds base64 image in output when project has imageUrl and download succeeds`() = runTest {
        val imageBytes = "hello cover".toByteArray()
        val project = minimalProject().copy(imageUrl = "https://example.com/cover.jpg")
        val projectImageRepository: ProjectImageRepository = mockk {
            coEvery { downloadCoverBytes(project.projectId) } returns imageBytes
        }
        val out = ByteArrayOutputStream()
        val useCase = buildUseCase(project, outputStream = out, projectImageRepository = projectImageRepository)
        val result = useCase.export(project.projectId, uri)
        assertTrue("Expected Success", result is ExportResult.Success)
        val parsed = parseRgp(ByteArrayInputStream(out.toByteArray()))
        val expected = java.util.Base64.getEncoder().encodeToString(imageBytes)
        assertEquals(expected, parsed.image)
    }

    @Test
    fun `skips download and omits image field when project has no imageUrl`() = runTest {
        val project = minimalProject()  // imageUrl is null by default
        val projectImageRepository: ProjectImageRepository = mockk()
        val out = ByteArrayOutputStream()
        val useCase = buildUseCase(project, outputStream = out, projectImageRepository = projectImageRepository)
        val result = useCase.export(project.projectId, uri)
        assertTrue("Expected Success", result is ExportResult.Success)
        coVerify(exactly = 0) { projectImageRepository.downloadCoverBytes(any()) }
        val parsed = parseRgp(ByteArrayInputStream(out.toByteArray()))
        assertTrue("Expected image field to be absent", parsed.image == null)
    }

    @Test
    fun `returns Success without image field when download throws`() = runTest {
        val project = minimalProject().copy(imageUrl = "https://example.com/cover.jpg")
        val projectImageRepository: ProjectImageRepository = mockk {
            coEvery { downloadCoverBytes(any()) } throws RuntimeException("network error")
        }
        val out = ByteArrayOutputStream()
        val useCase = buildUseCase(project, outputStream = out, projectImageRepository = projectImageRepository)
        val result = useCase.export(project.projectId, uri)
        assertTrue("Expected Success despite download failure", result is ExportResult.Success)
        val parsed = parseRgp(ByteArrayInputStream(out.toByteArray()))
        assertTrue("Expected image field to be absent on download failure", parsed.image == null)
    }

    @Test
    fun `omits image field when imageUrl is set but download returns null`() = runTest {
        val project = minimalProject().copy(imageUrl = "https://example.com/cover.jpg")
        val projectImageRepository: ProjectImageRepository = mockk {
            coEvery { downloadCoverBytes(project.projectId) } returns null
        }
        val out = ByteArrayOutputStream()
        val useCase = buildUseCase(project, outputStream = out, projectImageRepository = projectImageRepository)
        val result = useCase.export(project.projectId, uri)
        assertTrue("Expected Success", result is ExportResult.Success)
        assertTrue("Expected image field absent when download returns null",
            parseRgp(ByteArrayInputStream(out.toByteArray())).image == null)
    }
}
