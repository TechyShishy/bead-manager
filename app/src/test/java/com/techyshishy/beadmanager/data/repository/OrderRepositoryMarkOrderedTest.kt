package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that [OrderRepository.markVendorItemsOrdered] copies the invoice number onto
 * transitioning items and leaves non-FINALIZED items unchanged. Also verifies that
 * [OrderRepository.updateItemStatus] always clears any stale invoice number, since that path
 * never accepts invoice input.
 */
class OrderRepositoryMarkOrderedTest {

    private val source = mockk<FirestoreOrderSource>(relaxed = true)
    private val inventoryRepository = mockk<InventoryRepository>(relaxed = true)
    private val repository = OrderRepository(
        source = source,
        inventoryRepository = inventoryRepository,
        appScope = CoroutineScope(SupervisorJob()),
    )

    private fun item(
        beadCode: String,
        vendorKey: String,
        status: OrderItemStatus,
        invoiceNumber: String? = null,
    ) = OrderItemEntry(
        beadCode = beadCode,
        vendorKey = vendorKey,
        status = status.firestoreValue,
        invoiceNumber = invoiceNumber,
    )

    @Test
    fun `invoice number is copied to all FINALIZED items for the vendor`() = runTest {
        val allItems = listOf(
            item("DB0001", "fmg", OrderItemStatus.FINALIZED),
            item("DB0002", "fmg", OrderItemStatus.FINALIZED),
        )
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("order1", capture(slot)) } returns Unit

        repository.markVendorItemsOrdered("order1", "fmg", allItems, invoiceNumber = "FMG-12345")

        val updated = slot.captured
        assertEquals(2, updated.size)
        updated.forEach { item ->
            assertEquals(OrderItemStatus.ORDERED.firestoreValue, item.status)
            assertEquals("FMG-12345", item.invoiceNumber)
        }
    }

    @Test
    fun `null invoice number leaves invoiceNumber null on transitioning items`() = runTest {
        val allItems = listOf(
            item("DB0001", "ac", OrderItemStatus.FINALIZED),
        )
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("order1", capture(slot)) } returns Unit

        repository.markVendorItemsOrdered("order1", "ac", allItems, invoiceNumber = null)

        val updated = slot.captured.single()
        assertEquals(OrderItemStatus.ORDERED.firestoreValue, updated.status)
        assertNull(updated.invoiceNumber)
    }

    @Test
    fun `blank invoice number is treated as null`() = runTest {
        val allItems = listOf(
            item("DB0001", "bob", OrderItemStatus.FINALIZED),
        )
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("order1", capture(slot)) } returns Unit

        repository.markVendorItemsOrdered("order1", "bob", allItems, invoiceNumber = "   ")

        val updated = slot.captured.single()
        assertNull(updated.invoiceNumber)
    }

    @Test
    fun `non-FINALIZED items are left unchanged`() = runTest {
        val allItems = listOf(
            item("DB0001", "fmg", OrderItemStatus.FINALIZED),
            item("DB0002", "fmg", OrderItemStatus.ORDERED),
            item("DB0003", "fmg", OrderItemStatus.PENDING),
            item("DB0004", "ac",  OrderItemStatus.FINALIZED),
        )
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("order1", capture(slot)) } returns Unit

        repository.markVendorItemsOrdered("order1", "fmg", allItems, invoiceNumber = "INV-999")

        val updated = slot.captured
        assertEquals(4, updated.size)

        val db0001 = updated.first { it.beadCode == "DB0001" }
        assertEquals(OrderItemStatus.ORDERED.firestoreValue, db0001.status)
        assertEquals("INV-999", db0001.invoiceNumber)

        // ORDERED item is unchanged
        val db0002 = updated.first { it.beadCode == "DB0002" }
        assertEquals(OrderItemStatus.ORDERED.firestoreValue, db0002.status)
        assertNull(db0002.invoiceNumber)

        // PENDING item is unchanged
        val db0003 = updated.first { it.beadCode == "DB0003" }
        assertEquals(OrderItemStatus.PENDING.firestoreValue, db0003.status)

        // Different vendor's FINALIZED item is unchanged
        val db0004 = updated.first { it.beadCode == "DB0004" }
        assertEquals(OrderItemStatus.FINALIZED.firestoreValue, db0004.status)
        assertNull(db0004.invoiceNumber)
    }

    @Test
    fun `updateItemStatus clears invoice number on the transitioning item`() = runTest {
        // An item that already has an invoice number (e.g. was previously marked ordered)
        // must have its invoice number cleared when its status is changed via updateItemStatus,
        // because that path never accepts invoice input — the existing number would be stale.
        val itemWithInvoice = item("DB0001", "fmg", OrderItemStatus.ORDERED, invoiceNumber = "FMG-12345")
        val allItems = listOf(itemWithInvoice)
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("order1", capture(slot)) } returns Unit

        repository.updateItemStatus("order1", itemWithInvoice, allItems, OrderItemStatus.PENDING)

        val updated = slot.captured.single()
        assertEquals(OrderItemStatus.PENDING.firestoreValue, updated.status)
        assertNull(updated.invoiceNumber)
    }
}
