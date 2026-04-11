package com.techyshishy.beadmanager.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.selectCheapestVendor
import com.techyshishy.beadmanager.data.scraper.NoConnectivityException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown when one or more items have no vendor with a checkable, in-stock pack combination.
 * Contains the bead codes of the affected items; finalization is blocked.
 */
class UnavailablePacksException(val beadCodes: List<String>) :
    Exception("${beadCodes.size} bead(s) have no available vendor: $beadCodes")

/**
 * A single finalized item: one order line after vendor selection, price checking, and
 * ORDERED transition.
 *
 * [fetchFailed] is true when the live check threw for this SKU; prices are from the last
 * successful check or the seed baseline. The item is still ordered.
 */
data class FinalizedItem(
    val beadCode: String,
    val vendorKey: String,
    val packGrams: Double,
    val quantityUnits: Int,
    val url: String,
    val priceCents: Int?,
    val available: Boolean?,
    val fetchFailed: Boolean,
    val imageUrl: String = "",
    val hex: String = "",
) {
    val packGramsLabel: String
        get() = BigDecimal.valueOf(packGrams).stripTrailingZeros().toPlainString()
}

data class FinalizeResult(val items: List<FinalizedItem>)

/**
 * Orchestrates order finalization:
 *
 * 1. Asserts internet connectivity.
 * 2. For each PENDING vendor-less item, loads all packs for the bead and runs per-vendor DP
 *    using the best available price data to select the cheapest vendor+combination.
 * 3. Live-checks price and availability for all candidate packs (newly selected + pre-assigned).
 * 4. Re-runs vendor selection on vendor-less items using the freshly scraped prices.
 * 5. Throws [UnavailablePacksException] for beads whose selected pack is confirmed OOS with
 *    no fallback vendor; performs automatic fallback to the next-cheapest available vendor
 *    when possible.
 * 6. Writes vendor assignment + ORDERED transition in a single Firestore call.
 *
 * A [Mutex] prevents concurrent finalize calls.
 */
