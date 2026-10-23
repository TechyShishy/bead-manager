package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.VendorPackEntity
import kotlin.math.roundToInt

/**
 * Represents one pack size and how many units of it to order.
 */
data class PackCombination(val packGrams: Double, val quantity: Int)

/**
 * Result of selecting the cheapest vendor for one bead.
 *
 * @param vendorKey  The vendor that wins.
 * @param combination The pack combination to purchase.
 * @param totalCents  Total cost for the combination in cents.
 */
data class VendorSelection(
    val vendorKey: String,
    val combination: List<PackCombination>,
    val totalCents: Int,
)

/**
 * Chooses the cheapest vendor and pack combination for [targetGrams] of [beadCode].
 *
 * Only packs with a non-null [VendorPackEntity.priceCents] are eligible. Within each vendor,
 * the combination is the cheapest one that covers the target ([computeCheapestCombination]).
 * Across vendors, the vendor whose combination costs the least total wins.
 *
 * Returns null when no vendor has a complete pack catalog with prices for [beadCode].
 */
fun selectCheapestVendor(
    beadCode: String,
    targetGrams: Double,
    allPacks: List<VendorPackEntity>,
): VendorSelection? {
    val relevantPacks = allPacks.filter { it.beadCode == beadCode && it.priceCents != null }
    if (relevantPacks.isEmpty()) return null

    val byVendor = relevantPacks.groupBy { it.vendorKey }

    var best: VendorSelection? = null

    for ((vendorKey, packs) in byVendor) {
        val combination = computeCheapestCombination(packs, targetGrams)
        if (combination.isEmpty()) continue

        // Map each pack in the combination back to its entity to get the price.
        val totalCents = combination.sumOf { combo ->
            val pack = packs.first { (it.grams * 10).roundToInt() == (combo.packGrams * 10).roundToInt() }
            (pack.priceCents ?: 0) * combo.quantity
        }

        if (best == null || totalCents < best.totalCents) {
            best = VendorSelection(
                vendorKey = vendorKey,
                combination = combination,
                totalCents = totalCents,
            )
        }
    }

    return best
}

/**
 * Returns the cheapest combination of [packs] that reaches [targetGrams]. Among equally
 * cheap combinations, the one with the least total grams (least waste) wins, then the
 * one with the fewest units.
 *
 * Cost is the sum of [VendorPackEntity.priceCents] — the tier-1 unit price. FMG
 * quantity-break repricing depends on the whole order's unit count, so it is applied
 * afterward by [applyFmgTier] and [analyzeBuyUp] rather than folded into this per-bead choice.
 *
 * A pack with a non-null [VendorPackEntity.stockQuantity] contributes at most that many
 * units; a null quantity is unlimited. Packs with a zero quantity are ignored.
 *
 * Uses a grouped knapsack DP scaled to integers (×10) so that half-gram pack sizes
 * (e.g. 7.5g) become clean integers (75). All known Delica pack sizes are multiples
 * of 0.5g, so the scale factor of 10 is always exact. Totals up to the target plus the
 * largest pack are searched: any combination beyond that contains a pack whose removal
 * still reaches the target at lower cost, and removal never breaks a stock cap.
 *
 * Packs without a price are ignored. Returns an empty list if no priced pack remains,
 * [targetGrams] ≤ 0, the target exceeds the practical ceiling (10 kg), or the stock caps
 * make the target unreachable.
 */
