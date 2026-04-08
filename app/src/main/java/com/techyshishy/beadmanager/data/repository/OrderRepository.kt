package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepository @Inject constructor(
    private val source: FirestoreOrderSource,
    private val inventoryRepository: InventoryRepository,
) {
    fun ordersStream(projectId: String): Flow<List<OrderEntry>> =
        source.ordersStream(projectId)

    suspend fun createOrder(entry: OrderEntry): String =
        source.createOrder(entry)

    suspend fun deleteOrder(orderId: String) =
        source.deleteOrder(orderId)

    /**
     * Transitions a line item to [OrderItemStatus.RECEIVED] and posts the inventory delta.
     *
     * Safe to call multiple times — [OrderItemEntry.appliedToInventory] guards against
     * double-counting. If the flag is already true, the inventory write is skipped and
     * only the status update proceeds (no-op if already RECEIVED).
     *
     * Inventory is written first. If the app crashes between the inventory write and the
     * Firestore status update, [appliedToInventory] stays false and the user is prompted
     * to confirm receive again — they can decline. This is the worst-case outcome and is
     * tolerable given the absence of a distributed transaction boundary here.
     *
     * @param orderId   Document ID of the containing order.
     * @param item      The item being received.
     * @param allItems  The full items list from the order (used to build the replacement array).
     * @param currentInventory  Latest inventory snapshot, keyed by beadCode.
     */
    suspend fun markItemReceived(
        orderId: String,
        item: OrderItemEntry,
        allItems: List<OrderItemEntry>,
        currentInventory: Map<String, InventoryEntry>,
    ) {
        check(allItems.count { it.beadCode == item.beadCode && it.vendorKey == item.vendorKey } == 1) {
            "Order item identity collision: ${item.beadCode}/${item.vendorKey}"
        }
        if (!item.appliedToInventory) {
            val deltaGrams = item.packGrams * item.quantityUnits
            inventoryRepository.adjustQuantity(
                beadCode = item.beadCode,
                deltaGrams = deltaGrams,
                current = currentInventory[item.beadCode],
            )
        }

        val received = item.copy(
            status = OrderItemStatus.RECEIVED.firestoreValue,
            receivedAt = com.google.firebase.Timestamp.now(),
            appliedToInventory = true,
        )
        val updatedItems = allItems.map { existing ->
            if (existing.beadCode == item.beadCode && existing.vendorKey == item.vendorKey) {
                received
            } else {
                existing
            }
        }
        source.updateItems(orderId, updatedItems)
    }

    /**
     * Updates the status of a line item to any non-received status.
     * For RECEIVED transitions, use [markItemReceived] instead.
     */
    suspend fun updateItemStatus(
        orderId: String,
        item: OrderItemEntry,
        allItems: List<OrderItemEntry>,
        newStatus: OrderItemStatus,
    ) {
        require(newStatus != OrderItemStatus.RECEIVED) {
            "Use markItemReceived() to transition items to RECEIVED status."
        }
        check(allItems.count { it.beadCode == item.beadCode && it.vendorKey == item.vendorKey } == 1) {
            "Order item identity collision: ${item.beadCode}/${item.vendorKey}"
        }
        val updated = item.copy(status = newStatus.firestoreValue)
        val updatedItems = allItems.map { existing ->
            if (existing.beadCode == item.beadCode && existing.vendorKey == item.vendorKey) {
                updated
            } else {
                existing
            }
        }
        source.updateItems(orderId, updatedItems)
    }
}
