package com.techyshishy.beadmanager.data.pdf

import android.util.Log
import javax.inject.Inject

/**
 * Parses Word Chart-formatted PDFs (typically produced from XLSM spreadsheet bead patterns)
 * into a [PdfProject].
 *
 * Real-world XLSM PDFs (e.g. AZ Art Jewelry exports) follow this page structure:
 *  - Cover / metadata page(s)
 *  - Information page — contains the color key table: `DB0005 A Deep Blue:MET IRIS   65`
 *  - One or more "Word Cart" / "Word Chart" pages — pattern rows: `3 R 26(J)`
 *  - Grid page(s) — visual grid, ignored
 *
 * Row-parsing logic is ported from `xlsm-pdf.service.ts` in the rowguide companion
 * project. Color mapping extraction is based on observed real-world PDF structure
 * (DB code appears before the letter, no hyphen in source).
 */
class XlsmPdfParser @Inject constructor() {

    companion object {
        private const val TAG = "PdfImport"
    }

    /**
     * Parses [pages] (one string per PDF page, as returned by [extractPdfText])
     * into a [PdfProject] with a fully-populated [PdfProject.colorMapping].
     *
     * When [diagnostics] is provided, the extracted color map, row count, and any
     * missing letters are captured before the function throws.
     *
     * @throws [PdfParseException.NoPatternFound] when no Word Chart / Word Cart
     *   section is found or the section contains no parseable rows.
     * @throws [PdfParseException.IncompleteColorMapping] when one or more color
     *   letters referenced in pattern rows have no entry in the extracted color key.
     */
    fun parse(
        pages: List<String>,
        diagnostics: PdfImportDiagnosticsCollector? = null,
    ): PdfProject {
        diagnostics?.xlsmAttempted = true
        if (pages.isEmpty()) throw PdfParseException.NoPatternFound()
        val allText = pages.joinToString("\n")
        val colorMapping = extractColorMapping(allText)
        diagnostics?.xlsmColorMap = colorMapping
        Log.d(TAG, "XLSM color mapping: ${colorMapping.size} entries = $colorMapping")
        val rows = extractRows(allText)
        diagnostics?.xlsmRowCount = rows.size
        Log.d(TAG, "XLSM extractRows: ${rows.size} rows")
        if (rows.isEmpty()) throw PdfParseException.NoPatternFound()
        val usedLetters = rows.flatMap { row -> row.steps.map { it.colorLetter } }.toSet()
        val missingLetters = (usedLetters - colorMapping.keys).sorted()
        if (missingLetters.isNotEmpty()) {
            diagnostics?.xlsmMissingLetters = missingLetters
            Log.w(TAG, "XLSM IncompleteColorMapping — missing: $missingLetters (used: $usedLetters, mapping keys: ${colorMapping.keys.sorted()})")
            throw PdfParseException.IncompleteColorMapping(missingLetters)
        }
        return PdfProject(colorMapping = colorMapping, rows = rows)
    }

    // ── Color mapping extraction ──────────────────────────────────────────────

    /**
     * Matches XLSM color key entries in the format `DB0005 A `, where the DB code
     * has no hyphen and the single- or double-letter color abbreviation follows.
     *
     * Observed in real-world XLSM exports: the DB code and letter are separated by
     * whitespace, and the letter is followed by more whitespace and a color description.
     * Output is normalized: `DB0005` → `DB-0005`.
     *
     * Horizontal-only whitespace (`[ \t]`) is used to avoid false matches across
     * page boundaries when pages are joined with `\n`.
     */
    private val colorKeyRegex = Regex("""DB(\d{4})[ \t]+([A-Z]{1,2})[ \t]""")

    private fun extractColorMapping(allText: String): Map<String, String> =
        colorKeyRegex.findAll(allText).associate { match ->
            val letter = match.groupValues[2]
            val dbCode = "DB-${match.groupValues[1]}"
            letter to dbCode
        }

    // ── Row extraction ────────────────────────────────────────────────────────

