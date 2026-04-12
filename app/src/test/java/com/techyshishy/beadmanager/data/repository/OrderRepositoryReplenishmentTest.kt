package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import io.mockk.coEvery
import io.mockk.slot
import io.mockk.mockk
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that [OrderRepository.createOrderFromBeads] computes order targetGrams
 * using the effective-deficit formula:
 *
 *   max(0, projectTarget + effectiveThreshold - inventoryGrams)
 *
 * where effectiveThreshold = per-bead lowStockThresholdGrams when > 0, else globalThreshold.
 */
class OrderRepositoryReplenishmentTest {

    private val source = mockk<FirestoreOrderSource>(relaxed = true)
    private val inventoryRepository = mockk<InventoryRepository>(relaxed = true)
    private val repository = OrderRepository(
        source = source,
        inventoryRepository = inventoryRepository,
        appScope = CoroutineScope(SupervisorJob()),
    )

    private fun bead(code: String, targetGrams: Double) =
        ProjectBeadEntry(beadCode = code, targetGrams = targetGrams)

    private fun inv(quantityGrams: Double, perBeadThreshold: Double = 0.0) =
        InventoryEntry(quantityGrams = quantityGrams, lowStockThresholdGrams = perBeadThreshold)

    @Test
    fun `project deficit only — no threshold contribution`() = runTest {
        // 10g target, 3g in stock, global threshold 5g.
        // inventory (3g) < target (10g) AND post-project would be < threshold.
        // Formula: max(0, 10 + 5 - 3) = 12g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createOrderFromBeads(
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(3.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        assertEquals(12.0, slot.captured.items.single().targetGrams, 0.001)
    }

    @Test
    fun `threshold only — inventory covers project but post-project falls below threshold`() = runTest {
        // 10g target, 12g in stock, global threshold 5g.
        // Post-project inventory = 2g < 5g threshold → replenishment needed.
        // Formula: max(0, 10 + 5 - 12) = 3g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createOrderFromBeads(
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(12.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        assertEquals(3.0, slot.captured.items.single().targetGrams, 0.001)
    }

    @Test
    fun `fully sufficient — inventory covers project and threshold`() = runTest {
        // 10g target, 20g in stock, global threshold 5g.
        // Post-project inventory = 10g ≥ 5g threshold → nothing to order.
        // Formula: max(0, 10 + 5 - 20) = 0g. Item is still created but with 0g target.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createOrderFromBeads(
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(20.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        assertEquals(0.0, slot.captured.items.single().targetGrams, 0.001)
    }

    @Test
    fun `per-bead threshold overrides global threshold`() = runTest {
        // 10g target, 12g in stock, per-bead threshold 8g (overrides global 5g).
        // Post-project inventory = 2g < 8g → replenishment = max(0, 10 + 8 - 12) = 6g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createOrderFromBeads(
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(quantityGrams = 12.0, perBeadThreshold = 8.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        assertEquals(6.0, slot.captured.items.single().targetGrams, 0.001)
    }

    @Test
    fun `active order for bead uses full project target regardless of threshold`() = runTest {
        // 10g target, 12g in stock, threshold 5g — but an active order exists.
        // Active order path bypasses deficit formula and uses bead.targetGrams directly.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createOrderFromBeads(
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(12.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = mapOf("DB0001" to OrderItemStatus.PENDING),
        )

        assertEquals(10.0, slot.captured.items.single().targetGrams, 0.001)
    }

    @Test
    fun `no inventory entry uses global threshold against zero stock`() = runTest {
        // 10g target, no inventory entry (0g assumed), global threshold 5g.
        // Formula: max(0, 10 + 5 - 0) = 15g.
        val slot = slot<OrderEntry>()
        coEvery { source.createOrder(capture(slot)) } returns "order1"

        repository.createOrderFromBeads(
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = emptyMap(),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        assertEquals(15.0, slot.captured.items.single().targetGrams, 0.001)
    }

    // ── importProjectItems threshold tests ───────────────────────────────────

    @Test
    fun `importProjectItems — threshold-only bead survives early-exit filter`() = runTest {
        // 10g target, 12g in stock, global threshold 5g.
        // effectiveDeficit = max(0, 10 + 5 - 12) = 3g > 0 → survives mapNotNull.
        val updatedItems = slot<List<OrderItemEntry>>()
        coEvery { source.orderSnapshot("order1") } returns OrderEntry(items = emptyList())
        coEvery { source.updateItems("order1", capture(updatedItems)) } returns Unit
        coEvery { source.addProjectIdToOrder("order1", "p1") } returns Unit

        repository.importProjectItems(
            orderId = "order1",
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(12.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        val item = updatedItems.captured.single()
        assertEquals("DB0001", item.beadCode)
        assertEquals(3.0, item.targetGrams, 0.001)
    }

    @Test
    fun `importProjectItems — threshold-only bead merges into existing vendor-less item`() = runTest {
        // Order already has a vendor-less item for DB0001 with 4g from project p0.
        // New candidate from p1: 10g target, 12g stock, threshold 5g → deficit = 3g.
        // Merged item should have targetGrams = 4 + 3 = 7g and both contributions.
        val existingItem = OrderItemEntry(
            beadCode = "DB0001",
            vendorKey = "",
            targetGrams = 4.0,
            packGrams = 0.0,
            quantityUnits = 0,
            status = OrderItemStatus.PENDING.firestoreValue,
            sourceProjectContributions = mapOf("p0" to 4.0),
        )
        val updatedItems = slot<List<OrderItemEntry>>()
        coEvery { source.orderSnapshot("order1") } returns OrderEntry(items = listOf(existingItem))
        coEvery { source.updateItems("order1", capture(updatedItems)) } returns Unit
        coEvery { source.addProjectIdToOrder("order1", "p1") } returns Unit

        repository.importProjectItems(
            orderId = "order1",
            projectId = "p1",
            selectedBeads = listOf(bead("DB0001", 10.0)),
            inventoryEntries = mapOf("DB0001" to inv(12.0)),
            globalThresholdGrams = 5.0,
            activeOrderStatus = emptyMap(),
        )

        val merged = updatedItems.captured.single()
        assertEquals(7.0, merged.targetGrams, 0.001)
        assertEquals(4.0, merged.sourceProjectContributions["p0"] ?: 0.0, 0.001)
        assertEquals(3.0, merged.sourceProjectContributions["p1"] ?: 0.0, 0.001)
    }
}
