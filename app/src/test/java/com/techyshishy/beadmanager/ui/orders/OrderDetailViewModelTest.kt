package com.techyshishy.beadmanager.ui.orders

import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.db.VendorLinkEntity
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun bead(code: String) = BeadEntity(
        code = code,
        hex = "#000000",
        imageUrl = "",
        officialUrl = "",
        colorGroup = emptyList(),
        glassGroup = "",
        finishes = emptyList(),
        dyed = "",
        galvanized = "",
        plating = "",
    )

    private fun vendorLink(
        beadCode: String,
        vendorKey: String,
        beadName: String?,
    ) = VendorLinkEntity(
        beadCode = beadCode,
        vendorKey = vendorKey,
        displayName = vendorKey,
        url = "",
        beadName = beadName,
    )

    @Test
    fun `beadColorNames resolves name from first matching vendor in priority order`() = runTest {
        val beadWithVendors = BeadWithVendors(
            bead = bead("DB0001"),
            vendorLinks = listOf(
                vendorLink("DB0001", "ac",  beadName = "Sky Blue"),
                vendorLink("DB0001", "fmg", beadName = "Cerulean"),
            ),
        )
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(listOf(beadWithVendors))
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true)

        val viewModel = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.beadColorNames.collect {}
        }
        advanceUntilIdle()

        // "fmg" is first in priority and "DB0001" has the FMG name "Cerulean".
        assertEquals("Cerulean", viewModel.beadColorNames.value["DB0001"])
    }

    @Test
    fun `beadColorNames falls back to second vendor when first has no name`() = runTest {
        val beadWithVendors = BeadWithVendors(
            bead = bead("DB0001"),
            vendorLinks = listOf(
                vendorLink("DB0001", "fmg", beadName = null),
                vendorLink("DB0001", "ac",  beadName = "Cobalt Blue"),
            ),
        )
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(listOf(beadWithVendors))
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true)

        val viewModel = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.beadColorNames.collect {}
        }
        advanceUntilIdle()

        assertEquals("Cobalt Blue", viewModel.beadColorNames.value["DB0001"])
    }

    @Test
    fun `beadColorNames omits bead when no vendor has a name`() = runTest {
        val beadWithVendors = BeadWithVendors(
            bead = bead("DB0001"),
            vendorLinks = listOf(
                vendorLink("DB0001", "fmg", beadName = null),
                vendorLink("DB0001", "ac",  beadName = "  "),
            ),
        )
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(listOf(beadWithVendors))
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true)

        val viewModel = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.beadColorNames.collect {}
        }
        advanceUntilIdle()

        assertNull(viewModel.beadColorNames.value["DB0001"])
    }

    @Test
    fun `beadColorNames emits empty map when no beads exist`() = runTest {
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(emptyList())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(listOf("fmg", "ac"))
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true)

        val viewModel = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.beadColorNames.collect {}
        }
        advanceUntilIdle()

        assertTrue(viewModel.beadColorNames.value.isEmpty())
    }

    @Test
    fun `beadLookup is populated from getAllBeadsWithVendors upstream`() = runTest {
        val entity = bead("DB0001")
        val beadWithVendors = BeadWithVendors(
            bead = entity,
            vendorLinks = emptyList(),
        )
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(listOf(beadWithVendors))
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(emptyList())
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true)

        val viewModel = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.beadLookup.collect {}
        }
        advanceUntilIdle()

        assertEquals(entity, viewModel.beadLookup.value["DB0001"])
    }
}
