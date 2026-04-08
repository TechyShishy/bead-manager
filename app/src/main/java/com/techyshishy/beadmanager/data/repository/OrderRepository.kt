package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
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

    fun orderStream(orderId: String): Flow<OrderEntry?> =
        source.orderStream(orderId)

    suspend fun createOrder(entry: OrderEntry): String =
        source.createOrder(entry)

    suspend fun deleteOrder(orderId: String) =
        source.deleteOrder(orderId)

    /** Appends a new item to an order. No-op if the (beadCode, vendorKey) pair already exists. */
    suspend fun addItem(orderId: String, newItem: OrderItemEntry, existingItems: List<OrderItemEntry>) {
        val alreadyPresent = existingItems.any {
            it.beadCode == newItem.beadCode && it.vendorKey == newItem.vendorKey
        }
        if (alreadyPresent) return
        source.updateItems(orderId, existingItems + newItem)
    }

    /** Removes a line item by (beadCode, vendorKey) identity. */
    suspend fun removeItem(orderId: String, item: OrderItemEntry, allItems: List<OrderItemEntry>) {
        val updated = allItems.filter {
            it.beadCode != item.beadCode || it.vendorKey != item.vendorKey
        }
        source.updateItems(orderId, updated)
    }

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
     * The inventory increment is atomic on the server ([FieldValue.increment] via
     * [InventoryRepository.adjustQuantity]), so concurrent receives from multiple devices
     * accumulate correctly.
     *
     * @param orderId   Document ID of the containing order.
     * @param item      The item being received.
     * @param allItems  The full items list from the order (used to build the replacement array).
     */
    suspend fun markItemReceived(
        orderId: String,
        item: OrderItemEntry,
        allItems: List<OrderItemEntry>,
    ) {
        check(allItems.count { it.beadCode == item.beadCode && it.vendorKey == item.vendorKey } == 1) {
            "Order item identity collision: ${item.beadCode}/${item.vendorKey}"
        }
        require(item.quantityUnits > 0 && item.packGrams > 0.0) {
            "Cannot receive item with zero quantity or pack size: ${item.beadCode}/${item.vendorKey}"
        }
        if (!item.appliedToInventory) {
            val deltaGrams = item.packGrams * item.quantityUnits
            inventoryRepository.adjustQuantity(
                beadCode = item.beadCode,
                deltaGrams = deltaGrams,
            )
        }

        val received = item.copy(
            status = OrderItemStatus.RECEIVED.firestoreValue,
            // Preserve the original timestamp on re-calls (e.g. retry after partial failure).
            // Only set when first marking received; client Timestamp.now() is the only option
            // because FieldValue.serverTimestamp() is not supported inside array elements.
            receivedAt = item.receivedAt ?: com.google.firebase.Timestamp.now(),
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
     *
     * Reverting FROM [OrderItemStatus.RECEIVED] is permitted. The item's
     * [OrderItemEntry.appliedToInventory] flag is intentionally left true — no inventory
     * reversal is performed. This means a re-received item will skip the inventory write
     * (idempotency is preserved) but will show a PENDING/ORDERED/SKIPPED status in the UI
     * while inventory has already been credited.
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
