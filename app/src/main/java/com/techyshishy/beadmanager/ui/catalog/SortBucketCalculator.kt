package com.techyshishy.beadmanager.ui.catalog

import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.model.BEADS_PER_GRAM

private val DB_NUMBER_STEPS = listOf(10, 25, 50, 100, 250, 500, 1000)

/**
 * Partitions [beads] (already sorted by the ViewModel) into labeled navigation buckets.
 * The returned list is in display order — ascending or descending — matching the list.
 * Returns an empty list when there are zero items.
 * The caller is responsible for suppressing the nav bar when the result has ≤ 1 entries.
 */
fun computeSortBuckets(
    beads: List<BeadWithInventory>,
    sortBy: SortBy,
    ascending: Boolean,
): List<SortBucket> {
    if (beads.isEmpty()) return emptyList()
    return when (sortBy) {
        SortBy.DB_NUMBER -> computeDbNumberBuckets(beads, ascending)
        SortBy.COLOR_GROUP -> computeCategoricalBuckets(beads) { item ->
            item.catalogEntry.bead.colorGroup.firstOrNull() ?: ""
        }
        SortBy.GLASS_GROUP -> computeCategoricalBuckets(beads) { it.catalogEntry.bead.glassGroup }
        SortBy.DYED -> computeCategoricalBuckets(beads) { it.catalogEntry.bead.dyed }
        SortBy.GALVANIZED -> computeCategoricalBuckets(beads) { it.catalogEntry.bead.galvanized }
        SortBy.PLATING -> computeCategoricalBuckets(beads) { it.catalogEntry.bead.plating }
        SortBy.COUNT_GRAMS -> computeCountBuckets(beads, ascending)
        SortBy.COUNT_BEADS -> computeCountBeadsBuckets(beads, ascending)
    }
}

