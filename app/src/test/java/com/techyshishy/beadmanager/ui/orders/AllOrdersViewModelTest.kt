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
class AllOrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun ordersResolvesProjectNamesFromProjectIds() = runTest {
        val order = OrderEntry(orderId = "o1", projectIds = listOf("p1"))
        val project = ProjectEntry(projectId = "p1", name = "Alpha")
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(order))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(listOf(project))
        }

        val viewModel = AllOrdersViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.orders.collect {}
        }
        advanceUntilIdle()

        val result = viewModel.orders.value
        assertEquals(1, result.size)
        assertEquals(listOf("Alpha"), result[0].projectNames)
    }

    @Test
    fun ordersWithNoMatchingProjectHasEmptyProjectNames() = runTest {
        val order = OrderEntry(orderId = "o1", projectIds = listOf("missing"))
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(order))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(emptyList())
        }

        val viewModel = AllOrdersViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.orders.collect {}
        }
        advanceUntilIdle()

        val result = viewModel.orders.value
        assertEquals(1, result.size)
        assertTrue(result[0].projectNames.isEmpty())
    }

    @Test
    fun emptyRepositoryStreamsProduceEmptyOrdersList() = runTest {
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(emptyList())
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(emptyList())
        }

        val viewModel = AllOrdersViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.orders.collect {}
        }
        advanceUntilIdle()

        assertTrue(viewModel.orders.value.isEmpty())
    }

    @Test
    fun ordersWithMultipleProjectsResolvesAllNames() = runTest {
        val order = OrderEntry(orderId = "o1", projectIds = listOf("p1", "p2"))
        val projects = listOf(
            ProjectEntry(projectId = "p1", name = "Alpha"),
            ProjectEntry(projectId = "p2", name = "Beta"),
        )
        val orderRepository = mockk<OrderRepository> {
            every { allOrdersStream() } returns flowOf(listOf(order))
        }
        val projectRepository = mockk<ProjectRepository> {
            every { projectsStream() } returns flowOf(projects)
        }

        val viewModel = AllOrdersViewModel(orderRepository, projectRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.orders.collect {}
        }
        advanceUntilIdle()

        val result = viewModel.orders.value
        assertEquals(listOf("Alpha", "Beta"), result[0].projectNames)
    }
}
