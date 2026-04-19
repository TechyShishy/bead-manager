package com.techyshishy.beadmanager.ui.projects

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.ProjectBeadEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the behavioral change from Phase 1a: the "View Orders" action button has been
 * removed from [ProjectDetailScreen]. Absence of this button ensures the user cannot
 * navigate to a per-project orders list from within the Projects tab.
 */
@RunWith(AndroidJUnit4::class)
class ProjectDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeProjectDetailViewModel(): ProjectDetailViewModel =
        mockk(relaxed = true) {
            every { project } returns MutableStateFlow(
                ProjectEntry(projectId = "p1", name = "Test Project")
            )
            every { beads } returns MutableStateFlow(emptyList<ProjectBeadEntry>())
            every { activeOrders } returns MutableStateFlow(emptyList<OrderEntry>())
            every { inventoryEntries } returns MutableStateFlow(emptyMap<String, InventoryEntry>())
            every { activeOrderStatus } returns MutableStateFlow(emptyMap<String, OrderItemStatus>())
            every { beadLookup } returns MutableStateFlow(emptyMap<String, BeadEntity>())
            every { globalThreshold } returns MutableStateFlow(5.0)
        }

    @Test
    fun viewOrdersButtonIsNotPresentInProjectDetailScreen() {
        val viewModel = makeProjectDetailViewModel()
        composeTestRule.setContent {
            ProjectDetailScreen(
                projectId = "p1",
                viewModel = viewModel,
                onNavigateBack = {},
                onAddToOrder = {},
                onAddBeadFromCatalog = {},
                onReplaceBeadFromCatalog = {},
                onPinAllToComparison = {},
                onViewInCatalog = {},
            )
        }

        composeTestRule
            .onNodeWithContentDescription("View Orders", substring = true, ignoreCase = true)
            .assertDoesNotExist()
    }

    @Test
    fun pinAllButtonIsAbsentWhenProjectHasNoBeads() {
        val viewModel = makeProjectDetailViewModel()
        composeTestRule.setContent {
            ProjectDetailScreen(
                projectId = "p1",
                viewModel = viewModel,
                onNavigateBack = {},
                onAddToOrder = {},
                onAddBeadFromCatalog = {},
                onReplaceBeadFromCatalog = {},
                onPinAllToComparison = {},
                onViewInCatalog = {},
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Pin all beads", substring = true, ignoreCase = true)
            .assertDoesNotExist()
    }

    @Test
    fun pinAllButtonCallsCallbackWithBeadCodes() {
        val beads = listOf(
            ProjectBeadEntry(beadCode = "DB0001", targetGrams = 5.0),
            ProjectBeadEntry(beadCode = "DB0002", targetGrams = 3.0),
        )
        val viewModel: ProjectDetailViewModel = mockk(relaxed = true) {
            every { project } returns MutableStateFlow(
                ProjectEntry(projectId = "p2", name = "Test", colorMapping = mapOf("A" to "DB0001", "B" to "DB0002"))
            )
            every { this@mockk.beads } returns MutableStateFlow(beads)
            every { activeOrders } returns MutableStateFlow(emptyList<OrderEntry>())
            every { inventoryEntries } returns MutableStateFlow(emptyMap<String, InventoryEntry>())
            every { activeOrderStatus } returns MutableStateFlow(emptyMap<String, OrderItemStatus>())
            every { beadLookup } returns MutableStateFlow(emptyMap<String, BeadEntity>())
            every { globalThreshold } returns MutableStateFlow(5.0)
        }

        var capturedCodes: List<String>? = null
        composeTestRule.setContent {
            ProjectDetailScreen(
                projectId = "p2",
                viewModel = viewModel,
                onNavigateBack = {},
                onAddToOrder = {},
                onAddBeadFromCatalog = {},
                onReplaceBeadFromCatalog = {},
                onPinAllToComparison = { codes -> capturedCodes = codes },
                onViewInCatalog = {},
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Pin all beads", substring = true, ignoreCase = true)
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf("DB0001", "DB0002"), capturedCodes)
    }
}
