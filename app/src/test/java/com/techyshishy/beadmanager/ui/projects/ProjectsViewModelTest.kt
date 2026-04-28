package com.techyshishy.beadmanager.ui.projects

import android.net.Uri
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.CreateBlankProjectUseCase
import com.techyshishy.beadmanager.domain.ImportPdfProjectUseCase
import com.techyshishy.beadmanager.domain.ImportResult
import com.techyshishy.beadmanager.domain.ImportRgpProjectUseCase
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    fun `setSortKey with new key adopts that key defaultDirection`() = runTest {
        val vm = createViewModel()
        // NAME has defaultDirection ASCENDING
        vm.setSortKey(ProjectSortKey.NAME)
        assertEquals(ProjectSortKey.NAME, vm.sortOrder.value.key)
        assertEquals(SortDirection.ASCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `setSortKey with same key flips DESCENDING to ASCENDING`() = runTest {
        val vm = createViewModel()
        // Initial: CREATED_AT DESCENDING
        vm.setSortKey(ProjectSortKey.CREATED_AT)
        assertEquals(ProjectSortKey.CREATED_AT, vm.sortOrder.value.key)
        assertEquals(SortDirection.ASCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `setSortKey with same key flips ASCENDING back to DESCENDING`() = runTest {
        val vm = createViewModel()
        vm.setSortKey(ProjectSortKey.CREATED_AT) // → ASCENDING
        vm.setSortKey(ProjectSortKey.CREATED_AT) // → DESCENDING
        assertEquals(SortDirection.DESCENDING, vm.sortOrder.value.direction)
    }

    @Test
    fun `setSortKey switching keys resets to new key defaultDirection`() = runTest {
        val vm = createViewModel()
        vm.setSortKey(ProjectSortKey.NAME)    // NAME ASCENDING (default)
        vm.setSortKey(ProjectSortKey.NAME)    // NAME DESCENDING (flipped)
        vm.setSortKey(ProjectSortKey.GRID_SIZE) // GRID_SIZE DESCENDING (its default)
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

    // ── Search ───────────────────────────────────────────────────────────────

    private fun makeProject(
        id: String,
        name: String = "project $id",
        notes: String? = null,
        tags: List<String> = emptyList(),
    ) = ProjectEntry(projectId = id, name = name, notes = notes, tags = tags)

    private fun createViewModelWithProjects(projects: List<ProjectEntry>): ProjectsViewModel {
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectsStream() } returns flowOf(projects)
        }
        return ProjectsViewModel(
            projectRepository,
            mockk<InventoryRepository>(relaxed = true) { every { inventoryStream() } returns flowOf(emptyMap()) },
            mockk<PreferencesRepository>(relaxed = true) { every { globalLowStockThreshold } returns flowOf(5.0) },
            mockk<CreateBlankProjectUseCase>(relaxed = true),
            mockk<ImportRgpProjectUseCase>(relaxed = true),
            mockk<ImportPdfProjectUseCase>(relaxed = true),
        )
    }

    @Test
    fun `empty search query shows all projects`() = runTest {
        val projects = listOf(makeProject("1"), makeProject("2"))
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        advanceUntilIdle()
        assertEquals(2, vm.projects.value.size)
    }

    @Test
    fun `search query filters by project name case-insensitive`() = runTest {
        val projects = listOf(makeProject("1", name = "Sunflower"), makeProject("2", name = "Rose"))
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setSearchQuery("sun")
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.projects.value.map { it.projectId })
    }

    @Test
    fun `search query matches projects by notes`() = runTest {
        val projects = listOf(
            makeProject("1", notes = "for the garden"),
            makeProject("2", notes = "birthday gift"),
        )
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setSearchQuery("garden")
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.projects.value.map { it.projectId })
    }

    @Test
    fun `search query matches projects by tag`() = runTest {
        val projects = listOf(
            makeProject("1", tags = listOf("floral", "spring")),
            makeProject("2", tags = listOf("geometric")),
        )
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setSearchQuery("floral")
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.projects.value.map { it.projectId })
    }

    @Test
    fun `search query with no match returns empty list`() = runTest {
        val projects = listOf(makeProject("1", name = "Sunflower"))
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setSearchQuery("zzznomatch")
        advanceUntilIdle()
        assertEquals(emptyList<ProjectEntry>(), vm.projects.value)
    }

    // ── Tag filter ───────────────────────────────────────────────────────────

    @Test
    fun `setTagFilter includes only projects with that tag`() = runTest {
        val projects = listOf(
            makeProject("1", tags = listOf("floral")),
            makeProject("2", tags = listOf("geometric")),
        )
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setTagFilter("floral")
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.projects.value.map { it.projectId })
    }

    @Test
    fun `setTagFilter null shows all projects`() = runTest {
        val projects = listOf(
            makeProject("1", tags = listOf("floral")),
            makeProject("2", tags = listOf("geometric")),
        )
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setTagFilter("floral")
        vm.setTagFilter(null)
        advanceUntilIdle()
        assertEquals(2, vm.projects.value.size)
    }

    @Test
    fun `tag filter and search query compose correctly`() = runTest {
        val projects = listOf(
            makeProject("1", name = "Sunflower", tags = listOf("floral")),
            makeProject("2", name = "Rose", tags = listOf("floral")),
            makeProject("3", name = "Sunflower Tile", tags = listOf("geometric")),
        )
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.projects.collect { } }
        vm.setTagFilter("floral")
        vm.setSearchQuery("sun")
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.projects.value.map { it.projectId })
    }

    @Test
    fun `availableTags returns sorted union of all project tags`() = runTest {
        val projects = listOf(
            makeProject("1", tags = listOf("floral", "spring")),
            makeProject("2", tags = listOf("geometric", "floral")),
        )
        val vm = createViewModelWithProjects(projects)
        backgroundScope.launch { vm.availableTags.collect { } }
        advanceUntilIdle()
        assertEquals(listOf("floral", "geometric", "spring"), vm.availableTags.value)
    }

    @Test
    fun `clearFilters resets sort to DEFAULT and clears tag filter`() = runTest {
        val vm = createViewModel()
        vm.setSortKey(ProjectSortKey.NAME)
        vm.setTagFilter("floral")
        vm.clearFilters()
        assertEquals(ProjectSortOrder.DEFAULT, vm.sortOrder.value)
        assertEquals(null, vm.tagFilter.value)
    }
}
