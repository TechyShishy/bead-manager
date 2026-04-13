package com.techyshishy.beadmanager.data.model

import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import kotlin.math.roundToLong

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
