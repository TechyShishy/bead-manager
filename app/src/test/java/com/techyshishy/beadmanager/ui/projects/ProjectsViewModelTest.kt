package com.techyshishy.beadmanager.ui.projects

import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.ImportRgpProjectUseCase
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            mockk(relaxed = true),
        )
    }

    @Test
    fun `default sort order is CREATED_AT DESCENDING`() = runTest {
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
}
