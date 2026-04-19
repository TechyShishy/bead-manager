package com.techyshishy.beadmanager.ui.projects

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectImageRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.ExportRgpProjectUseCase
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
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

    @Test
    fun `addBead with new code calls updateProject with identity-key entry`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project", colorMapping = emptyMap())
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.addBead("DB0001")
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project.copy(colorMapping = mapOf("DB0001" to "DB0001"))) }
    }

    @Test
    fun `addBead with duplicate code does not call updateProject and emits AlreadyPresent`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0001"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        val events = mutableListOf<AddBeadEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.addBeadEvents.collect { events.add(it) }
        }

        vm.addBead("DB0001")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
        assert(events == listOf(AddBeadEvent.AlreadyPresent)) {
            "Expected [AlreadyPresent] but got $events"
        }
    }

    @Test
    fun `addBead before project loads is a no-op`() = runTest {
        val projectRepository = mockk<ProjectRepository>(relaxed = true)
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        // Do not call initialize — project.value remains null.

        vm.addBead("DB0001")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `removeBead calls deleteColorMappingEntries with the matching key`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0168", "DB0001" to "DB0001"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.removeBead("DB0001")
        advanceUntilIdle()

        // Must use field-deletion, not a full document write — otherwise the merge semantics
        // would leave the removed key untouched on the Firestore server.
        coVerify { projectRepository.deleteColorMappingEntries("p1", setOf("DB0001")) }
        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `removeBead with bead absent from colorMapping does not call deleteColorMappingEntries`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0168"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.removeBead("DB9999")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.deleteColorMappingEntries(any(), any()) }
    }

    @Test
    fun `swapBead replaces all colorMapping entries matching old code`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0168", "B" to "DB0168", "C" to "DB0001"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.swapBead("DB0168", "DB0123")
        advanceUntilIdle()

        coVerify {
            projectRepository.updateProject(
                project.copy(colorMapping = mapOf("A" to "DB0123", "B" to "DB0123", "C" to "DB0001"))
            )
        }
    }

    @Test
    fun `swapBead where oldCode equals newCode does not call updateProject`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0168"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.swapBead("DB0168", "DB0168")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `swapBead with single entry replaces correctly`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("DB0001" to "DB0001"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.swapBead("DB0001", "DB0999")
        advanceUntilIdle()

        coVerify {
            projectRepository.updateProject(
                project.copy(colorMapping = mapOf("DB0001" to "DB0999"))
            )
        }
    }

    @Test
    fun `swapBead with oldCode absent from colorMapping does not call updateProject`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0168"),
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.swapBead("DB9999", "DB0001")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `swapBead before project loads is a no-op`() = runTest {
        val projectRepository = mockk<ProjectRepository>(relaxed = true)
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        // Do not call initialize — project.value remains null.

        vm.swapBead("DB0001", "DB0999")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `uploadProjectImage calls repository and persists url to Firestore`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project")
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true) {
            coEvery { uploadCover("p1", any()) } returns "https://example.com/cover.jpg"
        }
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        val fakeUri = mockk<android.net.Uri>()
        vm.uploadProjectImage(fakeUri)
        advanceUntilIdle()

        coVerify { projectImageRepository.uploadCover("p1", fakeUri) }
        coVerify { projectRepository.updateProject(project.copy(imageUrl = "https://example.com/cover.jpg")) }
        assertTrue(vm.imageUploadState.value is ImageUploadState.Idle)
    }

    @Test
    fun `removeProjectImage calls repository and clears imageUrl in Firestore`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            imageUrl = "https://example.com/cover.jpg",
        )
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
        val projectImageRepository = mockk<ProjectImageRepository>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.removeProjectImage()
        advanceUntilIdle()

        coVerify { projectImageRepository.deleteCover("p1") }
        coVerify { projectRepository.updateProject(project.copy(imageUrl = null)) }
        assertTrue(vm.imageUploadState.value is ImageUploadState.Idle)
    }
}
