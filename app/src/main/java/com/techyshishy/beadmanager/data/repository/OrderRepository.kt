package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.ProjectBeadEntry
import com.techyshishy.beadmanager.data.firestore.effectiveContributions
import com.techyshishy.beadmanager.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepository @Inject constructor(
    private val source: FirestoreOrderSource,
    private val inventoryRepository: InventoryRepository,
    @AppScope private val appScope: CoroutineScope,
) {
    fun ordersStream(projectId: String): Flow<List<OrderEntry>> =
        source.ordersStream(projectId)

    // Single shared listener for the full orders collection.
    private val sharedAllOrders =
        source.allOrdersStream()
            .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun allOrdersStream(): Flow<List<OrderEntry>> = sharedAllOrders

    fun orderStream(orderId: String): Flow<OrderEntry?> =
        source.orderStream(orderId)

    /** Used by MigrationViewModel to backfill projectIds from legacy projectId field. */
    suspend fun getAllOrdersSnapshot(): List<OrderEntry> =
        source.getAllOrdersSnapshot()

    /** Used by MigrationViewModel to write projectId into projectIds array. */
    suspend fun addProjectIdToOrder(orderId: String, projectId: String) =
        source.addProjectIdToOrder(orderId, projectId)

    suspend fun createOrder(entry: OrderEntry): String =
        source.createOrder(entry)

    /**
     * Creates a new order pre-populated with vendor-less items for [selectedBeads].
     *
     * Each bead produces one [OrderItemEntry] with [vendorKey] = "" and [packGrams] = 0.0.
     * The user assigns a vendor (and the DP combination runs) on the Order Detail screen.
     *
     * [targetGrams] per item is the effective deficit:
     *   max(0, projectTarget + effectiveThreshold - inventoryGrams)
     * where effectiveThreshold = per-bead override when > 0, else [globalThresholdGrams].
     *
     * When [activeOrderStatus] already has an active order for the bead the full project
     * target is used (the user is explicitly creating a backup/repeat order).
     *
     * Returns the new order document ID.
     */
    suspend fun createOrderFromBeads(
        projectId: String,
        selectedBeads: List<ProjectBeadEntry>,
        inventoryEntries: Map<String, InventoryEntry>,
        globalThresholdGrams: Double,
        activeOrderStatus: Map<String, OrderItemStatus>,
    ): String {
        require(selectedBeads.isNotEmpty()) { "Cannot create an order with no beads selected." }
        val items = selectedBeads.map { bead ->
            val targetGrams = if (activeOrderStatus.containsKey(bead.beadCode)) {
                bead.targetGrams
            } else {
                val inv = inventoryEntries[bead.beadCode]
                val inStock = inv?.quantityGrams ?: 0.0
                val threshold = if ((inv?.lowStockThresholdGrams ?: 0.0) > 0.0) inv!!.lowStockThresholdGrams
                                else globalThresholdGrams
                (bead.targetGrams + threshold - inStock).coerceAtLeast(0.0)
            }
            OrderItemEntry(
                beadCode = bead.beadCode,
                vendorKey = "",
                targetGrams = targetGrams,
                packGrams = 0.0,
                quantityUnits = 0,
                status = OrderItemStatus.PENDING.firestoreValue,
                sourceProjectContributions = mapOf(projectId to targetGrams),
            )
        }
        val entry = OrderEntry(projectIds = listOf(projectId), items = items)
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
        val replacedContributions = allItems
            .firstOrNull { it.beadCode == beadCode && it.vendorKey.isBlank() }
            ?.effectiveContributions() ?: emptyMap()
        val toAdd = newItems.filter { newItem ->
            withoutVendorless.none {
                it.beadCode == newItem.beadCode &&
                    it.vendorKey == newItem.vendorKey &&
                    it.packGrams == newItem.packGrams
            }
        }.map { it.copy(sourceProjectContributions = replacedContributions) }
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

    /**
     * Transitions PENDING items to ORDERED, applying vendor assignments for previously unassigned
     * or fallback-reassigned items.
     *
     * [assignedItems] is the resolved list produced by [FinalizeOrderUseCase]: each entry has a
     * definitive vendorKey, packGrams, and quantityUnits. Items in [allItems] that are vendor-less
     * (or OOS and replaced by a fallback) are replaced by their entries in [assignedItems]. Items
     * not present in [assignedItems] are left unchanged except for the PENDING → ORDERED transition.
     *
     * Only items with [vendorKey] non-blank are transitioned to ORDERED.
     */
    suspend fun finalizeOrder(
        orderId: String,
        allItems: List<OrderItemEntry>,
        assignedItems: List<OrderItemEntry>,
    ) {
        // Start by removing all vendor-less PENDING items from allItems and any PENDING items
        // whose bead code appears in assignedItems (they will be replaced).
        val beadCodesWithAssignment = assignedItems.map { it.beadCode }.toSet()
        val retained = allItems.filter { item ->
            val isPending = OrderItemStatus.fromFirestore(item.status) == OrderItemStatus.PENDING
            !(isPending && item.beadCode in beadCodesWithAssignment)
        }

        // Add the resolved items (already have correct vendorKey/pack), transitioned to FINALIZED.
        // Items remain FINALIZED until the user explicitly marks each vendor's items as ordered
        // from the finalize screen.
        val resolved = assignedItems.map { item ->
            if (item.vendorKey.isNotBlank()) {
                item.copy(status = OrderItemStatus.FINALIZED.firestoreValue)
            } else {
                item
            }
        }

        source.updateItems(orderId, retained + resolved)
    }

    suspend fun removeProjectIdFromOrder(orderId: String, projectId: String) =
        source.removeProjectIdFromOrder(orderId, projectId)

    /**
     * Transitions all FINALIZED items for [vendorKey] to ORDERED.
     * Items in any other status are left unchanged.
     */
    suspend fun markVendorItemsOrdered(
        orderId: String,
        vendorKey: String,
        allItems: List<OrderItemEntry>,
    ) {
        val updated = allItems.map { item ->
            if (item.vendorKey == vendorKey &&
                OrderItemStatus.fromFirestore(item.status) == OrderItemStatus.FINALIZED
            ) {
                item.copy(status = OrderItemStatus.ORDERED.firestoreValue)
            } else {
                item
            }
        }
        source.updateItems(orderId, updated)
    }

    /**
     * Reverts all FINALIZED items to PENDING and clears their vendor assignments.
     * Items in any other status are left unchanged.
     *
     * Pack-split finalization creates multiple FINALIZED items per bead (one per pack size),
     * each carrying identical targetGrams and sourceProjectContributions copied from the
     * original vendor-less item. After reverting all of them to vendor-less, duplicates with
     * the same beadCode would exist. The first occurrence is kept; extras are dropped.
     * targetGrams and contributions are the same on all split items so keeping the first is
     * semantically correct.
     */
    suspend fun reopenOrder(
        orderId: String,
        allItems: List<OrderItemEntry>,
    ) {
        val reverted = allItems.map { item ->
            if (OrderItemStatus.fromFirestore(item.status) == OrderItemStatus.FINALIZED) {
                item.copy(
                    status = OrderItemStatus.PENDING.firestoreValue,
                    vendorKey = "",
                    packGrams = 0.0,
                    quantityUnits = 0,
                )
            } else {
                item
            }
        }
        val seenVendorless = mutableSetOf<String>()
        val updated = reverted.filter { item ->
            if (item.vendorKey.isBlank() && item.packGrams == 0.0) {
                seenVendorless.add(item.beadCode)  // true on first insert, false on duplicate → deduplicates
            } else {
                true
            }
        }
        source.updateItems(orderId, updated)
    }

    /**
     * Detaches [projectId] from an order:
     *  1. For each item, subtracts this project's recorded contribution from [targetGrams].
     *     - Item has no entry for this project in [effectiveContributions] (manually added,
     *       or predates M3): preserved unchanged.
     *     - Item's [targetGrams] drops to ≤ 0 after subtraction: removed entirely.
     *     - Item still has remaining contributors: updated with reduced [targetGrams] and the
     *       project removed from [OrderItemEntry.sourceProjectContributions].
     *  2. Removes [projectId] from [OrderEntry.projectIds] via arrayRemove.
     *
     * Crash window: if the app terminates between the item removal write and the projectIds
     * update, the items are gone but the projectId remains in the array. The order still shows
     * as "belonging to" the project, but the user can re-detach, which is a safe no-op on
     * items and completes the projectIds cleanup.
     */
    suspend fun detachProject(
        orderId: String,
        projectId: String,
        allItems: List<OrderItemEntry>,
    ) {
        val updated = allItems.mapNotNull { item ->
            val contributions = item.effectiveContributions()
            val contribution = contributions[projectId]
            if (contribution == null) {
                item  // not contributed by this project; preserve unchanged
            } else {
                val remaining = contributions - projectId
                val newTargetGrams = item.targetGrams - contribution
                if (newTargetGrams <= 0.0) {
                    null  // sole or full contributor; remove item
                } else {
                    item.copy(
                        targetGrams = newTargetGrams,
                        sourceProjectContributions = remaining,
                    )
                }
            }
        }
        source.updateItems(orderId, updated)
        source.removeProjectIdFromOrder(orderId, projectId)
    }

    /**
     * Adds items from [projectId]'s selected beads to an existing order, then registers
     * [projectId] in [OrderEntry.projectIds] via arrayUnion.
     *
     * Each bead produces one vendor-less [OrderItemEntry] with [targetGrams] set to the
     * inventory deficit (or full project target for repeat orders). Items with zero deficit
     * are skipped. [targetGrams] is computed by the same logic as [createOrderFromBeads]:
     * full project target when [activeOrderStatus] records an active order for the bead
     * (indicating a parallel re-order), otherwise the inventory deficit.
     *
     * If a vendor-less item for the same bead already exists in the order, the deficit is
     * merged into that item ([targetGrams] accumulated, project added to
     * [OrderItemEntry.sourceProjectContributions]) rather than creating a duplicate.
     *
     * The existing items for the order are fetched from Firestore (cache-first) to provide
     * the merge/deduplication baseline, so no [existingItems] parameter is needed at the
     * call site.
     */
    suspend fun importProjectItems(
        orderId: String,
        projectId: String,
        selectedBeads: List<ProjectBeadEntry>,
        inventoryEntries: Map<String, InventoryEntry>,
        globalThresholdGrams: Double,
        activeOrderStatus: Map<String, OrderItemStatus>,
    ) {
        require(selectedBeads.isNotEmpty()) { "Cannot import with no beads selected." }
        val existingItems = source.orderSnapshot(orderId)?.items ?: emptyList()

        // Build candidate items for this project.
        val candidateItems = selectedBeads.mapNotNull { bead ->
            val targetGrams = if (activeOrderStatus.containsKey(bead.beadCode)) {
                bead.targetGrams
            } else {
                val inv = inventoryEntries[bead.beadCode]
                val inStock = inv?.quantityGrams ?: 0.0
                val threshold = if ((inv?.lowStockThresholdGrams ?: 0.0) > 0.0) inv!!.lowStockThresholdGrams
                                else globalThresholdGrams
                (bead.targetGrams + threshold - inStock).coerceAtLeast(0.0)
            }
            if (targetGrams == 0.0) return@mapNotNull null  // nothing to contribute; skip
            OrderItemEntry(
                beadCode = bead.beadCode,
                vendorKey = "",
                targetGrams = targetGrams,
                packGrams = 0.0,
                quantityUnits = 0,
                status = OrderItemStatus.PENDING.firestoreValue,
                sourceProjectContributions = mapOf(projectId to targetGrams),
            )
        }
        // All selected beads are already covered by inventory; nothing to contribute.
        if (candidateItems.isEmpty()) return

        // Merge into existing vendor-less items where the same bead already appears;
        // append as new items where it does not.
        var hasMerges = false
        val mutableExisting = existingItems.toMutableList()
        val toAppend = mutableListOf<OrderItemEntry>()
        for (candidate in candidateItems) {
            val existingIndex = mutableExisting.indexOfFirst {
                it.beadCode == candidate.beadCode && it.vendorKey.isBlank() && it.packGrams == 0.0
            }
            if (existingIndex >= 0) {
                val existing = mutableExisting[existingIndex]
                @Suppress("DEPRECATION")
                mutableExisting[existingIndex] = existing.copy(
                    targetGrams = existing.targetGrams + candidate.targetGrams,
                    sourceProjectContributions = existing.effectiveContributions() + candidate.sourceProjectContributions,
                    sourceProjectId = "",  // clear stale M3 scalar now that map owns the state
                )
                hasMerges = true
            } else {
                toAppend.add(candidate)
            }
        }

        if (hasMerges || toAppend.isNotEmpty()) {
            source.updateItems(orderId, mutableExisting + toAppend)
        }
        source.addProjectIdToOrder(orderId, projectId)
    }
}
