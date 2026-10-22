package com.techyshishy.beadmanager.data.pdf

/**
 * Outcome of [ColorKeyReconciler.reconcile]. [corrections] maps each relabeled OCR key to
 * the pattern letter it was resolved to; empty when nothing was changed.
 */
data class ColorKeyReconciliation(
    val colorMap: Map<String, String>,
    val corrections: Map<String, String>,
)

/**
 * Repairs OCR-garbled color key labels by resolving them against the letters the pattern
 * actually uses.
 *
 * OCR can misread a chart letter (e.g. `I` read as `1I`) so the entry lands under a label
 * the pattern never uses while a letter the pattern does use has no entry. Such a *stray*
 * label is relabeled to a *missing* letter only when independent evidence picks exactly one
 * candidate. Two kinds of evidence are applied; every one that can run must agree, and at
 * least one must run:
 *
 * - **Neighbor order**: the color key lists labels in ascending order within each column,
 *   so a stray's nearest correctly-read entries before and after it bound which letters
 *   it can be. Abstains when either neighbor is absent or the two are out of order (a
 *   column boundary lies between them).
 * - **Counts**: the key prints a bead count per color, and the pattern's graph yields the
 *   same count per letter. A candidate is supported when its graph count is among the
 *   OCR-read counts not already accounted for by correctly-read letters. Abstains when no
 *   counts were read.
 *
 * Anything ambiguous is left uncorrected, so the caller's missing-letter check still fails
 * rather than importing a guessed mapping.
 */
object ColorKeyReconciler {

    /**
     * @param ocrColorMap OCR letter-to-DB-code map in the order the entries were read.
     * @param ocrCounts every bead count read from the color key page, in any order.
     * @param usedLetters letters used by the pattern.
     * @param variantLetterCounts per variant, the number of beads of each letter in its graph.
     */
    fun reconcile(
        ocrColorMap: Map<String, String>,
        ocrCounts: List<Int>,
        usedLetters: Set<String>,
        variantLetterCounts: List<Map<String, Int>>,
    ): ColorKeyReconciliation {
        val unchanged = ColorKeyReconciliation(ocrColorMap, emptyMap())
        val missing = usedLetters - ocrColorMap.keys
        val strays = ocrColorMap.keys.filter { it !in usedLetters }
        if (missing.isEmpty() || strays.isEmpty()) return unchanged

        val ordered = ocrColorMap.keys.toList()
        val matched = ocrColorMap.keys.filter { it in usedLetters }
        val chosen = mutableMapOf<String, String>()
        for (stray in strays) {
            val filters = listOfNotNull(
                neighborFilter(stray, ordered, usedLetters),
                countFilter(ocrCounts, matched, variantLetterCounts),
            )
            if (filters.isEmpty()) continue
            val survivors = missing.filter { candidate -> filters.all { it(candidate) } }
            if (survivors.size == 1) chosen[stray] = survivors.single()
        }

        // Two strays claiming one letter means the evidence did not separate them.
        val claimed = chosen.values.groupingBy { it }.eachCount()
        val corrections = chosen.filterValues { claimed.getValue(it) == 1 }
        if (corrections.isEmpty()) return unchanged

        val corrected = LinkedHashMap<String, String>()
        for ((letter, dbCode) in ocrColorMap) corrected[corrections[letter] ?: letter] = dbCode
        return ColorKeyReconciliation(corrected, corrections)
    }

    private fun neighborFilter(
        stray: String,
        ordered: List<String>,
        usedLetters: Set<String>,
    ): ((String) -> Boolean)? {
        val index = ordered.indexOf(stray)
        val before = ordered.subList(0, index).lastOrNull { it in usedLetters } ?: return null
        val after = ordered.subList(index + 1, ordered.size).firstOrNull { it in usedLetters }
            ?: return null
        if (LABEL_ORDER.compare(before, after) >= 0) return null
        return { candidate ->
            LABEL_ORDER.compare(before, candidate) < 0 && LABEL_ORDER.compare(candidate, after) < 0
        }
    }

    private fun countFilter(
        ocrCounts: List<Int>,
        matched: List<String>,
        variantLetterCounts: List<Map<String, Int>>,
    ): ((String) -> Boolean)? {
        if (ocrCounts.isEmpty() || variantLetterCounts.isEmpty()) return null
        val unaccounted = variantLetterCounts.map { tally ->
            val bag = ocrCounts.groupingBy { it }.eachCount().toMutableMap()
            for (letter in matched) {
                val count = tally[letter] ?: continue
                bag.computeIfPresent(count) { _, n -> n - 1 }
            }
            tally to bag
        }
        return { candidate ->
            unaccounted.any { (tally, bag) ->
                val count = tally[candidate]
                count != null && (bag[count] ?: 0) > 0
            }
        }
    }

    /** Spreadsheet-column order: shorter labels first, then alphabetical (A < Z < AA < AB). */
    private val LABEL_ORDER = compareBy<String>({ it.length }, { it })
}
