package com.techyshishy.beadmanager.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.SUFFICIENT_THRESHOLD_GRAMS
import com.techyshishy.beadmanager.data.model.effectiveThresholdFor
import com.techyshishy.beadmanager.data.repository.BuyUpSuggestion
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PackCombination
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.PriceCheckResult
import com.techyshishy.beadmanager.data.repository.VendorSelection
import com.techyshishy.beadmanager.data.repository.analyzeBuyUp
import com.techyshishy.beadmanager.data.repository.applyFmgTier
import com.techyshishy.beadmanager.data.repository.fmgEffectivePriceCents
import com.techyshishy.beadmanager.data.repository.selectAllocation
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
 * Thrown by [FinalizeOrderUseCase.commit] when the chosen buy-up bead code has no available
 * FMG packs. The user should be prompted to choose a different bead.
 */
class InvalidBuyUpBeadException(val beadCode: String) :
    Exception("No available FMG pack for buy-up bead code: $beadCode")

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
    /** Vendor-specific color name, resolved from [VendorLinkEntity.beadName]. Null when unavailable. */
    val colorName: String? = null,
    /** True when this item was added as a buy-up to reach a discount tier. */
    val isBuyUp: Boolean = false,
) {
    val packGramsLabel: String
        get() = BigDecimal.valueOf(packGrams).stripTrailingZeros().toPlainString()
}

data class FinalizeResult(val items: List<FinalizedItem>)

/**
 * Intermediate result of [FinalizeOrderUseCase.analyze]. Carries all state needed for
 * [FinalizeOrderUseCase.commit] without re-running scraping or vendor selection.
 *
 * [items] shows tier-adjusted prices at the current FMG unit count — suitable for a
 * preview before the user decides on buy-up.
 * [buyUpSuggestion] is non-null when adding units would reduce the total order cost by
 * crossing the next FMG quantity-break tier.
 */
data class OrderAnalysis(
    val orderId: String,
    val items: List<FinalizedItem>,
    val buyUpSuggestion: BuyUpSuggestion?,
    val unassignedItems: List<OrderItemEntry>,
    val assignedItems: List<OrderItemEntry>,
    /** Per bead, one selection per vendor used; several when stock forces a split. */
    val selectionMap: Map<String, List<VendorSelection>>,
    val fallbackSelections: Map<String, List<VendorSelection>>,
    val allFreshPacks: Map<Triple<String, String, Double>, VendorPackEntity>,
    val allOrderItems: List<OrderItemEntry>,
    val beadMap: Map<String, BeadEntity>,
    val checkResult: PriceCheckResult,
    val currentFmgTotalUnits: Int,
    val fmgPacksByBead: Map<String, List<VendorPackEntity>>,
)

/** Each pack combination of every vendor part, paired with the part it belongs to. */
private fun List<VendorSelection>.flattenCombinations(): List<Pair<VendorSelection, PackCombination>> =
    flatMap { part -> part.combination.map { part to it } }

/**
 * Orchestrates order finalization in two phases:
 *
 * Phase 1 — [analyze]: scrapes live prices, runs vendor selection respecting the
 * configured vendor priority order, applies FMG quantity-break tier pricing, and
 * detects whether a buy-up would reduce total cost. Does NOT write to Firestore.
 *
 * Phase 2 — [commit]: takes the [OrderAnalysis] from phase 1 and the user's buy-up
 * decision, writes vendor assignments and ORDERED transition in a single Firestore call.
 *
 * A [Mutex] prevents concurrent calls to both phases.
 */
