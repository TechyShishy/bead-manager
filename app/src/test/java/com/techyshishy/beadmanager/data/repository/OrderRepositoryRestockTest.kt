package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import io.mockk.coEvery
import io.mockk.slot
import io.mockk.mockk
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [OrderRepository.createRestockOrder] computes order targetGrams using the
 * threshold-only replenishment formula:
 *
 *   max(0, effectiveThreshold − quantityGrams)
 *
 * where effectiveThreshold = per-bead lowStockThresholdGrams when > 0, else globalThresholdGrams.
 *
 * Items where targetGrams == 0.0 are excluded. The resulting OrderEntry has projectIds = empty
 * and sourceProjectContributions = emptyMap() on all items.
 */
class OrderRepositoryRestockTest {

    private val source = mockk<FirestoreOrderSource>(relaxed = true)
    private val inventoryRepository = mockk<InventoryRepository>(relaxed = true)
    private val repository = OrderRepository(
        source = source,
        inventoryRepository = inventoryRepository,
        appScope = CoroutineScope(SupervisorJob()),
    )

    private fun inv(quantityGrams: Double, perBeadThreshold: Double = 0.0) =
        InventoryEntry(quantityGrams = quantityGrams, lowStockThresholdGrams = perBeadThreshold)

    @Test
    fun `threshold-only formula — deficit equals threshold minus stock`() = runTest {
        // 3g in stock, 5g global threshold → targetGrams = 5 - 3 = 2g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createRestockOrder(
            beadCodes = setOf("DB0001"),
            inventory = mapOf("DB0001" to inv(3.0)),
            globalThresholdGrams = 5.0,
        )

        val item = slot.captured.items.single()
        assertEquals("DB0001", item.beadCode)
        assertEquals(2.0, item.targetGrams, 0.001)
        assertEquals(OrderItemStatus.PENDING.firestoreValue, item.status)
        assertEquals(emptyMap<String, Double>(), item.sourceProjectContributions)
    }

    @Test
    fun `per-bead threshold overrides global threshold`() = runTest {
        // 3g in stock, per-bead threshold 8g (global 5g) → targetGrams = 8 - 3 = 5g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createRestockOrder(
            beadCodes = setOf("DB0001"),
            inventory = mapOf("DB0001" to inv(quantityGrams = 3.0, perBeadThreshold = 8.0)),
            globalThresholdGrams = 5.0,
        )

        assertEquals(5.0, slot.captured.items.single().targetGrams, 0.001)
    }

    @Test
    fun `zero-target item is excluded — other items still included`() = runTest {
        // DB0001: 10g in stock, 5g threshold → deficit = 0 → excluded.
        // DB0002: 2g in stock, 5g threshold → deficit = 3g → included.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createRestockOrder(
            beadCodes = setOf("DB0001", "DB0002"),
            inventory = mapOf(
                "DB0001" to inv(10.0),
                "DB0002" to inv(2.0),
            ),
            globalThresholdGrams = 5.0,
        )

        val items = slot.captured.items
        assertEquals(1, items.size)
        assertEquals("DB0002", items.single().beadCode)
        assertEquals(3.0, items.single().targetGrams, 0.001)
    }

    @Test
    fun `all items resolve to zero throws IllegalArgumentException`() {
        // Both beads are at or above threshold → no items survive → must throw.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.createRestockOrder(
                    beadCodes = setOf("DB0001", "DB0002"),
                    inventory = mapOf(
                        "DB0001" to inv(10.0),
                        "DB0002" to inv(20.0),
                    ),
                    globalThresholdGrams = 5.0,
                )
            }
        }
    }

    @Test
    fun `empty beadCodes throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.createRestockOrder(
                    beadCodes = emptySet(),
                    inventory = emptyMap(),
                    globalThresholdGrams = 5.0,
                )
            }
        }
    }

    @Test
    fun `resulting order has empty projectIds`() = runTest {
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createRestockOrder(
            beadCodes = setOf("DB0001"),
            inventory = mapOf("DB0001" to inv(1.0)),
            globalThresholdGrams = 5.0,
        )

        assertTrue(slot.captured.projectIds.isEmpty())
    }

    @Test
    fun `no inventory entry treats stock as zero`() = runTest {
        // No inventory entry for DB0001 → stock assumed 0 → targetGrams = 5 - 0 = 5g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createRestockOrder(
            beadCodes = setOf("DB0001"),
            inventory = emptyMap(),
            globalThresholdGrams = 5.0,
        )

        assertEquals(5.0, slot.captured.items.single().targetGrams, 0.001)
    }
}
