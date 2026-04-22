package com.techyshishy.beadmanager.domain

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateBlankProjectUseCaseTest {

    private fun useCase(repo: ProjectRepository) = CreateBlankProjectUseCase(repo)

    @Test
    fun `success returns ImportResult Success with assigned projectId and name`() = runTest {
        val captured = slot<ProjectEntry>()
        val repo = mockk<ProjectRepository> {
            coEvery { createProject(capture(captured)) } returns "proj-abc"
        }

        val result = useCase(repo).create("My Pattern")

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals("proj-abc", success.projectId)
        assertEquals("My Pattern", success.name)

        // Verify the stored entry has the correct name and safe-default fields.
        val entry = captured.captured
        assertEquals("My Pattern", entry.name)
        assertTrue("colorMapping should be empty", entry.colorMapping.isEmpty())
        assertTrue("position should be empty", entry.position.isEmpty())
    }

    @Test
    fun `repository exception returns WriteError without rethrowing`() = runTest {
        val repo = mockk<ProjectRepository> {
            coEvery { createProject(any()) } throws RuntimeException("Firestore unavailable")
        }

        val result = useCase(repo).create("Doomed Project")

        assertEquals(ImportResult.Failure.WriteError, result)
        coVerify(exactly = 1) { repo.createProject(any()) }
    }

    @Test
    fun `CancellationException is rethrown and not swallowed as WriteError`() = runTest {
        val repo = mockk<ProjectRepository> {
            coEvery { createProject(any()) } throws CancellationException("scope cancelled")
        }

        var thrown: CancellationException? = null
        try {
            useCase(repo).create("Cancelled Project")
        } catch (e: CancellationException) {
            thrown = e
        }
        assertNotNull("Expected CancellationException to be rethrown", thrown)
    }
}
