package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.OrderInvoice
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
 * Verifies that [OrderRepository.markVendorItemsOrdered] attaches one new invoice record to the
 * transitioning items and leaves non-FINALIZED items unchanged, that
 * [OrderRepository.updateItemStatus] always detaches an item from its invoice, and that
 * [OrderRepository.updateInvoice] edits a single invoice.
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
        invoiceId: String? = null,
    ) = OrderItemEntry(
        beadCode = beadCode,
        vendorKey = vendorKey,
        status = status.firestoreValue,
        invoiceId = invoiceId,
    )

    private val itemsSlot = slot<List<OrderItemEntry>>()
    private val invoicesSlot = slot<List<OrderInvoice>>()

    private fun stubWrite() {
        coEvery {
            source.updateItemsAndInvoices("order1", capture(itemsSlot), capture(invoicesSlot))
        } returns Unit
    }

    @Test
    fun `all FINALIZED items for the vendor share one new invoice`() = runTest {
        val allItems = listOf(
            item("DB0001", "fmg", OrderItemStatus.FINALIZED),
            item("DB0002", "fmg", OrderItemStatus.FINALIZED),
        )
        stubWrite()

        repository.markVendorItemsOrdered(
            "order1", "fmg", allItems,
            invoiceNumber = "FMG-12345", estimatedCents = 5000, actualCents = 5725,
        )

        val invoice = invoicesSlot.captured.single()
        assertEquals("fmg", invoice.vendorKey)
        assertEquals("FMG-12345", invoice.invoiceNumber)
        assertEquals(5000, invoice.estimatedCents)
        assertEquals(5725, invoice.actualCents)
        assertEquals(2, itemsSlot.captured.size)
        itemsSlot.captured.forEach { item ->
            assertEquals(OrderItemStatus.ORDERED.firestoreValue, item.status)
            assertEquals(invoice.id, item.invoiceId)
        }
    }

    @Test
    fun `a missing invoice number still creates an invoice record`() = runTest {
        stubWrite()

        repository.markVendorItemsOrdered(
            "order1", "ac", listOf(item("DB0001", "ac", OrderItemStatus.FINALIZED)),
            invoiceNumber = null, actualCents = 1234,
        )

        val invoice = invoicesSlot.captured.single()
        assertNull(invoice.invoiceNumber)
        assertEquals(1234, invoice.actualCents)
        assertEquals(invoice.id, itemsSlot.captured.single().invoiceId)
    }

    @Test
    fun `blank invoice number is stored as null`() = runTest {
        stubWrite()

        repository.markVendorItemsOrdered(
            "order1", "bob", listOf(item("DB0001", "bob", OrderItemStatus.FINALIZED)),
            invoiceNumber = "   ",
        )

        assertNull(invoicesSlot.captured.single().invoiceNumber)
    }

    @Test
    fun `existing invoices are preserved`() = runTest {
        stubWrite()
        val earlier = OrderInvoice(id = "earlier", vendorKey = "ac", actualCents = 900)

        repository.markVendorItemsOrdered(
            "order1", "fmg", listOf(item("DB0001", "fmg", OrderItemStatus.FINALIZED)),
            allInvoices = listOf(earlier),
        )

        assertEquals(2, invoicesSlot.captured.size)
        assertEquals(earlier, invoicesSlot.captured.first())
    }

    @Test
    fun `non-FINALIZED items are left unchanged`() = runTest {
        val allItems = listOf(
            item("DB0001", "fmg", OrderItemStatus.FINALIZED),
            item("DB0002", "fmg", OrderItemStatus.ORDERED, invoiceId = "earlier"),
            item("DB0003", "fmg", OrderItemStatus.PENDING),
            item("DB0004", "ac",  OrderItemStatus.FINALIZED),
        )
        stubWrite()

        repository.markVendorItemsOrdered("order1", "fmg", allItems, invoiceNumber = "INV-999")

        val updated = itemsSlot.captured
        assertEquals(4, updated.size)

        val db0001 = updated.first { it.beadCode == "DB0001" }
        assertEquals(OrderItemStatus.ORDERED.firestoreValue, db0001.status)
        assertEquals(invoicesSlot.captured.single().id, db0001.invoiceId)

        // ORDERED item keeps its earlier invoice
        val db0002 = updated.first { it.beadCode == "DB0002" }
        assertEquals(OrderItemStatus.ORDERED.firestoreValue, db0002.status)
        assertEquals("earlier", db0002.invoiceId)

        // PENDING item is unchanged
        val db0003 = updated.first { it.beadCode == "DB0003" }
        assertEquals(OrderItemStatus.PENDING.firestoreValue, db0003.status)

        // Different vendor's FINALIZED item is unchanged
        val db0004 = updated.first { it.beadCode == "DB0004" }
        assertEquals(OrderItemStatus.FINALIZED.firestoreValue, db0004.status)
        assertNull(db0004.invoiceId)
    }

    @Test
    fun `nothing is written when the vendor has no FINALIZED items`() = runTest {
        repository.markVendorItemsOrdered(
            "order1", "fmg", listOf(item("DB0001", "ac", OrderItemStatus.FINALIZED)),
        )

        coVerify(exactly = 0) { source.updateItemsAndInvoices(any(), any(), any()) }
    }

    @Test
    fun `updateItemStatus detaches the item from its invoice`() = runTest {
        // An item already on an invoice must lose it when its status is changed via
        // updateItemStatus, because that path never accepts invoice input — the existing
        // invoice would be stale.
        val itemWithInvoice = item("DB0001", "fmg", OrderItemStatus.ORDERED, invoiceId = "inv-1")
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("order1", capture(slot)) } returns Unit

        repository.updateItemStatus("order1", itemWithInvoice, listOf(itemWithInvoice), OrderItemStatus.PENDING)

        val updated = slot.captured.single()
        assertEquals(OrderItemStatus.PENDING.firestoreValue, updated.status)
        assertNull(updated.invoiceId)
    }

    @Test
    fun `updateInvoice edits the number and amount of one invoice only`() = runTest {
        val first = OrderInvoice(id = "a", vendorKey = "fmg", invoiceNumber = "OLD", actualCents = 100)
        val second = OrderInvoice(id = "b", vendorKey = "ac", invoiceNumber = "KEEP", actualCents = 200)
        val slot = slot<List<OrderInvoice>>()
        coEvery { source.updateInvoices("order1", capture(slot)) } returns Unit

        repository.updateInvoice("order1", listOf(first, second), "a", " NEW ", 4321)

        assertEquals(first.copy(invoiceNumber = "NEW", actualCents = 4321), slot.captured[0])
        assertEquals(second, slot.captured[1])
    }

    @Test
    fun `updateInvoice clears a blank number and an empty amount`() = runTest {
        val invoice = OrderInvoice(id = "a", vendorKey = "fmg", invoiceNumber = "OLD", actualCents = 100)
        val slot = slot<List<OrderInvoice>>()
        coEvery { source.updateInvoices("order1", capture(slot)) } returns Unit

        repository.updateInvoice("order1", listOf(invoice), "a", "", null)

        assertNull(slot.captured.single().invoiceNumber)
        assertNull(slot.captured.single().actualCents)
    }

    @Test
    fun `updateInvoice ignores an unknown invoice id`() = runTest {
        repository.updateInvoice("order1", listOf(OrderInvoice(id = "a")), "missing", "X", 1)

        coVerify(exactly = 0) { source.updateInvoices(any(), any()) }
    }
}