    /**
     * Matches either XLSM `count(color)` or peyote-shorthand `(count)color` step
     * tokens. Groups:
     *  - 1, 2: XLSM count and color (when group 1 is non-empty)
     *  - 3, 4: peyote count and color (when group 3 is non-empty)
     */
    private val stepTokenRegex = Regex("""(\d+)\(([A-Z]{1,2})\)|\((\d+)\)([A-Z]{1,2})""")

    private val rangeRowRegex = Regex("""^(\d+)\s*&\s*(\d+)\s+([LR])\s+(.+)$""")
    private val singleRowRegex = Regex("""^(\d+)\s+([LR])\s+(.+)$""")
    private val wordChartTableHeaderRegex = Regex("""^row\s+direction\s+word\s+chart$""")

    /**
     * A line is a continuation if it contains step notation but is not a row header.
     *
     * Handles bead sequences split across lines in the PDF text stream. Both XLSM
     * `3(G)` and peyote-shorthand `(3)G` tokens are recognized.
     */
    private fun isContinuationLine(line: String): Boolean =
        stepTokenRegex.containsMatchIn(line) &&
            !rangeRowRegex.containsMatchIn(line) &&
            !singleRowRegex.containsMatchIn(line)

    private fun extractRows(allText: String): List<PdfRow> {
        val lines = allText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val rows = mutableListOf<PdfRow>()
        var inWordChart = false
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Word Chart table header: "Row Direction Word Chart"
            if (line.lowercase().matches(wordChartTableHeaderRegex)) {
                inWordChart = true
                i++
                continue
            }

            // Word Chart / Word Cart section header (including "Word Cart" misspelling).
            // startsWith rather than contains to avoid matching prose lines that happen
            // to mention "word chart" (e.g. a cover-page description).
            if (!inWordChart &&
                (line.lowercase().startsWith("word chart") || line.lowercase().startsWith("word cart"))
            ) {
                inWordChart = true
                i++
                continue
            }

            // Grid section terminates row parsing
            if (inWordChart && line.lowercase().startsWith("grid")) break

            if (inWordChart) {
                val rangeMatch = rangeRowRegex.find(line)
                if (rangeMatch != null) {
                    val startRow = rangeMatch.groupValues[1].toInt()
                    val endRow = rangeMatch.groupValues[2].toInt()
                    var sequence = rangeMatch.groupValues[4]
                    var j = i + 1
                    while (j < lines.size && isContinuationLine(lines[j])) {
                        sequence += " " + lines[j]
                        j++
                    }
                    i = j - 1
                    val steps = parseSteps(sequence)
                    if (steps.isNotEmpty()) {
                        rows.add(PdfRow(id = startRow, steps = steps))
                        rows.add(PdfRow(id = endRow, steps = steps))
                    }
                    i++
                    continue
                }

                val singleMatch = singleRowRegex.find(line)
                if (singleMatch != null) {
                    val rowNum = singleMatch.groupValues[1].toInt()
                    var sequence = singleMatch.groupValues[3]
                    var j = i + 1
                    while (j < lines.size && isContinuationLine(lines[j])) {
                        sequence += " " + lines[j]
                        j++
                    }
                    i = j - 1
                    val steps = parseSteps(sequence)
                    if (steps.isNotEmpty()) {
                        rows.add(PdfRow(id = rowNum, steps = steps))
                    }
                    i++
                    continue
                }
            }

            i++
        }

        return rows
    }

    // ── Step parsing ──────────────────────────────────────────────────────────

    /**
     * Parses a bead sequence string into [PdfStep]s. Handles both XLSM
     * `count(color)` notation and peyote-shorthand `(count)color` pass-through,
     * including mixed sequences. Each token is resolved independently.
     */
    private fun parseSteps(sequence: String): List<PdfStep> =
        stepTokenRegex.findAll(sequence).map { m ->
            if (m.groupValues[1].isNotEmpty()) {
                // XLSM format: 3(G)
                PdfStep(count = m.groupValues[1].toInt(), colorLetter = m.groupValues[2])
            } else {
                // Peyote-shorthand: (3)G
                PdfStep(count = m.groupValues[3].toInt(), colorLetter = m.groupValues[4])
            }
        }.toList()
}
