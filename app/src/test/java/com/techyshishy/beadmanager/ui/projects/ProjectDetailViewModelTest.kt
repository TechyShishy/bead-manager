package com.techyshishy.beadmanager.ui.projects

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.ProjectImageRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.domain.ExportRgpProjectUseCase
import com.techyshishy.beadmanager.domain.GenerateProjectPreviewUseCase
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
                project.copy(
                    colorMapping = mapOf("A" to "DB0123", "B" to "DB0123", "C" to "DB0001"),
                    originalColorMapping = mapOf("A" to "DB0168", "B" to "DB0168"),
                )
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
                project.copy(
                    colorMapping = mapOf("DB0001" to "DB0999"),
                    originalColorMapping = mapOf("DB0001" to "DB0001"),
                )
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
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

    @Test
    fun `beads StateFlow emits entries sorted by beadCode ascending`() = runTest {
        // colorMapping values are intentionally in reverse alphabetical order to prove
        // the ViewModel applies sortedBy rather than relying on map iteration order.
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf(
                "DB-0010" to "DB-0010",
                "DB-0003" to "DB-0003",
                "DB-0007" to "DB-0007",
            ),
        )
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
            coEvery { readProjectGrid("p1") } returns emptyList()
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
        val generateProjectPreviewUseCase = mockk<GenerateProjectPreviewUseCase>(relaxed = true)
        val vm = ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            exportUseCase,
            projectImageRepository,
            generateProjectPreviewUseCase,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.projectRows.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        var result: List<com.techyshishy.beadmanager.data.model.ProjectBeadEntry> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { result = it }
        }
        advanceUntilIdle()

        assertEquals(listOf("DB-0003", "DB-0007", "DB-0010"), result.map { it.beadCode })
    }

    private fun buildVm(projectRepository: ProjectRepository): ProjectDetailViewModel {
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
        return ProjectDetailViewModel(
            projectRepository,
            orderRepository,
            inventoryRepository,
            catalogRepository,
            preferencesRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
    }

    @Test
    fun `swapBead records original code in originalColorMapping on first swap`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0168", "B" to "DB0001"),
            originalColorMapping = emptyMap(),
        )
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.swapBead("DB0168", "DB0999")
        advanceUntilIdle()

        coVerify {
            projectRepository.updateProject(
                project.copy(
                    colorMapping = mapOf("A" to "DB0999", "B" to "DB0001"),
                    originalColorMapping = mapOf("A" to "DB0168"),
                )
            )
        }
    }

    @Test
    fun `swapBead does not overwrite originalColorMapping on second swap`() = runTest {
        // "A" was previously swapped from DB0168 -> DB0999; original is already recorded.
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0999"),
            originalColorMapping = mapOf("A" to "DB0168"),
        )
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.swapBead("DB0999", "DB0042")
        advanceUntilIdle()

        // colorMapping updated, but originalColorMapping still maps "A" to DB0168, not DB0999.
        coVerify {
            projectRepository.updateProject(
                project.copy(
                    colorMapping = mapOf("A" to "DB0042"),
                    originalColorMapping = mapOf("A" to "DB0168"),
                )
            )
        }
    }

    @Test
    fun `beads StateFlow populates originalBeadCode from originalColorMapping`() = runTest {
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0002"),
            originalColorMapping = mapOf("A" to "DB0001"),
        )
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
            coEvery { readProjectGrid("p1") } returns emptyList()
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.projectRows.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        var result: List<com.techyshishy.beadmanager.data.model.ProjectBeadEntry> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { result = it }
        }
        advanceUntilIdle()

        assertEquals(1, result.size)
        assertEquals("DB0002", result[0].beadCode)
        assertEquals("DB0001", result[0].originalBeadCode)
    }

    @Test
    fun `beads StateFlow uses first palette key original when two keys share the same current code`() = runTest {
        // Both "A" (DB0168 -> DB0999) and "B" (DB0042 -> DB0999) now map to DB0999.
        // The ViewModel must deterministically pick one original; it must not crash or
        // exhibit non-deterministic behavior. We verify that exactly one non-null original
        // is produced and that it is one of the two valid candidates.
        val project = ProjectEntry(
            projectId = "p1",
            name = "My Project",
            colorMapping = mapOf("A" to "DB0999", "B" to "DB0999"),
            originalColorMapping = mapOf("A" to "DB0168", "B" to "DB0042"),
        )
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
            coEvery { readProjectGrid("p1") } returns emptyList()
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.projectRows.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        var result: List<com.techyshishy.beadmanager.data.model.ProjectBeadEntry> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { result = it }
        }
        advanceUntilIdle()

        // DB0999 appears only once in the bead list (two keys, same code).
        assertEquals(1, result.size)
        assertEquals("DB0999", result[0].beadCode)
        // "A" is the first key in the LinkedHashMap, so DB0168 wins deterministically.
        assertEquals("DB0168", result[0].originalBeadCode)
    }

    @Test
    fun `addTag with valid tag calls updateProject with tag appended`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project", tags = listOf("existing"))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.addTag("new-tag")
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project.copy(tags = listOf("existing", "new-tag"))) }
    }

    @Test
    fun `addTag trims whitespace before storing`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project", tags = emptyList())
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.addTag("  holiday  ")
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project.copy(tags = listOf("holiday"))) }
    }

    @Test
    fun `addTag with blank tag does not call updateProject`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project")
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.addTag("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `addTag with duplicate tag does not call updateProject`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project", tags = listOf("holiday"))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.addTag("holiday")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `removeTag calls updateProject with tag removed`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project", tags = listOf("gift", "wip"))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.removeTag("gift")
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project.copy(tags = listOf("wip"))) }
    }

    @Test
    fun `removeTag with absent tag does not call updateProject`() = runTest {
        val project = ProjectEntry(projectId = "p1", name = "My Project", tags = listOf("wip"))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(project)
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        vm.removeTag("nonexistent")
        advanceUntilIdle()

        coVerify(exactly = 0) { projectRepository.updateProject(any()) }
    }

    @Test
    fun `suggestedTags is empty before initialize is called`() = runTest {
        val otherProject = ProjectEntry(projectId = "p2", name = "Other", tags = listOf("holiday"))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectsStream() } returns flowOf(listOf(otherProject))
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.suggestedTags.collect {}
        }
        // initialize() is intentionally not called
        advanceUntilIdle()

        assertEquals(emptyList<String>(), vm.suggestedTags.value)
    }

    @Test
    fun `suggestedTags excludes tags already on the current project`() = runTest {
        val currentProject = ProjectEntry(projectId = "p1", name = "Current", tags = listOf("holiday"))
        val otherProject = ProjectEntry(projectId = "p2", name = "Other", tags = listOf("holiday", "gift"))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(currentProject)
            every { projectsStream() } returns flowOf(listOf(currentProject, otherProject))
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.suggestedTags.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        assertEquals(listOf("gift"), vm.suggestedTags.value)
    }

    @Test
    fun `suggestedTags is empty when no other project has tags`() = runTest {
        val currentProject = ProjectEntry(projectId = "p1", name = "Current", tags = listOf("wip"))
        val otherProject = ProjectEntry(projectId = "p2", name = "Other", tags = emptyList())
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(currentProject)
            every { projectsStream() } returns flowOf(listOf(currentProject, otherProject))
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.suggestedTags.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), vm.suggestedTags.value)
    }

    @Test
    fun `suggestedTags updates reactively when another project gains a tag`() = runTest {
        val currentProject = ProjectEntry(projectId = "p1", name = "Current", tags = emptyList())
        val otherProject = ProjectEntry(projectId = "p2", name = "Other", tags = emptyList())
        val allProjectsFlow = MutableStateFlow(listOf(currentProject, otherProject))
        val projectRepository = mockk<ProjectRepository>(relaxed = true) {
            every { projectStream("p1") } returns flowOf(currentProject)
            every { projectsStream() } returns allProjectsFlow
        }
        val vm = buildVm(projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.project.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.suggestedTags.collect {}
        }
        vm.initialize("p1")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.suggestedTags.value)

        allProjectsFlow.value = listOf(currentProject, otherProject.copy(tags = listOf("new-tag")))
        advanceUntilIdle()

        assertEquals(listOf("new-tag"), vm.suggestedTags.value)
    }
}