private fun computeDbNumberBuckets(beads: List<BeadWithInventory>, ascending: Boolean): List<SortBucket> {
    val numericValues = beads.map { it.code.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val min = numericValues.min()
    val max = numericValues.max()
    val span = max - min + 1

    // Smallest step producing ≤ 20 non-empty buckets across the numeric span.
    val step = DB_NUMBER_STEPS.firstOrNull { s -> (span + s - 1) / s <= 20 }
        ?: DB_NUMBER_STEPS.last()

    // Record the first list index at which each bucket is encountered.
    val bucketFirstIndex = mutableMapOf<Int, Int>()
    beads.forEachIndexed { listIndex, item ->
        val num = item.code.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
        // (num - 1) / step maps 1..step → bucket 0, (step+1)..(2*step) → bucket 1, etc.
        val bucketIdx = (num - 1).coerceAtLeast(0) / step
        if (bucketIdx !in bucketFirstIndex) {
            bucketFirstIndex[bucketIdx] = listIndex
        }
    }

    val sortedBuckets = bucketFirstIndex.entries
        .sortedBy { it.key }
        .map { (bucketIdx, startIndex) ->
            val bucketStart = bucketIdx * step + 1
            val bucketEnd = bucketStart + step - 1
            SortBucket(
                label = "${bucketStart.toString().padStart(4, '0')} – ${bucketEnd.toString().padStart(4, '0')}",
                startIndex = startIndex,
            )
        }

    return if (ascending) sortedBuckets else sortedBuckets.reversed()
}

// For categorical sorts the ViewModel has already applied the correct sort direction, so
// walking the list in encounter order gives buckets in display order without any reversal.
private fun computeCategoricalBuckets(
    beads: List<BeadWithInventory>,
    keySelector: (BeadWithInventory) -> String,
): List<SortBucket> {
    val seen = linkedMapOf<String, Int>() // key → first list index; preserves insertion order
    beads.forEachIndexed { index, item ->
        val key = keySelector(item)
        if (key !in seen) seen[key] = index
    }
    return seen.entries.map { (key, startIndex) -> SortBucket(label = key, startIndex = startIndex) }
}

private fun computeCountBuckets(beads: List<BeadWithInventory>, ascending: Boolean): List<SortBucket> {
    val quantities = beads.map { it.inventory?.quantityGrams ?: 0.0 }
    return if (ascending) {
        // Ascending: zeros come first, then non-zero increasing.
        val zeroEnd = quantities.indexOfFirst { it > 0.0 }.takeIf { it >= 0 } ?: beads.size
        buildList {
            if (zeroEnd > 0) add(SortBucket(label = "Unowned", startIndex = 0))
            addAll(quantileBuckets(quantities.subList(zeroEnd, beads.size), startOffset = zeroEnd))
        }
    } else {
        // Descending: non-zero items come first (highest qty first), zeros at the end.
        val zeroStart = quantities.indexOfFirst { it <= 0.0 }.takeIf { it >= 0 } ?: beads.size
        buildList {
            addAll(quantileBuckets(quantities.subList(0, zeroStart), startOffset = 0))
            if (zeroStart < beads.size) add(SortBucket(label = "Unowned", startIndex = zeroStart))
        }
    }
}

/**
 * Splits [quantities] (in the order they appear in the list) into up to 10 equal-count
 * quantile groups. Each bucket is labeled with its qty range.
 * [startOffset] is the position of quantities[0] within the full beads list.
 */
private fun quantileBuckets(quantities: List<Double>, startOffset: Int): List<SortBucket> {
    if (quantities.isEmpty()) return emptyList()
    val n = quantities.size
    val targetBuckets = minOf(10, n)
    return (0 until targetBuckets).map { i ->
        val startIdx = i * n / targetBuckets
        val endIdx = (i + 1) * n / targetBuckets - 1
        val qA = quantities[startIdx]
        val qB = quantities[endIdx]
        val qMin = minOf(qA, qB)
        val qMax = maxOf(qA, qB)
        val label = if (qMin == qMax) "${"%.1f".format(qMin)}g"
                    else "${"%.1f".format(qMin)}g – ${"%.1f".format(qMax)}g"
        SortBucket(label = label, startIndex = startOffset + startIdx)
    }
}

private fun computeCountBeadsBuckets(beads: List<BeadWithInventory>, ascending: Boolean): List<SortBucket> {
    val quantities = beads.map { (it.inventory?.quantityGrams ?: 0.0) * BEADS_PER_GRAM }
    return if (ascending) {
        val zeroEnd = quantities.indexOfFirst { it > 0.0 }.takeIf { it >= 0 } ?: beads.size
        buildList {
            if (zeroEnd > 0) add(SortBucket(label = "Unowned", startIndex = 0))
            addAll(quantileBucketsBeads(quantities.subList(zeroEnd, beads.size), startOffset = zeroEnd))
        }
    } else {
        val zeroStart = quantities.indexOfFirst { it <= 0.0 }.takeIf { it >= 0 } ?: beads.size
        buildList {
            addAll(quantileBucketsBeads(quantities.subList(0, zeroStart), startOffset = 0))
            if (zeroStart < beads.size) add(SortBucket(label = "Unowned", startIndex = zeroStart))
        }
    }
}

private fun quantileBucketsBeads(quantities: List<Double>, startOffset: Int): List<SortBucket> {
    if (quantities.isEmpty()) return emptyList()
    val n = quantities.size
    val targetBuckets = minOf(10, n)
    return (0 until targetBuckets).map { i ->
        val startIdx = i * n / targetBuckets
        val endIdx = (i + 1) * n / targetBuckets - 1
        val qA = quantities[startIdx]
        val qB = quantities[endIdx]
        val qMin = minOf(qA, qB).toLong()
        val qMax = maxOf(qA, qB).toLong()
        val label = if (qMin == qMax) "$qMin beads" else "$qMin – $qMax beads"
        SortBucket(label = label, startIndex = startOffset + startIdx)
    }
}
