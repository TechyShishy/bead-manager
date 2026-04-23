package com.techyshishy.beadmanager.ui.projects

import android.net.Uri
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.CreateBlankProjectUseCase
import com.techyshishy.beadmanager.domain.ImportPdfProjectUseCase
import com.techyshishy.beadmanager.domain.ImportResult
import com.techyshishy.beadmanager.domain.ImportRgpProjectUseCase
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(): ProjectsViewModel {
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectsStream() } returns flowOf(emptyList())
        }
        val inventoryRepository = mockk<InventoryRepository>(relaxed = true) {
            every { inventoryStream() } returns flowOf(emptyMap())
        }
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true) {
            every { globalLowStockThreshold } returns flowOf(5.0)
        }
        return ProjectsViewModel(
            projectRepository,
            inventoryRepository,
            preferencesRepository,
            mockk<CreateBlankProjectUseCase>(relaxed = true),
            mockk<ImportRgpProjectUseCase>(relaxed = true),
            mockk<ImportPdfProjectUseCase>(relaxed = true),
        )
    }

    @Test
    fun `initial sortOrder is DEFAULT`() = runTest {
        val vm = createViewModel()
        assertEquals(ProjectSortOrder.DEFAULT, vm.sortOrder.value)
        assertEquals(ProjectSortKey.CREATED_AT, vm.sortOrder.value.key)
        assertEquals(SortDirection.DESCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSortKey with new key adopts that key defaultDirection`() = runTest {
        val vm = createViewModel()
        // NAME has defaultDirection ASCENDING
        vm.toggleSortKey(ProjectSortKey.NAME)
        assertEquals(ProjectSortKey.NAME, vm.sortOrder.value.key)
        assertEquals(SortDirection.ASCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSortKey with same key flips DESCENDING to ASCENDING`() = runTest {
        val vm = createViewModel()
        // Initial: CREATED_AT DESCENDING
        vm.toggleSortKey(ProjectSortKey.CREATED_AT)
        assertEquals(ProjectSortKey.CREATED_AT, vm.sortOrder.value.key)
        assertEquals(SortDirection.ASCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSortKey with same key flips ASCENDING back to DESCENDING`() = runTest {
        val vm = createViewModel()
        vm.toggleSortKey(ProjectSortKey.CREATED_AT) // → ASCENDING
        vm.toggleSortKey(ProjectSortKey.CREATED_AT) // → DESCENDING
        assertEquals(SortDirection.DESCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSortKey switching keys resets to new key defaultDirection`() = runTest {
        val vm = createViewModel()
        vm.toggleSortKey(ProjectSortKey.NAME)    // NAME ASCENDING (default)
        vm.toggleSortKey(ProjectSortKey.NAME)    // NAME DESCENDING (flipped)
        vm.toggleSortKey(ProjectSortKey.GRID_SIZE) // GRID_SIZE DESCENDING (its default)
        assertEquals(ProjectSortKey.GRID_SIZE, vm.sortOrder.value.key)
        assertEquals(SortDirection.DESCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `isImporting is true while RGP import is suspended`() = runTest {
        val deferred = CompletableDeferred<ImportResult>()
        val rgpUseCase = mockk<ImportRgpProjectUseCase> {
            coEvery { import(any()) } coAnswers { deferred.await() }
        }
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectsStream() } returns flowOf(emptyList())
        }
        val vm = ProjectsViewModel(
            projectRepository,
            mockk<InventoryRepository>(relaxed = true) { every { inventoryStream() } returns flowOf(emptyMap()) },
            mockk<PreferencesRepository>(relaxed = true) { every { globalLowStockThreshold } returns flowOf(5.0) },
            mockk<CreateBlankProjectUseCase>(relaxed = true),
            rgpUseCase,
            mockk<ImportPdfProjectUseCase>(relaxed = true),
        )

        vm.importFromRgp(mockk<Uri>())
        assertTrue("isImporting should be true while use case is suspended", vm.isImporting.value)

        deferred.complete(ImportResult.Success("pid", "name"))
        advanceUntilIdle()
        assertFalse("isImporting should be false after import completes", vm.isImporting.value)
    }

    @Test
    fun `isImporting is false after RGP import success`() = runTest {
        val rgpUseCase = mockk<ImportRgpProjectUseCase> {
            coEvery { import(any()) } returns ImportResult.Success("pid", "name")
        }
        val vm = ProjectsViewModel(
            mockk<ProjectRepository>(relaxed = true) { every { projectsStream() } returns flowOf(emptyList()) },
            mockk<InventoryRepository>(relaxed = true) { every { inventoryStream() } returns flowOf(emptyMap()) },
            mockk<PreferencesRepository>(relaxed = true) { every { globalLowStockThreshold } returns flowOf(5.0) },
            mockk<CreateBlankProjectUseCase>(relaxed = true),
            rgpUseCase,
            mockk<ImportPdfProjectUseCase>(relaxed = true),
        )

        vm.importFromRgp(mockk<Uri>())
        advanceUntilIdle()
        assertFalse("isImporting should be false after successful import", vm.isImporting.value)
    }

    @Test
    fun `isImporting is false after RGP import failure`() = runTest {
        val rgpUseCase = mockk<ImportRgpProjectUseCase> {
            coEvery { import(any()) } returns ImportResult.Failure.NotGzip
        }
        val vm = ProjectsViewModel(
            mockk<ProjectRepository>(relaxed = true) { every { projectsStream() } returns flowOf(emptyList()) },
            mockk<InventoryRepository>(relaxed = true) { every { inventoryStream() } returns flowOf(emptyMap()) },
            mockk<PreferencesRepository>(relaxed = true) { every { globalLowStockThreshold } returns flowOf(5.0) },
            mockk<CreateBlankProjectUseCase>(relaxed = true),
            rgpUseCase,
            mockk<ImportPdfProjectUseCase>(relaxed = true),
        )

        vm.importFromRgp(mockk<Uri>())
        advanceUntilIdle()
        assertFalse("isImporting should be false after failed import", vm.isImporting.value)
    }

    @Test
    fun `isImporting is true while PDF import is suspended`() = runTest {
        val deferred = CompletableDeferred<ImportResult>()
        val pdfUseCase = mockk<ImportPdfProjectUseCase> {
            coEvery { import(any()) } coAnswers { deferred.await() }
        }
        val vm = ProjectsViewModel(
            mockk<ProjectRepository>(relaxed = true) { every { projectsStream() } returns flowOf(emptyList()) },
            mockk<InventoryRepository>(relaxed = true) { every { inventoryStream() } returns flowOf(emptyMap()) },
            mockk<PreferencesRepository>(relaxed = true) { every { globalLowStockThreshold } returns flowOf(5.0) },
            mockk<CreateBlankProjectUseCase>(relaxed = true),
            mockk<ImportRgpProjectUseCase>(relaxed = true),
            pdfUseCase,
        )

        vm.importFromPdf(mockk<Uri>())
        assertTrue("isImporting should be true while PDF use case is suspended", vm.isImporting.value)

        deferred.complete(ImportResult.Success("pid", "name"))
        advanceUntilIdle()
        assertFalse("isImporting should be false after PDF import completes", vm.isImporting.value)
    }
}