@Singleton
class FinalizeOrderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderRepository: OrderRepository,
    private val catalogRepository: CatalogRepository,
    private val inventoryRepository: InventoryRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    private val mutex = Mutex()

    /**
     * Phase 1: scrapes prices, selects vendors per the configured priority order, applies
     * FMG tier pricing, and detects buy-up opportunities. Does NOT write to Firestore.
     *
     * Throws [UnavailablePacksException] when one or more beads have no available vendor.
     * Throws [NoConnectivityException] when the device has no validated internet.
     */
    suspend fun analyze(orderId: String): OrderAnalysis = mutex.withLock {
        assertConnectivity()

        val vendorPriority = preferencesRepository.vendorPriorityOrder.first()
        val buyUpEnabled = preferencesRepository.buyUpEnabled.first()

        val order = orderRepository.orderStream(orderId).first()
            ?: error("Order $orderId not found")

        val pendingItems = order.items.filter {
            OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.PENDING
        }
        if (pendingItems.isEmpty()) {
            orderRepository.finalizeOrder(orderId, order.items, assignedItems = emptyList())
            return@withLock OrderAnalysis(
                orderId = orderId,
                items = emptyList(),
                buyUpSuggestion = null,
                unassignedItems = emptyList(),
                assignedItems = emptyList(),
                selectionMap = emptyMap(),
                fallbackSelections = emptyMap(),
                allFreshPacks = emptyMap(),
                allOrderItems = order.items,
                beadMap = emptyMap(),
                checkResult = PriceCheckResult(failedPackIds = emptySet()),
                currentFmgTotalUnits = 0,
                fmgPacksByBead = emptyMap(),
            )
        }

        val (assignedItems, unassignedItems) = pendingItems.partition { it.vendorKey.isNotBlank() }
        val nowSeconds = System.currentTimeMillis() / 1000
        val beadMap = catalogRepository.allBeadsAsMap()
        val beadNames = catalogRepository.vendorNamesForBeads(pendingItems.map { it.beadCode }.distinct())

        val unassignedBeadCodes = unassignedItems.map { it.beadCode }.distinct()
        val packsByBead: Map<String, List<VendorPackEntity>> =
            unassignedBeadCodes.associateWith { catalogRepository.packsForBead(it) }

        val assignedPacks = assignedItems.mapNotNull { item ->
            catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
        }

        val allCandidatePacks = assignedPacks + packsByBead.values.flatten().distinctBy { it.id }
        val checkResult = catalogRepository.checkAndUpdatePacks(allCandidatePacks, nowSeconds)

        val freshPacksByBead: Map<String, List<VendorPackEntity>> =
            unassignedBeadCodes.associateWith { catalogRepository.packsForBead(it) }

        val selectionMap = mutableMapOf<String, List<VendorSelection>>()
        val noVendorBeadCodes = mutableListOf<String>()
        for (item in unassignedItems) {
            check(item.beadCode !in selectionMap) {
                "Duplicate unassigned bead code '${item.beadCode}' — " +
                    "each bead may appear at most once in unassigned PENDING items"
            }
            val packs = freshPacksByBead[item.beadCode] ?: emptyList()
            val selection = selectAllocation(item.beadCode, item.targetGrams, packs, vendorPriority)
            if (selection == null) {
                noVendorBeadCodes.add(item.beadCode)
            } else {
                selectionMap[item.beadCode] = selection
            }
        }

        val freshAssignedPacks = assignedItems.mapNotNull { item ->
            catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
        }
        // An assigned pack needs a fallback when it is out of stock, or when the vendor reports
        // fewer units than the assigned row asks for.
        val unavailableAssigned = freshAssignedPacks.filter { pack ->
            pack.available == false || assignedItems.any { item ->
                item.beadCode == pack.beadCode && item.vendorKey == pack.vendorKey &&
                    item.packGrams == pack.grams &&
                    pack.stockQuantity != null && item.quantityUnits > pack.stockQuantity
            }
        }

        val assignedFallbackFailures = mutableListOf<String>()
        val fallbackSelections = mutableMapOf<String, List<VendorSelection>>()
        val fallbackPacksByBead = mutableMapOf<String, List<VendorPackEntity>>()
        for (pack in unavailableAssigned) {
            val originalItem = assignedItems.first {
                it.beadCode == pack.beadCode && it.vendorKey == pack.vendorKey && it.packGrams == pack.grams
            }
            val allPacksForBead = catalogRepository.packsForBead(pack.beadCode)
            fallbackPacksByBead[pack.beadCode] = allPacksForBead
            val fallbackPriority = vendorPriority.filter { it != pack.vendorKey }
            val availablePacks = allPacksForBead.filter { it.available != false && it.priceCents != null }
            val selection = selectAllocation(
                pack.beadCode, originalItem.targetGrams, availablePacks, fallbackPriority,
            )
            if (selection == null) {
                assignedFallbackFailures.add(pack.beadCode)
            } else {
                fallbackSelections[pack.beadCode] = selection
            }
        }

        val allBlockedBeadCodes = noVendorBeadCodes + assignedFallbackFailures
        if (allBlockedBeadCodes.isNotEmpty()) throw UnavailablePacksException(allBlockedBeadCodes.distinct())

        val allFreshPacks = (freshAssignedPacks + freshPacksByBead.values.flatten() +
            fallbackPacksByBead.values.flatten())
            .associateBy { Triple(it.beadCode, it.vendorKey, it.grams) }

        // Build a FMG selection map for tier calculations (includes pre-assigned FMG items).
        val fmgSelectionsByBead: Map<String, VendorSelection> = buildMap {
            for ((beadCode, parts) in selectionMap) {
                parts.firstOrNull { it.vendorKey == "fmg" }?.let { put(beadCode, it) }
            }
            for ((beadCode, parts) in fallbackSelections) {
                parts.firstOrNull { it.vendorKey == "fmg" }?.let { put(beadCode, it) }
            }
            // Pre-assigned FMG items not replaced by fallback, grouped by bead code.
            val preAssignedByBead = assignedItems
                .filter { it.vendorKey == "fmg" && it.beadCode !in fallbackSelections }
                .groupBy { it.beadCode }
            for ((beadCode, items) in preAssignedByBead) {
                if (beadCode !in this) {
                    val combos = items.mapNotNull { item ->
                        val pack = allFreshPacks[Triple(beadCode, "fmg", item.packGrams)]
                            ?: return@mapNotNull null
                        PackCombination(pack.grams, item.quantityUnits)
                    }
                    val totalCents = items.sumOf { item ->
                        val pack = allFreshPacks[Triple(beadCode, "fmg", item.packGrams)]
                        (pack?.priceCents ?: 0) * item.quantityUnits
                    }
                    if (combos.isNotEmpty()) put(beadCode, VendorSelection("fmg", combos, totalCents))
                }
            }
        }

        val currentFmgTotalUnits = fmgSelectionsByBead.values.sumOf { sel ->
            sel.combination.sumOf { it.quantity }
        }

        val fmgPacksByBead: Map<String, List<VendorPackEntity>> = buildMap {
            for (beadCode in fmgSelectionsByBead.keys) {
                val packs = allFreshPacks.values.filter {
                    it.beadCode == beadCode && it.vendorKey == "fmg"
                }
                if (packs.isNotEmpty()) put(beadCode, packs)
            }
        }

        // Rebuild FMG selections with tier-adjusted totalCents.
        val tieredFmgSelectionsByBead = fmgSelectionsByBead.mapValues { (beadCode, sel) ->
            val beadPacks = fmgPacksByBead[beadCode] ?: return@mapValues sel
            applyFmgTier(sel, beadPacks, currentFmgTotalUnits)
        }

        // Effective unit price for one pack in the finalized order.
        fun effectivePrice(pack: VendorPackEntity?): Int? {
            if (pack == null) return null
            return if (pack.vendorKey == "fmg") {
                fmgEffectivePriceCents(pack, currentFmgTotalUnits)
            } else {
                pack.priceCents
            }
        }

        val finalizedItems = mutableListOf<FinalizedItem>()

        for (item in assignedItems) {
            val fallbackParts = fallbackSelections[item.beadCode]
            if (fallbackParts != null) {
                for ((fallback, combo) in fallbackParts.flattenCombinations()) {
                    val key = Triple(item.beadCode, fallback.vendorKey, combo.packGrams)
                    val pack = allFreshPacks[key]
                    val bead = beadMap[item.beadCode]
                    finalizedItems.add(FinalizedItem(
                        beadCode = item.beadCode,
                        vendorKey = fallback.vendorKey,
                        packGrams = combo.packGrams,
                        quantityUnits = combo.quantity,
                        url = pack?.url ?: "",
                        priceCents = effectivePrice(pack),
                        available = pack?.available,
                        fetchFailed = false,
                        imageUrl = bead?.imageUrl ?: "",
                        hex = bead?.hex ?: "",
                        colorName = beadNames[item.beadCode to fallback.vendorKey],
                    ))
                }
            } else {
                val key = Triple(item.beadCode, item.vendorKey, item.packGrams)
                val pack = allFreshPacks[key]
                val bead = beadMap[item.beadCode]
                finalizedItems.add(FinalizedItem(
                    beadCode = item.beadCode,
                    vendorKey = item.vendorKey,
                    packGrams = item.packGrams,
                    quantityUnits = item.quantityUnits,
                    url = pack?.url ?: "",
                    priceCents = effectivePrice(pack),
                    available = pack?.available,
                    fetchFailed = pack?.id in checkResult.failedPackIds,
                    imageUrl = bead?.imageUrl ?: "",
                    hex = bead?.hex ?: "",
                    colorName = beadNames[item.beadCode to item.vendorKey],
                ))
            }
        }

        for (item in unassignedItems) {
            val parts = selectionMap[item.beadCode] ?: continue
            for ((selection, combo) in parts.flattenCombinations()) {
                val key = Triple(item.beadCode, selection.vendorKey, combo.packGrams)
                val pack = allFreshPacks[key]
                val bead = beadMap[item.beadCode]
                finalizedItems.add(FinalizedItem(
                    beadCode = item.beadCode,
                    vendorKey = selection.vendorKey,
                    packGrams = combo.packGrams,
                    quantityUnits = combo.quantity,
                    url = pack?.url ?: "",
                    priceCents = effectivePrice(pack),
                    available = pack?.available,
                    fetchFailed = false,
                    imageUrl = bead?.imageUrl ?: "",
                    hex = bead?.hex ?: "",
                    colorName = beadNames[item.beadCode to selection.vendorKey],
                ))
            }
        }

        val buyUpSuggestion: BuyUpSuggestion? = if (buyUpEnabled) {
            val allFmgPacks = catalogRepository.allPacksByVendor("fmg")
            analyzeBuyUp(
                fmgSelectionsByBead = tieredFmgSelectionsByBead,
                fmgPacksByBead = fmgPacksByBead,
                currentTotalFmgUnits = currentFmgTotalUnits,
                allAvailableFmgPacks = allFmgPacks,
            )
        } else null

        OrderAnalysis(
            orderId = orderId,
            items = finalizedItems,
            buyUpSuggestion = buyUpSuggestion,
            unassignedItems = unassignedItems,
            assignedItems = assignedItems,
            selectionMap = selectionMap,
            fallbackSelections = fallbackSelections,
            allFreshPacks = allFreshPacks,
            allOrderItems = order.items,
            beadMap = beadMap,
            checkResult = checkResult,
            currentFmgTotalUnits = currentFmgTotalUnits,
            fmgPacksByBead = fmgPacksByBead,
        )
    }

    /**
     * Phase 2: writes the finalized order to Firestore.
     *
     * [buyUpBeadCode] — when non-null, adds [analysis.buyUpSuggestion.unitsToAdd] units of
     * the smallest available FMG pack for that bead. If the chosen bead has no available FMG
     * packs, [InvalidBuyUpBeadException] is thrown and nothing is written to Firestore.
     *
     * When [buyUpBeadCode] shares a (beadCode, packGrams) triple with an existing pre-assigned
     * item in the order, the extra units are merged into that item rather than creating a
     * duplicate entry.
     *
     * Returns [FinalizeResult] with items priced at the final effective tier (accounting for
     * the buy-up units if applicable).
     */
    suspend fun commit(analysis: OrderAnalysis, buyUpBeadCode: String?): FinalizeResult = mutex.withLock {
        val resolvedAssignments = buildResolvedItems(
            unassignedItems = analysis.unassignedItems,
            assignedItems = analysis.assignedItems,
            selectionMap = analysis.selectionMap,
            fallbackSelections = analysis.fallbackSelections,
        ).toMutableList()

        // (beadCode, packGrams, extraUnits) — set when buy-up is merged into an existing row.
        var buyUpMerged: Triple<String, Double, Int>? = null
        var buyUpFinalizedItem: FinalizedItem? = null

        if (buyUpBeadCode != null && analysis.buyUpSuggestion != null) {
            val suggestion = analysis.buyUpSuggestion
            val fmgPacks = catalogRepository.packsForBead(buyUpBeadCode)
                .filter {
                    it.vendorKey == "fmg" && it.available != false && it.priceCents != null &&
                        (it.stockQuantity ?: Int.MAX_VALUE) >= suggestion.unitsToAdd
                }
                .sortedBy { it.grams }
            val buyUpPack = fmgPacks.firstOrNull()
                ?: throw InvalidBuyUpBeadException(buyUpBeadCode)

            val finalUnits = analysis.currentFmgTotalUnits + suggestion.unitsToAdd

            // Detect identity collision: does resolvedAssignments already contain an entry
            // with the same (beadCode, "fmg", packGrams) triple?
            val existingIdx = resolvedAssignments.indexOfFirst {
                it.beadCode == buyUpBeadCode && it.vendorKey == "fmg" && it.packGrams == buyUpPack.grams
            }

            if (existingIdx >= 0) {
                // Merge: fold buy-up units into the existing assignment to avoid duplicates.
                resolvedAssignments[existingIdx] = resolvedAssignments[existingIdx].copy(
                    quantityUnits = resolvedAssignments[existingIdx].quantityUnits + suggestion.unitsToAdd,
                )
                buyUpMerged = Triple(buyUpBeadCode, buyUpPack.grams, suggestion.unitsToAdd)
            } else {
                // New item: add a dedicated buy-up entry.
                resolvedAssignments.add(
                    OrderItemEntry(
                        beadCode = buyUpBeadCode,
                        vendorKey = "fmg",
                        targetGrams = 0.0,
                        packGrams = buyUpPack.grams,
                        quantityUnits = suggestion.unitsToAdd,
                        status = OrderItemStatus.PENDING.firestoreValue,
                        buyUp = true,
                    )
                )
                val bead = analysis.beadMap[buyUpBeadCode]
                    ?: catalogRepository.allBeadsAsMap()[buyUpBeadCode]
                val buyUpColorName = catalogRepository.vendorNamesForBeads(listOf(buyUpBeadCode))[buyUpBeadCode to "fmg"]
                buyUpFinalizedItem = FinalizedItem(
                    beadCode = buyUpBeadCode,
                    vendorKey = "fmg",
                    packGrams = buyUpPack.grams,
                    quantityUnits = suggestion.unitsToAdd,
                    url = buyUpPack.url,
                    priceCents = fmgEffectivePriceCents(buyUpPack, finalUnits),
                    available = buyUpPack.available,
                    fetchFailed = false,
                    imageUrl = bead?.imageUrl ?: "",
                    hex = bead?.hex ?: "",
                    colorName = buyUpColorName,
                    isBuyUp = true,
                )
            }
        }

        orderRepository.finalizeOrder(analysis.orderId, analysis.allOrderItems, resolvedAssignments)

        // Build the FinalizeResult, repricing FMG items and reflecting any merged/new buy-up.
        val finalItems = if ((buyUpFinalizedItem != null || buyUpMerged != null) && analysis.buyUpSuggestion != null) {
            val finalUnits = analysis.currentFmgTotalUnits + analysis.buyUpSuggestion.unitsToAdd
            var repricedItems = analysis.items.map { item ->
                if (item.vendorKey == "fmg") {
                    val pack = analysis.allFreshPacks[Triple(item.beadCode, "fmg", item.packGrams)]
                    if (pack != null) item.copy(priceCents = fmgEffectivePriceCents(pack, finalUnits))
                    else item
                } else item
            }
            // Merged case: update the existing FinalizedItem's quantityUnits too.
            if (buyUpMerged != null) {
                val (mergedCode, mergedPackGrams, extraUnits) = buyUpMerged
                repricedItems = repricedItems.map { item ->
                    if (item.beadCode == mergedCode && item.vendorKey == "fmg" && item.packGrams == mergedPackGrams) {
                        item.copy(quantityUnits = item.quantityUnits + extraUnits)
                    } else item
                }
            }
            if (buyUpFinalizedItem != null) repricedItems + buyUpFinalizedItem else repricedItems
        } else {
            analysis.items
        }

        FinalizeResult(items = finalItems)
    }

    /**
     * Records that [vendorKey] shows only [quantity] units of the [packGrams] pack of [beadCode],
     * then re-plans that bead against the updated stock.
     *
     * Throws [UnavailablePacksException] when the vendors together can no longer cover the bead.
     * The stock report is kept in that case so the user can act on it elsewhere.
     */
    suspend fun reportStockAndReplan(
        orderId: String,
        beadCode: String,
        vendorKey: String,
        packGrams: Double,
        quantity: Int,
    ) {
        catalogRepository.reportPackStock(
            beadCode, vendorKey, packGrams, quantity,
            nowEpochSeconds = System.currentTimeMillis() / 1000,
        )
        replanBead(orderId, beadCode)
    }

    /**
     * Re-selects vendors and packs for the replannable rows of [beadCode] using the stock
     * already in the catalog (no live check, so reported stock is not overwritten).
     *
     * Replannable rows are the bead's FINALIZED and ORDERED rows. RECEIVED, SKIPPED and
     * buy-up rows are untouched. A new row identical to an existing row in vendor, pack and
     * quantity keeps that row's status and invoice number; any other row is FINALIZED with
     * no invoice number, to be marked ordered again at its vendor.
     *
     * Throws [UnavailablePacksException] when no allocation covers the bead.
     */
    suspend fun replanBead(orderId: String, beadCode: String): Unit = mutex.withLock {
        val order = orderRepository.orderStream(orderId).first()
            ?: error("Order $orderId not found")
        val rows = order.items.filter { it.isReplannable(beadCode) }
        if (rows.isEmpty()) return@withLock

        val vendorPriority = preferencesRepository.vendorPriorityOrder.first()
        val packs = catalogRepository.packsForBead(beadCode)
        val allocation = selectAllocation(beadCode, rows.first().targetGrams, packs, vendorPriority)
            ?: throw UnavailablePacksException(listOf(beadCode))

        val template = rows.first()
        val replacements = allocation.flattenCombinations().map { (part, combo) ->
            val existing = rows.firstOrNull {
                it.vendorKey == part.vendorKey && it.packGrams == combo.packGrams &&
                    it.quantityUnits == combo.quantity
            }
            existing ?: template.copy(
                vendorKey = part.vendorKey,
                packGrams = combo.packGrams,
                quantityUnits = combo.quantity,
                status = OrderItemStatus.FINALIZED.firestoreValue,
                invoiceNumber = null,
            )
        }
        orderRepository.replaceItems(orderId, order.items, replaced = rows, replacements = replacements)
    }

    private fun OrderItemEntry.isReplannable(beadCode: String): Boolean {
        if (this.beadCode != beadCode || buyUp || vendorKey.isBlank()) return false
        val status = OrderItemStatus.fromFirestore(status)
        return status == OrderItemStatus.FINALIZED || status == OrderItemStatus.ORDERED
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
        val firestoreFinalizedItems = order.items
            .filter { OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.FINALIZED }
        // Count FMG units across all finalized items so tier pricing reflects the actual order.
        val totalFmgUnits = firestoreFinalizedItems
            .filter { it.vendorKey == "fmg" }
            .sumOf { it.quantityUnits }
        val beadNames = catalogRepository.vendorNamesForBeads(
            firestoreFinalizedItems.map { it.beadCode }.distinct()
        )
        val finalizedItems = firestoreFinalizedItems.map { item ->
            val pack = catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
            val bead = beadMap[item.beadCode]
            val displayPrice = if (item.vendorKey == "fmg" && pack != null) {
                fmgEffectivePriceCents(pack, totalFmgUnits)
            } else {
                pack?.priceCents
            }
            FinalizedItem(
                beadCode = item.beadCode,
                vendorKey = item.vendorKey,
                packGrams = item.packGrams,
                quantityUnits = item.quantityUnits,
                url = pack?.url ?: "",
                priceCents = displayPrice,
                available = pack?.available,
                fetchFailed = false,
                imageUrl = bead?.imageUrl ?: "",
                hex = bead?.hex ?: "",
                colorName = beadNames[item.beadCode to item.vendorKey],
                isBuyUp = item.buyUp,
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
        selectionMap: Map<String, List<VendorSelection>>,
        fallbackSelections: Map<String, List<VendorSelection>>,
    ): List<OrderItemEntry> {
        val result = mutableListOf<OrderItemEntry>()

        for (item in unassignedItems) {
            val parts = selectionMap[item.beadCode] ?: continue
            for ((selection, combo) in parts.flattenCombinations()) {
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
            val fallbackParts = fallbackSelections[item.beadCode]
            if (fallbackParts != null) {
                for ((fallback, combo) in fallbackParts.flattenCombinations()) {
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

    /**
     * Returns the bead codes of any owned inventory items that are at or below the configured
     * low-stock threshold. An empty list means everything is adequately stocked.
     *
     * Uses the per-bead override when set (> 0); otherwise falls back to the global threshold.
     * Ignores floating-point noise below [SUFFICIENT_THRESHOLD_GRAMS].
     */
    suspend fun checkLowStock(): List<String> {
        val inventoryMap = inventoryRepository.inventoryStream().first()
        val globalThreshold = preferencesRepository.globalLowStockThreshold.first()
        return inventoryMap.values
            .filter { entry ->
                val threshold = effectiveThresholdFor(entry, globalThreshold)
                entry.quantityGrams > SUFFICIENT_THRESHOLD_GRAMS && entry.quantityGrams <= threshold
            }
            .map { it.beadCode }
            .sorted()
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