fun computeCheapestCombination(
    packs: List<VendorPackEntity>,
    targetGrams: Double,
): List<PackCombination> {
    if (targetGrams <= 0.0 || targetGrams > 10_000.0) return emptyList()

    val scale = 10
    // One entry per pack size: the cheapest priced, in-stock SKU of that size.
    val sizes: List<PackSize> = packs
        .filter { (it.stockQuantity ?: 1) > 0 }
        .mapNotNull { pack ->
            val price = pack.priceCents ?: return@mapNotNull null
            val scaled = (pack.grams * scale).roundToInt()
            if (scaled > 0) PackSize(scaled, price, pack.stockQuantity, pack.grams) else null
        }
        .groupBy { it.scaledGrams }
        .map { (_, group) -> group.minBy { it.priceCents } }
    if (sizes.isEmpty()) return emptyList()

    val targetInt = (targetGrams * scale).roundToInt()
    val ceiling = targetInt + sizes.maxOf { it.scaledGrams }

    // cost[i] / units[i]: cheapest (then fewest-unit) way to reach exactly i scaled grams
    // using the sizes processed so far. choice[k][i]: units of size k in that best way.
    val inf = Long.MAX_VALUE / 2
    var cost = LongArray(ceiling + 1) { inf }.also { it[0] = 0 }
    var units = IntArray(ceiling + 1)
    val choice = Array(sizes.size) { IntArray(ceiling + 1) }

    for ((k, size) in sizes.withIndex()) {
        val nextCost = cost.copyOf()
        val nextUnits = units.copyOf()
        val cap = size.stockQuantity
        for (i in 1..ceiling) {
            if (cap == null) {
                // Unbounded: nextCost[i - pack] already includes this size, so ascending
                // order lets one size repeat arbitrarily often.
                val prev = i - size.scaledGrams
                if (prev >= 0 && nextCost[prev] < inf) {
                    val candidateCost = nextCost[prev] + size.priceCents
                    val candidateUnits = nextUnits[prev] + 1
                    if (candidateCost < nextCost[i] ||
                        (candidateCost == nextCost[i] && candidateUnits < nextUnits[i])
                    ) {
                        nextCost[i] = candidateCost
                        nextUnits[i] = candidateUnits
                        choice[k][i] = choice[k][prev] + 1
                    }
                }
            } else {
                for (j in 1..minOf(cap, i / size.scaledGrams)) {
                    val prev = i - j * size.scaledGrams
                    if (cost[prev] >= inf) continue
                    val candidateCost = cost[prev] + j.toLong() * size.priceCents
                    val candidateUnits = units[prev] + j
                    if (candidateCost < nextCost[i] ||
                        (candidateCost == nextCost[i] && candidateUnits < nextUnits[i])
                    ) {
                        nextCost[i] = candidateCost
                        nextUnits[i] = candidateUnits
                        choice[k][i] = j
                    }
                }
            }
        }
        cost = nextCost
        units = nextUnits
    }

    // Ascending scan with a strict improvement test keeps the smallest total on cost ties.
    var best = -1
    for (total in targetInt..ceiling) {
        if (cost[total] < inf && (best == -1 || cost[total] < cost[best])) best = total
    }
    if (best == -1) return emptyList()

    val result = mutableListOf<PackCombination>()
    var remaining = best
    for (k in sizes.indices.reversed()) {
        val count = choice[k][remaining]
        if (count > 0) result.add(PackCombination(sizes[k].packGrams, count))
        remaining -= count * sizes[k].scaledGrams
    }
    return result.sortedByDescending { it.packGrams }
}

private data class PackSize(
    val scaledGrams: Int,
    val priceCents: Int,
    val stockQuantity: Int?,
    val packGrams: Double,
)

// ---------------------------------------------------------------------------
// FMG tier pricing
// ---------------------------------------------------------------------------

/**
 * FMG quantity-break tier thresholds (minimum units to reach each tier).
 * These boundaries are uniform across every FMG SKU in the catalog.
 */
private val FMG_TIER_THRESHOLDS = listOf(15, 50, 100)

/**
 * Returns the effective unit price for [pack] given [totalFmgUnits] across the order.
 * Falls back to [VendorPackEntity.priceCents] if the tier-specific column is null
 * (e.g. the pack was seeded before tier data was available).
 */
fun fmgEffectivePriceCents(pack: VendorPackEntity, totalFmgUnits: Int): Int? = when {
    totalFmgUnits >= 100 -> pack.tier4PriceCents ?: pack.priceCents
    totalFmgUnits >= 50  -> pack.tier3PriceCents ?: pack.priceCents
    totalFmgUnits >= 15  -> pack.tier2PriceCents ?: pack.priceCents
    else                 -> pack.priceCents
}

/**
 * Re-computes [VendorSelection.totalCents] using the tier price appropriate for
 * [totalFmgUnits]. [packsByGrams] must be the FMG packs for the same bead scaled
 * identically to the selection's combination.
 */
fun applyFmgTier(
    selection: VendorSelection,
    packsByGrams: List<VendorPackEntity>,
    totalFmgUnits: Int,
): VendorSelection {
    val scale = 10
    val totalCents = selection.combination.sumOf { combo ->
        val pack = packsByGrams.first {
            (it.grams * scale).roundToInt() == (combo.packGrams * scale).roundToInt()
        }
        (fmgEffectivePriceCents(pack, totalFmgUnits) ?: 0) * combo.quantity
    }
    return selection.copy(totalCents = totalCents)
}

// ---------------------------------------------------------------------------
// Vendor preference + buy-up
// ---------------------------------------------------------------------------

/**
 * Selects the vendor for [beadCode] by walking [vendorPriorityOrder] and returning the
 * first vendor that has an available, priced pack combination for the target quantity.
 *
 * Unlike [selectCheapestVendor], this does not compare costs across vendors — the
 * priority order is the sole ranking. A vendor is skipped only if it has no available
 * packs with prices for [beadCode].
 *
 * Returns null when no vendor in the priority list can supply the bead.
 */
