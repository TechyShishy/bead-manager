package com.techyshishy.beadmanager.ui.orders

import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.db.VendorLinkEntity
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
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
import org.junit.Assert.assertFalse
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

    @Test
    fun `sortedItems emits order items sorted by bead code ascending`() = runTest {
        val items = listOf(
            OrderItemEntry(beadCode = "DB0050"),
            OrderItemEntry(beadCode = "DB0010"),
            OrderItemEntry(beadCode = "DB0030"),
        )
        val orderEntry = OrderEntry(orderId = "order1", items = items)
        val orderRepository = mockk<OrderRepository> {
            every { orderStream("order1") } returns flowOf(orderEntry)
        }
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(emptyList())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(emptyList())
        }

        val viewModel = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        viewModel.initialize("order1")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.sortedItems.collect {}
        }
        advanceUntilIdle()

        assertEquals(
            listOf("DB0010", "DB0030", "DB0050"),
            viewModel.sortedItems.value.map { it.beadCode },
        )
    }

    // ── isFullyOrdered ────────────────────────────────────────────────────────

    private fun makeViewModel(items: List<OrderItemEntry>): OrderDetailViewModel {
        val orderEntry = OrderEntry(orderId = "order1", items = items)
        val orderRepository = mockk<OrderRepository> {
            every { orderStream("order1") } returns flowOf(orderEntry)
        }
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(emptyList())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(emptyList())
        }
        return OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
            .also { it.initialize("order1") }
    }

    @Test
    fun `isFullyOrdered is true when all items are ORDERED`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isFullyOrdered.collect {} }
        advanceUntilIdle()
        assertTrue(vm.isFullyOrdered.value)
    }

    @Test
    fun `isFullyOrdered is true for mixed ORDERED RECEIVED SKIPPED`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.RECEIVED.firestoreValue),
            OrderItemEntry(beadCode = "DB0003", vendorKey = "fmg",
                status = OrderItemStatus.SKIPPED.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isFullyOrdered.collect {} }
        advanceUntilIdle()
        assertTrue(vm.isFullyOrdered.value)
    }

    @Test
    fun `isFullyOrdered is false when any item is PENDING`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.PENDING.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isFullyOrdered.collect {} }
        advanceUntilIdle()
        assertFalse(vm.isFullyOrdered.value)
    }

    @Test
    fun `isFullyOrdered is false when any item is FINALIZED`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.FINALIZED.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isFullyOrdered.collect {} }
        advanceUntilIdle()
        assertFalse(vm.isFullyOrdered.value)
    }

    @Test
    fun `isFullyOrdered is false when all items are SKIPPED`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.SKIPPED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.SKIPPED.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isFullyOrdered.collect {} }
        advanceUntilIdle()
        assertFalse(vm.isFullyOrdered.value)
    }

    @Test
    fun `isFullyOrdered is false when order is null`() = runTest {
        val orderRepository = mockk<OrderRepository>(relaxed = true)
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(emptyList())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(emptyList())
        }
        val vm = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        // Not initialized — order remains null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isFullyOrdered.collect {} }
        advanceUntilIdle()
        assertFalse(vm.isFullyOrdered.value)
    }

    // ── vendorSets ────────────────────────────────────────────────────────────

    @Test
    fun `vendorSets groups by vendorKey excludes SKIPPED and resolves display name`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0003", vendorKey = "ac",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0004", vendorKey = "fmg",
                status = OrderItemStatus.SKIPPED.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()

        val sets = vm.vendorSets.value
        // Sorted by display name: "Aura Crystals" before "Fire Mountain Gems"
        assertEquals(2, sets.size)
        assertEquals("ac", sets[0].vendorKey)
        assertEquals("Aura Crystals", sets[0].displayName)
        assertEquals(1, sets[0].itemCount)
        assertEquals("fmg", sets[1].vendorKey)
        assertEquals("Fire Mountain Gems", sets[1].displayName)
        // SKIPPED item not counted
        assertEquals(2, sets[1].itemCount)
    }

    @Test
    fun `vendorSets shows invoice number when all items in group agree`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = "INV-123"),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = "INV-123"),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()
        assertEquals("INV-123", vm.vendorSets.value.first().invoiceNumber)
    }

    @Test
    fun `vendorSets suppresses invoice number when items in group disagree`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = "INV-123"),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = "INV-999"),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()
        assertNull(vm.vendorSets.value.first().invoiceNumber)
    }

    @Test
    fun `vendorSets returns null invoice number when no items have one`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = null),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = ""),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()
        assertNull(vm.vendorSets.value.first().invoiceNumber)
    }

    @Test
    fun `vendorSets excludes vendorless items`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "",
                status = OrderItemStatus.ORDERED.firestoreValue),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()
        val sets = vm.vendorSets.value
        assertEquals(1, sets.size)
        assertEquals("fmg", sets[0].vendorKey)
    }

    @Test
    fun `vendorSets returns empty list when order is null`() = runTest {
        val orderRepository = mockk<OrderRepository>(relaxed = true)
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(emptyList())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { vendorPriorityOrder } returns flowOf(emptyList())
        }
        val vm = OrderDetailViewModel(orderRepository, catalogRepository, preferencesRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()
        assertTrue(vm.vendorSets.value.isEmpty())
    }

    @Test
    fun `vendorSets shows invoice when only some items have one and they agree`() = runTest {
        val vm = makeViewModel(listOf(
            OrderItemEntry(beadCode = "DB0001", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = "INV-123"),
            OrderItemEntry(beadCode = "DB0002", vendorKey = "fmg",
                status = OrderItemStatus.ORDERED.firestoreValue,
                invoiceNumber = null),
        ))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.vendorSets.collect {} }
        advanceUntilIdle()
        assertEquals("INV-123", vm.vendorSets.value.first().invoiceNumber)
    }
}
