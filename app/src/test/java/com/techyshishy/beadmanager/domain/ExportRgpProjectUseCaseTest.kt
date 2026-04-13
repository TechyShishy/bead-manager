package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.repository.ProjectRepository
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
        rows = listOf(
            ProjectRgpRow(
                id = 1,
                steps = listOf(ProjectRgpStep(id = 1, count = 5, description = "A")),
            )
        ),
        colorMapping = mapOf("A" to "DB0001"),
    )

    private fun buildUseCase(
        project: ProjectEntry?,
        outputStream: java.io.OutputStream? = ByteArrayOutputStream(),
    ): ExportRgpProjectUseCase {
        val projectRepository: ProjectRepository = mockk {
            every { projectStream(project?.projectId ?: "missing-id") } returns flowOf(project)
        }
        val contentResolver: ContentResolver = mockk {
            every { openOutputStream(uri) } returns outputStream
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
    fun `returns NoGrid when project has empty rows`() = runTest {
        val flatProject = ProjectEntry(projectId = "flat-id", name = "Flat", rows = emptyList())
        val projectRepository: ProjectRepository = mockk {
            every { projectStream("flat-id") } returns flowOf(flatProject)
        }
        val useCase = ExportRgpProjectUseCase(
            contentResolver = mockk(),
            projectRepository = projectRepository,
        )
        val result = useCase.export("flat-id", uri)
        assertEquals(ExportResult.Failure.NoGrid, result)
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