fun selectPreferredVendor(
    beadCode: String,
    targetGrams: Double,
    allPacks: List<VendorPackEntity>,
    vendorPriorityOrder: List<String>,
): VendorSelection? {
    val relevantPacks = allPacks.filter { it.beadCode == beadCode && it.priceCents != null }
    if (relevantPacks.isEmpty()) return null

    val byVendor = relevantPacks.groupBy { it.vendorKey }

    for (vendorKey in vendorPriorityOrder) {
        val packs = byVendor[vendorKey] ?: continue
        val available = packs.filter { it.available != false }
        if (available.isEmpty()) continue
        val combination = computeCheapestCombination(available, targetGrams)
        if (combination.isEmpty()) continue
        val scale = 10
        val totalCents = combination.sumOf { combo ->
            val pack = available.first {
                (it.grams * scale).roundToInt() == (combo.packGrams * scale).roundToInt()
            }
            (pack.priceCents ?: 0) * combo.quantity
        }
        return VendorSelection(vendorKey, combination, totalCents)
    }
    return null
}

/**
 * Information about one buy-up suggestion: which bead and pack to add to push
 * the FMG order over the next quantity-break tier, along with the net savings.
 *
 * [savingsCents] is the net reduction: (discount on existing items) − (cost of buy-up packs).
 * A positive value means the buy-up is worth accepting.
 */
data class BuyUpSuggestion(
    val unitsToAdd: Int,
    val nextTierMinQty: Int,
    val savingsCents: Int,
    val suggestedBeadCode: String,
    val suggestedPackGrams: Double,
    val suggestedUnitCents: Int,
)

/**
 * Evaluates whether adding [unitsToAdd] FMG packs to this order would reduce its total cost
 * by crossing the next quantity-break tier threshold.
 *
 * [fmgSelectionsByBead] — current FMG vendor selections keyed by bead code.
 * [fmgPacksByBead] — FMG pack entities for each bead in the selection (for tier price lookup).
 * [allAvailableFmgPacks] — all available, priced FMG packs in the catalog (for suggestion).
 * [currentTotalFmgUnits] — total pack units currently selected from FMG across the order.
 *
 * Returns null if already at the top tier, or if the buy-up would not reduce total cost.
 */
fun analyzeBuyUp(
    fmgSelectionsByBead: Map<String, VendorSelection>,
    fmgPacksByBead: Map<String, List<VendorPackEntity>>,
    currentTotalFmgUnits: Int,
    allAvailableFmgPacks: List<VendorPackEntity>,
): BuyUpSuggestion? {
    val nextThreshold = FMG_TIER_THRESHOLDS.firstOrNull { it > currentTotalFmgUnits }
        ?: return null  // already at top tier (100+)

    val unitsToAdd = nextThreshold - currentTotalFmgUnits
    val scale = 10

    // Cost of existing selections at the current tier.
    val currentCost = fmgSelectionsByBead.values.sumOf { it.totalCents }

    // Cost of existing selections repriced at the next tier.
    val nextTierCost = fmgSelectionsByBead.entries.sumOf { (beadCode, selection) ->
        val beadPacks = fmgPacksByBead[beadCode] ?: return@sumOf selection.totalCents
        selection.combination.sumOf { combo ->
            val pack = beadPacks.firstOrNull {
                (it.grams * scale).roundToInt() == (combo.packGrams * scale).roundToInt()
            } ?: return@sumOf selection.totalCents
            (fmgEffectivePriceCents(pack, nextThreshold) ?: 0) * combo.quantity
        }
    }

    val savings = currentCost - nextTierCost

    // Cheapest available FMG pack (by next-tier unit price) for the suggested buy-up item.
    val cheapestPack = allAvailableFmgPacks
        .filter {
            it.available != false && it.priceCents != null &&
                (it.stockQuantity ?: Int.MAX_VALUE) >= unitsToAdd
        }
        .minByOrNull { fmgEffectivePriceCents(it, nextThreshold) ?: Int.MAX_VALUE }
        ?: return null

    val buyUpUnitCents = fmgEffectivePriceCents(cheapestPack, nextThreshold) ?: return null
    val buyUpCost = unitsToAdd * buyUpUnitCents

    if (savings <= buyUpCost) return null  // doesn't save money

    return BuyUpSuggestion(
        unitsToAdd = unitsToAdd,
        nextTierMinQty = nextThreshold,
        savingsCents = savings - buyUpCost,
        suggestedBeadCode = cheapestPack.beadCode,
        suggestedPackGrams = cheapestPack.grams,
        suggestedUnitCents = buyUpUnitCents,
    )
}