@Singleton
class FinalizeOrderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderRepository: OrderRepository,
    private val catalogRepository: CatalogRepository,
) {
    private val mutex = Mutex()

    suspend fun execute(orderId: String): FinalizeResult = mutex.withLock {
        assertConnectivity()

        val order = orderRepository.orderStream(orderId).first()
            ?: error("Order $orderId not found")

        val pendingItems = order.items.filter {
            OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.PENDING
        }
        if (pendingItems.isEmpty()) {
            orderRepository.finalizeOrder(orderId, order.items, assignedItems = emptyList())
            return@withLock FinalizeResult(items = emptyList())
        }

        val (assignedItems, unassignedItems) = pendingItems.partition { it.vendorKey.isNotBlank() }
        val nowSeconds = System.currentTimeMillis() / 1000
        val beadMap = catalogRepository.allBeadsAsMap()

        // Load all packs for each unassigned bead. We'll check all of them so that after
        // the scrape we can re-run selection with fresh prices.
        val unassignedBeadCodes = unassignedItems.map { it.beadCode }.distinct()
        val packsByBead: Map<String, List<VendorPackEntity>> =
            unassignedBeadCodes.associateWith { catalogRepository.packsForBead(it) }

        // Resolve Room entities for pre-assigned packs.
        val assignedPacks = assignedItems.mapNotNull { item ->
            catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
        }

        // All packs that need a freshness check: pre-assigned + every candidate for unassigned.
        val allCandidatePacks = assignedPacks + packsByBead.values.flatten().distinctBy { it.id }
        val checkResult = catalogRepository.checkAndUpdatePacks(allCandidatePacks, nowSeconds)

        // Re-read freshened state from Room for all candidate packs.
        val freshPacksByBead: Map<String, List<VendorPackEntity>> =
            unassignedBeadCodes.associateWith { catalogRepository.packsForBead(it) }

        // Run vendor selection using fresh prices. Only packs with priceCents are eligible.
        // Each unassigned bead code must be unique — if the same bead code appears twice in
        // unassignedItems (e.g. two vendor-less entries), both would share the same selection
        // (keyed by beadCode) and both would produce the same combination. This is a data
        // inconsistency that shouldn't occur in practice, but we assert defensively.
        val selectionMap = mutableMapOf<String, com.techyshishy.beadmanager.data.repository.VendorSelection>()
        val noVendorBeadCodes = mutableListOf<String>()
        for (item in unassignedItems) {
            check(item.beadCode !in selectionMap) {
                "Duplicate unassigned bead code '${item.beadCode}' — " +
                    "each bead may appear at most once in unassigned PENDING items"
            }
            val packs = freshPacksByBead[item.beadCode] ?: emptyList()
            val available = packs.filter { it.available != false }
            val selection = selectCheapestVendor(item.beadCode, item.targetGrams, available)
            if (selection == null) {
                noVendorBeadCodes.add(item.beadCode)
            } else {
                selectionMap[item.beadCode] = selection
            }
        }

        // Pre-assigned items: re-read fresh packs and check their availability.
        val freshAssignedPacks = assignedItems.mapNotNull { item ->
            catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
        }
        val unavailableAssigned = freshAssignedPacks.filter { it.available == false }

        // For confirmed OOS assigned items, attempt fallback to the cheapest available vendor.
        val assignedFallbackFailures = mutableListOf<String>()
        val fallbackSelections = mutableMapOf<String, com.techyshishy.beadmanager.data.repository.VendorSelection>()
        // Accumulate pack entities for all fallback vendors so allFreshPacks lookup succeeds.
        val fallbackPacksByBead = mutableMapOf<String, List<VendorPackEntity>>()
        for (pack in unavailableAssigned) {
            val originalItem = assignedItems.first {
                it.beadCode == pack.beadCode && it.vendorKey == pack.vendorKey && it.packGrams == pack.grams
            }
            val allPacksForBead = catalogRepository.packsForBead(pack.beadCode)
            fallbackPacksByBead[pack.beadCode] = allPacksForBead
            val availablePacks = allPacksForBead.filter {
                it.available != false && it.priceCents != null && it.vendorKey != pack.vendorKey
            }
            val selection = selectCheapestVendor(pack.beadCode, originalItem.targetGrams, availablePacks)
            if (selection == null) {
                assignedFallbackFailures.add(pack.beadCode)
            } else {
                fallbackSelections[pack.beadCode] = selection
            }
        }

        val allBlockedBeadCodes = noVendorBeadCodes + assignedFallbackFailures
        if (allBlockedBeadCodes.isNotEmpty()) throw UnavailablePacksException(allBlockedBeadCodes.distinct())

        // Build the resolved item list for the Firestore write.
        // Pre-assigned items get their original values unless a fallback was selected.
        // Unassigned items get their auto-selected vendor+combination.
        val assignmentMap: Map<String, com.techyshishy.beadmanager.data.repository.VendorSelection> =
            selectionMap + fallbackSelections

        // Collect all definitive packs for the finalized items list, including fallback-vendor
        // packs so that URL/price lookups succeed for the OOS-fallback path.
        val allFreshPacks = (freshAssignedPacks + freshPacksByBead.values.flatten() +
            fallbackPacksByBead.values.flatten())
            .associateBy { Triple(it.beadCode, it.vendorKey, it.grams) }

        val finalizedItems = mutableListOf<FinalizedItem>()

        for (item in assignedItems) {
            val fallback = fallbackSelections[item.beadCode]
            if (fallback != null) {
                // Replaced by fallback vendor — build items from fallback combination.
                for (combo in fallback.combination) {
                    val key = Triple(item.beadCode, fallback.vendorKey, combo.packGrams)
                    val pack = allFreshPacks[key]
                    val bead = beadMap[item.beadCode]
                    finalizedItems.add(
                        FinalizedItem(
                            beadCode = item.beadCode,
                            vendorKey = fallback.vendorKey,
                            packGrams = combo.packGrams,
                            quantityUnits = combo.quantity,
                            url = pack?.url ?: "",
                            priceCents = pack?.priceCents,
                            available = pack?.available,
                            fetchFailed = false,
                            imageUrl = bead?.imageUrl ?: "",
                            hex = bead?.hex ?: "",
                        )
                    )
                }
            } else {
                val key = Triple(item.beadCode, item.vendorKey, item.packGrams)
                val pack = allFreshPacks[key]
                val bead = beadMap[item.beadCode]
                finalizedItems.add(
                    FinalizedItem(
                        beadCode = item.beadCode,
                        vendorKey = item.vendorKey,
                        packGrams = item.packGrams,
                        quantityUnits = item.quantityUnits,
                        url = pack?.url ?: "",
                        priceCents = pack?.priceCents,
                        available = pack?.available,
                        fetchFailed = pack?.id in checkResult.failedPackIds,
                        imageUrl = bead?.imageUrl ?: "",
                        hex = bead?.hex ?: "",
                    )
                )
            }
        }

        for (item in unassignedItems) {
            val selection = selectionMap[item.beadCode] ?: continue
            for (combo in selection.combination) {
                val key = Triple(item.beadCode, selection.vendorKey, combo.packGrams)
                val pack = allFreshPacks[key]
                val bead = beadMap[item.beadCode]
                finalizedItems.add(
                    FinalizedItem(
                        beadCode = item.beadCode,
                        vendorKey = selection.vendorKey,
                        packGrams = combo.packGrams,
                        quantityUnits = combo.quantity,
                        url = pack?.url ?: "",
                        priceCents = pack?.priceCents,
                        available = pack?.available,
                        fetchFailed = false,
                        imageUrl = bead?.imageUrl ?: "",
                        hex = bead?.hex ?: "",
                    )
                )
            }
        }

        // Build the assignment list for the Firestore write. Each unassigned item needs its
        // bead code mapped to resolved OrderItemEntry values. Assigned items without fallback
        // stay as-is; fallback items need their vendorKey/pack updated.
        val resolvedAssignments: List<OrderItemEntry> = buildResolvedItems(
            unassignedItems = unassignedItems,
            assignedItems = assignedItems,
            selectionMap = selectionMap,
            fallbackSelections = fallbackSelections,
        )
        orderRepository.finalizeOrder(orderId, order.items, resolvedAssignments)

        FinalizeResult(items = finalizedItems)
    }

    /**
     * Resolves an already-finalized order into a [FinalizeResult] without re-running the price
     * check. Used when the user returns to the finalize screen after navigating away. Vendor
     * assignments are read from the FINALIZED items in Firestore; URLs and last-known prices are
     * resolved from Room.
     */
    suspend fun resolveExistingOrder(orderId: String): FinalizeResult {
        val order = orderRepository.orderStream(orderId).first()
            ?: error("Order $orderId not found")
        val beadMap = catalogRepository.allBeadsAsMap()
        val finalizedItems = order.items
            .filter { OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.FINALIZED }
            .map { item ->
                val pack = catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
                val bead = beadMap[item.beadCode]
                FinalizedItem(
                    beadCode = item.beadCode,
                    vendorKey = item.vendorKey,
                    packGrams = item.packGrams,
                    quantityUnits = item.quantityUnits,
                    url = pack?.url ?: "",
                    priceCents = pack?.priceCents,
                    available = pack?.available,
                    fetchFailed = false,
                    imageUrl = bead?.imageUrl ?: "",
                    hex = bead?.hex ?: "",
                )
            }
        return FinalizeResult(items = finalizedItems)
    }

    /**
     * Builds the list of assignment updates to pass to [OrderRepository.finalizeOrder].
     *
     * Unassigned items are replaced by one entry per pack in their selection combination.
     * Assigned items with a fallback are also replaced. Assigned items without fallback
     * are passed through — [OrderRepository.finalizeOrder] will handle the ORDERED transition.
     */
    private fun buildResolvedItems(
        unassignedItems: List<OrderItemEntry>,
        assignedItems: List<OrderItemEntry>,
        selectionMap: Map<String, com.techyshishy.beadmanager.data.repository.VendorSelection>,
        fallbackSelections: Map<String, com.techyshishy.beadmanager.data.repository.VendorSelection>,
    ): List<OrderItemEntry> {
        val result = mutableListOf<OrderItemEntry>()

        for (item in unassignedItems) {
            val selection = selectionMap[item.beadCode] ?: continue
            for (combo in selection.combination) {
                result.add(
                    item.copy(
                        vendorKey = selection.vendorKey,
                        packGrams = combo.packGrams,
                        quantityUnits = combo.quantity,
                    )
                )
            }
        }

        for (item in assignedItems) {
            val fallback = fallbackSelections[item.beadCode]
            if (fallback != null) {
                for (combo in fallback.combination) {
                    result.add(
                        item.copy(
                            vendorKey = fallback.vendorKey,
                            packGrams = combo.packGrams,
                            quantityUnits = combo.quantity,
                        )
                    )
                }
            } else {
                result.add(item)
            }
        }

        return result
    }

    private fun assertConnectivity() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            throw NoConnectivityException()
        }
    }
}
