package com.techyshishy.beadmanager.ui.projects

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.ExportRgpProjectUseCase
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `rename with valid name calls updateProject with trimmed name`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "Old Name")
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true) {
            every { ordersStream(any()) } returns flowOf(emptyList())
        }
        val inventoryRepository = mockk<InventoryRepository>(relaxed = true) {
            every { inventoryStream() } returns flowOf(emptyMap())
        }
        val catalogRepository = mockk<CatalogRepository>(relaxed = true) {
            every { allBeadsLookup() } returns flowOf(emptyMap())
        }
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true) {
            every { globalLowStockThreshold } returns flowOf(5.0)
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val exportUseCase = mockk<ExportRgpProjectUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.rename("New Name")
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project.copy(name = "New Name")) }
    }

    @Test
    fun `rename with blank name does not call updateProject`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "Old Name")
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true) {
            every { ordersStream(any()) } returns flowOf(emptyList())
        }
        val inventoryRepository = mockk<InventoryRepository>(relaxed = true) {
            every { inventoryStream() } returns flowOf(emptyMap())
        }
        val catalogRepository = mockk<CatalogRepository>(relaxed = true) {
            every { allBeadsLookup() } returns flowOf(emptyMap())
        }
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true) {
            every { globalLowStockThreshold } returns flowOf(5.0)
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val exportUseCase = mockk<ExportRgpProjectUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.rename("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `rename trims whitespace before writing`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "Old Name")
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true) {
            every { ordersStream(any()) } returns flowOf(emptyList())
        }
        val inventoryRepository = mockk<InventoryRepository>(relaxed = true) {
            every { inventoryStream() } returns flowOf(emptyMap())
        }
        val catalogRepository = mockk<CatalogRepository>(relaxed = true) {
            every { allBeadsLookup() } returns flowOf(emptyMap())
        }
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true) {
            every { globalLowStockThreshold } returns flowOf(5.0)
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val exportUseCase = mockk<ExportRgpProjectUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.rename("  Trimmed Name  ")
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project.copy(name = "Trimmed Name")) }
    }
}
