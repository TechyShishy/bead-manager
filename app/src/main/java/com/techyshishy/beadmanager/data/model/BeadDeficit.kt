package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import kotlin.math.max

/**
 * Inventory quantities below this many grams are treated as zero-deficit.
 * Keeps floating-point noise from keeping a bead in the "needs ordering" state.
 */
internal const val SUFFICIENT_THRESHOLD_GRAMS = 0.001

/** Resolves the effective minimum-stock threshold for a bead. */
fun effectiveThresholdFor(entry: InventoryEntry?, globalThreshold: Double): Double =
    entry?.lowStockThresholdGrams?.takeIf { it > 0.0 } ?: globalThreshold

/**
 * Effective deficit including the minimum-stock replenishment amount.
 *
 * Returns max(0, targetGrams + effectiveThreshold − inventoryGrams), floored to 0 when
 * the result is below [SUFFICIENT_THRESHOLD_GRAMS] to suppress floating-point noise.
 */
fun effectiveDeficitFor(
    bead: ProjectBeadEntry,
    entry: InventoryEntry?,
    globalThreshold: Double,
): Double {
    val inStock = entry?.quantityGrams ?: 0.0
    val threshold = effectiveThresholdFor(entry, globalThreshold)
    val raw = max(0.0, bead.targetGrams + threshold - inStock)
    return if (raw < SUFFICIENT_THRESHOLD_GRAMS) 0.0 else raw
}

/**
 * Computes a project-level satisfaction status from its bead list.
 *
 * Returns:
 * - `null`  — no DB beads (no indicator shown)
 * - `0`     — every bead's effective deficit is zero (project is ready)
 * - `N > 0` — N beads have a positive effective deficit
 *
 * [beads] is the list of [ProjectBeadEntry] for the project (may include 0-g entries for
 * beads registered only in colorMapping). [inventory] is the full inventory map keyed by
 * bead code. [globalThreshold] is the app-wide low-stock threshold.
 */
fun computeProjectSatisfaction(
    beads: List<ProjectBeadEntry>,
    inventory: Map<String, InventoryEntry>,
    globalThreshold: Double,
): Int? {
    val delicaBeads = beads.filter { it.beadCode.startsWith("DB") }
    if (delicaBeads.isEmpty()) return null
    return delicaBeads.count { bead ->
        effectiveDeficitFor(bead, inventory[bead.beadCode], globalThreshold) > 0.0
    }
}
