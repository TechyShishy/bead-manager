package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
        )
        val result = useCase.export("fab-id", uri)
        assertTrue("Expected Success for FAB project with beads but got $result", result is ExportResult.Success)
        assertEquals("Hand-built.rgp", (result as ExportResult.Success).suggestedFilename)
        assertTrue("Expected non-empty output stream", out.size() > 0)
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
}
