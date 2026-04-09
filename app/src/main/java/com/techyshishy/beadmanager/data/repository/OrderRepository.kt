package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
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

    /**
     * Creates a new order pre-populated with vendor-less items for [selectedBeads].
     *
     * Each bead produces one [OrderItemEntry] with [vendorKey] = "" and [packGrams] = 0.0.
     * The user assigns a vendor (and the DP combination runs) on the Order Detail screen.
     *
     * [targetGrams] on each item is set to the deficit — the project target minus whatever
     * is already in [inventoryGrams] — so the order reflects only what still needs to be
     * purchased. Callers should pass the live inventory snapshot from the ViewModel.
     *
     * Returns the new order document ID.
     */
    suspend fun createOrderFromBeads(
        projectId: String,
        selectedBeads: List<ProjectBeadEntry>,
        inventoryGrams: Map<String, Double>,
    ): String {
        require(selectedBeads.isNotEmpty()) { "Cannot create an order with no beads selected." }
        val items = selectedBeads.map { bead ->
            val inStock = inventoryGrams[bead.beadCode] ?: 0.0
            val deficit = (bead.targetGrams - inStock).coerceAtLeast(0.0)
            OrderItemEntry(
                beadCode = bead.beadCode,
                vendorKey = "",
                targetGrams = deficit,
                packGrams = 0.0,
                quantityUnits = 0,
                status = OrderItemStatus.PENDING.firestoreValue,
            )
        }
        val entry = OrderEntry(projectId = projectId, items = items)
        return source.createOrder(entry)
    }

    suspend fun deleteOrder(orderId: String) =
        source.deleteOrder(orderId)

    /**
     * Appends new line items to an order.
     * Items whose (beadCode, vendorKey, packGrams) triple already exists are silently skipped.
     */
    suspend fun addItems(orderId: String, newItems: List<OrderItemEntry>, existingItems: List<OrderItemEntry>) {
        val toAdd = newItems.filter { newItem ->
            existingItems.none {
                it.beadCode == newItem.beadCode &&
                    it.vendorKey == newItem.vendorKey &&
                    it.packGrams == newItem.packGrams
            }
        }
        if (toAdd.isEmpty()) return
        source.updateItems(orderId, existingItems + toAdd)
    }

    /**
     * Assigns a vendor to the vendor-less item for [beadCode], replacing it with one or more
     * [newItems] produced from the pack combination.
     *
     * The single vendor-less item (vendorKey == "") is removed and the [newItems] are appended.
     * Triple-identity duplicates among [newItems] vs the remaining items are silently dropped.
     * If no vendor-less item for [beadCode] exists, the call is a no-op.
     */
    suspend fun assignVendor(
        orderId: String,
        beadCode: String,
        newItems: List<OrderItemEntry>,
        allItems: List<OrderItemEntry>,
    ) {
        val withoutVendorless = allItems.filter { !(it.beadCode == beadCode && it.vendorKey.isBlank()) }
        if (withoutVendorless.size == allItems.size) return  // no vendor-less item found
        val toAdd = newItems.filter { newItem ->
            withoutVendorless.none {
                it.beadCode == newItem.beadCode &&
                    it.vendorKey == newItem.vendorKey &&
                    it.packGrams == newItem.packGrams
            }
        }
        source.updateItems(orderId, withoutVendorless + toAdd)
    }

    /** Removes a line item by (beadCode, vendorKey, packGrams) identity. */
    suspend fun removeItem(orderId: String, item: OrderItemEntry, allItems: List<OrderItemEntry>) {
        val updated = allItems.filter {
            it.beadCode != item.beadCode || it.vendorKey != item.vendorKey || it.packGrams != item.packGrams
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
        check(allItems.count { it.beadCode == item.beadCode && it.vendorKey == item.vendorKey && it.packGrams == item.packGrams } == 1) {
            "Order item identity collision: ${item.beadCode}/${item.vendorKey}/${item.packGrams}"
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
            if (existing.beadCode == item.beadCode && existing.vendorKey == item.vendorKey && existing.packGrams == item.packGrams) {
                received
            } else {
                existing
            }
        }
        source.updateItems(orderId, updatedItems)
    }

    /**
     * Reverts a RECEIVED item to PENDING and reverses the inventory credit.
     *
     * The inventory decrement uses the same [InventoryRepository.adjustQuantity] path as the
     * receive — a server-side [FieldValue.increment] with a negative delta, so concurrent
     * reverts from multiple devices accumulate correctly.
     *
     * After a successful revert, [OrderItemEntry.appliedToInventory] is reset to `false` and
     * [OrderItemEntry.receivedAt] is cleared. This means a subsequent "Mark Received" call will
     * re-credit inventory, which is the correct behaviour.
     *
     * Crash window: inventory is reversed before the Firestore write. If the app crashes between
     * the two operations, the item still shows RECEIVED / appliedToInventory = true, but one
     * pack-quantity of grams has already been subtracted from inventory. The user can retry the
     * revert; the inventory adjustment will fire a second time, going further negative. This is
     * the same class of risk accepted in [markItemReceived] and is tolerable without a
     * distributed transaction boundary.
     *
     * @param orderId   Document ID of the containing order.
     * @param item      The RECEIVED item to revert.
     * @param allItems  The full items list from the order.
     */
    suspend fun revertItemReceived(
        orderId: String,
        item: OrderItemEntry,
        allItems: List<OrderItemEntry>,
    ) {
        check(allItems.count { it.beadCode == item.beadCode && it.vendorKey == item.vendorKey && it.packGrams == item.packGrams } == 1) {
            "Order item identity collision: ${item.beadCode}/${item.vendorKey}/${item.packGrams}"
        }
        if (item.appliedToInventory) {
            val deltaGrams = -(item.packGrams * item.quantityUnits)
            inventoryRepository.adjustQuantity(
                beadCode = item.beadCode,
                deltaGrams = deltaGrams,
            )
        }
        val reverted = item.copy(
            status = OrderItemStatus.PENDING.firestoreValue,
            appliedToInventory = false,
            receivedAt = null,
        )
        val updatedItems = allItems.map { existing ->
            if (existing.beadCode == item.beadCode && existing.vendorKey == item.vendorKey && existing.packGrams == item.packGrams) {
                reverted
            } else {
                existing
            }
        }
        source.updateItems(orderId, updatedItems)
    }

    /**
     * Updates the status of a line item to any non-received status.
     * For RECEIVED transitions, use [markItemReceived] instead.
     * For reverting a RECEIVED item (with inventory reversal), use [revertItemReceived] instead.
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
        require(item.status != OrderItemStatus.RECEIVED.firestoreValue) {
            "Use revertItemReceived() to revert RECEIVED items — inventory reversal is required."
        }
        check(allItems.count { it.beadCode == item.beadCode && it.vendorKey == item.vendorKey && it.packGrams == item.packGrams } == 1) {
            "Order item identity collision: ${item.beadCode}/${item.vendorKey}/${item.packGrams}"
        }
        val updated = item.copy(status = newStatus.firestoreValue)
        val updatedItems = allItems.map { existing ->
            if (existing.beadCode == item.beadCode && existing.vendorKey == item.vendorKey && existing.packGrams == item.packGrams) {
                updated
            } else {
                existing
            }
        }
        source.updateItems(orderId, updatedItems)
    }
}
