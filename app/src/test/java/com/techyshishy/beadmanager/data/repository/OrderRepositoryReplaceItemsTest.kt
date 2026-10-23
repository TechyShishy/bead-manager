package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies [OrderRepository.replaceItems] swaps only the named rows in one write. */
class OrderRepositoryReplaceItemsTest {

    private val source = mockk<FirestoreOrderSource>(relaxed = true)
    private val repository = OrderRepository(
        source = source,
        inventoryRepository = mockk(relaxed = true),
        appScope = CoroutineScope(SupervisorJob()),
    )

    private fun item(
        vendorKey: String,
        packGrams: Double,
        quantity: Int,
        status: OrderItemStatus = OrderItemStatus.FINALIZED,
        beadCode: String = "DB0001",
    ) = OrderItemEntry(
        beadCode = beadCode,
        vendorKey = vendorKey,
        packGrams = packGrams,
        quantityUnits = quantity,
        status = status.firestoreValue,
    )

    @Test
    fun `replaces the named rows and leaves every other row untouched`() = runTest {
        val old = item("ac", 50.0, 3)
        val received = item("fmg", 7.5, 2, OrderItemStatus.RECEIVED)
        val other = item("ac", 50.0, 1, beadCode = "DB0002")
        val slot = slot<List<OrderItemEntry>>()
        coEvery { source.updateItems("o", capture(slot)) } returns Unit

        val replacements = listOf(item("ac", 50.0, 1), item("fmg", 50.0, 2))
        repository.replaceItems("o", listOf(old, received, other), listOf(old), replacements)

        assertEquals(listOf(received, other) + replacements, slot.captured)
    }

    @Test
    fun `a replacement colliding with a retained row is rejected`() = runTest {
        val received = item("fmg", 50.0, 1, OrderItemStatus.RECEIVED)
        val old = item("ac", 50.0, 3)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.replaceItems("o", listOf(received, old), listOf(old), listOf(item("fmg", 50.0, 2)))
            }
        }
    }
}
