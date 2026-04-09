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
 * the combination is computed via unbounded-knapsack DP (same algorithm used in the add-item
 * UI). Across vendors, the vendor whose combination costs the least total wins.
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
        val combination = computeOptimalCombination(packs.map { it.grams }, targetGrams)
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
 * Returns the combination of packs from [availablePacks] that reaches [targetGrams] with
 * minimum waste, and among equal-waste solutions, the fewest total units.
 *
 * Uses unbounded knapsack DP scaled to integers (×10) so that half-gram pack sizes
 * (e.g. 7.5g) become clean integers (75). All known Delica pack sizes are multiples
 * of 0.5g, so the scale factor of 10 is always exact.
 *
 * Returns an empty list if [availablePacks] is empty, [targetGrams] ≤ 0, or the target
 * exceeds the practical ceiling (10 kg).
 */
fun computeOptimalCombination(
    availablePacks: List<Double>,
    targetGrams: Double,
): List<PackCombination> {
    if (availablePacks.isEmpty() || targetGrams <= 0.0 || targetGrams > 10_000.0) return emptyList()

    val scale = 10
    val targetInt = (targetGrams * scale).roundToInt()
    val packsInt = availablePacks
        .map { (it * scale).roundToInt() }
        .filter { it > 0 }
        .distinct()
    if (packsInt.isEmpty()) return emptyList()

    val maxPack = packsInt.max()
    val ceiling = targetInt + maxPack

    val INF = Int.MAX_VALUE / 2
    val dp = IntArray(ceiling + 1) { INF }
    val parent = IntArray(ceiling + 1) { -1 }
    dp[0] = 0

    for (i in 1..ceiling) {
        for (pack in packsInt) {
            if (pack <= i && dp[i - pack] < INF) {
                val candidate = dp[i - pack] + 1
                if (candidate < dp[i]) {
                    dp[i] = candidate
                    parent[i] = pack
                }
            }
        }
    }

    val reachable = (targetInt..ceiling).firstOrNull { dp[it] < INF } ?: return emptyList()

    val counts = mutableMapOf<Int, Int>()
    var remaining = reachable
    while (remaining > 0) {
        val p = parent[remaining]
        counts[p] = (counts[p] ?: 0) + 1
        remaining -= p
    }

    return counts
        .map { (scaledGrams, qty) ->
            PackCombination(
                packGrams = availablePacks.first { (it * scale).roundToInt() == scaledGrams },
                quantity = qty,
            )
        }
        .sortedByDescending { it.packGrams }
}
