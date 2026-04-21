package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Converts a 1-based index to a bijective base-26 string using the letters A–Z.
 *
 * The mapping is:
 *   1 → "A", 2 → "B", …, 26 → "Z", 27 → "AA", 28 → "AB", …, 52 → "AZ", 53 → "BA", …
 *
 * This is the standard bijective numeral system with digits A–Z (value 1–26). Unlike
 * zero-indexed base-26, there is no empty-string representation ("A" is 1, not 0).
 */
fun bijectiveKey(n: Int): String {
    require(n >= 1) { "bijectiveKey index must be >= 1, got $n" }
    val sb = StringBuilder()
    var remaining = n
    while (remaining > 0) {
        remaining--
        sb.append(('A' + (remaining % 26)))
        remaining /= 26
    }
    return sb.reverse().toString()
}

/**
 * Returns the next bijective key (A, B, …, Z, AA, AB, …) that is not already a key in
 * [existingColorMapping]. Iterates indices starting at 1 until a free slot is found.
 *
 * This is O(n) in the size of [existingColorMapping], which is always small in practice
 * (at most a few hundred palette entries per project).
 */
fun nextBijectiveKey(existingColorMapping: Map<String, String>): String {
    var index = 1
    while (true) {
        val candidate = bijectiveKey(index)
        if (candidate !in existingColorMapping) return candidate
        index++
    }
}

/**
 * Synthesizes a single-row RGP grid from a flat list of (beadCode, targetGrams) pairs.
 *
 * Each bead code is assigned a bijective palette key (A, B, …) and gets one step whose
 * count is [targetGrams] × [BEADS_PER_GRAM] rounded to the nearest integer. The
 * resulting grid and color mapping round-trip through [computeBeadRequirements] with
 * a maximum error of ±( 0.5 / BEADS_PER_GRAM ) grams per bead, which is < 0.003g.
 *
 * Returns a pair of (rows, colorMapping). Returns empty lists for an empty input.
 */
fun synthesizeFlatListGrid(
    beads: List<Pair<String, Double>>,
): Pair<List<ProjectRgpRow>, Map<String, String>> {
    if (beads.isEmpty()) return emptyList<ProjectRgpRow>() to emptyMap()
    val colorMapping = mutableMapOf<String, String>()
    val steps = beads.mapIndexed { i, (beadCode, targetGrams) ->
        val key = bijectiveKey(i + 1)
        colorMapping[key] = beadCode
        val count = (targetGrams * BEADS_PER_GRAM).roundToInt().coerceAtLeast(0)
        ProjectRgpStep(id = i + 1, count = count, description = key)
    }
    return listOf(ProjectRgpRow(id = 1, steps = steps)) to colorMapping
}

/**
 * Derives bead requirements from an RGP grid.
 *
 * Sums step counts per palette key across all rows, maps each key to its DB catalog code via
 * [colorMapping], and converts the raw count to grams using [BEADS_PER_GRAM]. Palette keys
 * that map to a hex color string (i.e. values starting with "#") are skipped — they have no
 * catalog identity and cannot be ordered.
 *
 * When multiple palette keys map to the same DB code, their counts are summed before the
 * grams conversion, avoiding IEEE 754 noise from repeated fractional division.
 *
 * The result is rounded to 2 decimal places, matching the precision used for bead quantities throughout the app.
 *
 * Returns an empty map for an empty [rows] list or a [colorMapping] with no DB-prefixed values.
 */
fun computeBeadRequirements(
    rows: List<ProjectRgpRow>,
    colorMapping: Map<String, String>,
): Map<String, Double> {
    if (rows.isEmpty() || colorMapping.isEmpty()) return emptyMap()

    // Index only the DB-code entries; hex entries are not actionable.
    val dbCodeByKey = colorMapping.filterValues { it.startsWith("DB") }
    if (dbCodeByKey.isEmpty()) return emptyMap()

    // Accumulate integer step counts per palette key across all rows.
    val countByKey = mutableMapOf<String, Int>()
    for (row in rows) {
        for (step in row.steps) {
            val key = step.description
            if (key in dbCodeByKey) {
                countByKey[key] = (countByKey[key] ?: 0) + step.count
            }
        }
    }
    if (countByKey.isEmpty()) return emptyMap()

    // Merge counts that share the same DB code, then convert once to grams.
    val countByCode = mutableMapOf<String, Int>()
    for ((key, count) in countByKey) {
        val code = dbCodeByKey[key] ?: continue
        countByCode[code] = (countByCode[code] ?: 0) + count
    }

    return countByCode.mapValues { (_, count) ->
        val scaled = count.toDouble() / BEADS_PER_GRAM * 100.0
        scaled.roundToLong() / 100.0
    }
}

/**
 * Computes summary statistics for a project's bead grid.
 *
 * Returns null when [rowCount] is zero (indicating no grid) or [rows] is empty.
 *
 * [beadCountsByKey] maps each palette letter key found in the grid steps to the total step
 * count for that key across all rows. Steps with an empty [com.techyshishy.beadmanager.data.firestore.ProjectRgpStep.description]
 * are skipped. Keys present in [colorMapping] but absent from all steps are not included.
 */
fun computeGridSummary(
    rows: List<ProjectRgpRow>,
    colorMapping: Map<String, String>,
    rowCount: Int,
): GridSummary? {
    if (rowCount == 0 || rows.isEmpty()) return null

    val beadCountsByKey = mutableMapOf<String, Int>()
    for (row in rows) {
        for (step in row.steps) {
            val key = step.description
            if (key.isNotEmpty() && key in colorMapping) {
                beadCountsByKey[key] = (beadCountsByKey[key] ?: 0) + step.count
            }
        }
    }

    return GridSummary(
        totalBeads = beadCountsByKey.values.sum(),
        totalColors = colorMapping.size,
        rowCount = rowCount,
        maxBeadsWide = rows.maxOf { row -> row.steps.sumOf { it.count } },
        beadCountsByKey = beadCountsByKey,
    )
}
