package com.techyshishy.beadmanager.ui.orders

import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AddToOrderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun eligibleOrdersExcludesOrdersAlreadyLinkedToProject() = runTest {
        val linkedOrder = OrderEntry(orderId = "o1", projectIds = listOf("proj-1"))
        val eligibleOrder = OrderEntry(orderId = "o2", projectIds = listOf("other"))
        val project = ProjectEntry(projectId = "proj-1", name = "Test")
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(linkedOrder, eligibleOrder))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(listOf(project))
        }

        val viewModel = AddToOrderViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eligibleOrders.collect {}
        }
        viewModel.initialize("proj-1")
        advanceUntilIdle()

        val result = viewModel.eligibleOrders.value
        assertEquals(1, result.size)
        assertEquals("o2", result[0].order.orderId)
    }

    @Test
    fun eligibleOrdersIsEmptyBeforeInitialize() = runTest {
        val order = OrderEntry(orderId = "o1", projectIds = listOf("other"))
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(order))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(emptyList())
        }

        val viewModel = AddToOrderViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eligibleOrders.collect {}
        }
        // initialize() deliberately not called
        advanceUntilIdle()

        assertTrue(viewModel.eligibleOrders.value.isEmpty())
    }

    @Test
    fun eligibleOrdersExcludesLegacyProjectIdOrder() = runTest {
        // Pre-migration orders may have projectId set but projectIds empty;
        // they must be excluded even though projectIds.none { } would pass.
        val legacyOrder = OrderEntry(orderId = "o1", projectIds = emptyList(), projectId = "proj-1")
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(legacyOrder))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(emptyList())
        }

        val viewModel = AddToOrderViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eligibleOrders.collect {}
        }
        viewModel.initialize("proj-1")
        advanceUntilIdle()

        assertTrue(viewModel.eligibleOrders.value.isEmpty())
    }

    @Test
    fun eligibleOrdersIncludeAllOrdersWhenProjectHasNoLinks() = runTest {
        val order1 = OrderEntry(orderId = "o1", projectIds = listOf("other-1"))
        val order2 = OrderEntry(orderId = "o2", projectIds = listOf("other-2"))
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(order1, order2))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(emptyList())
        }

        val viewModel = AddToOrderViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eligibleOrders.collect {}
        }
        viewModel.initialize("new-project")
        advanceUntilIdle()

        val result = viewModel.eligibleOrders.value
        assertEquals(2, result.size)
    }
}
