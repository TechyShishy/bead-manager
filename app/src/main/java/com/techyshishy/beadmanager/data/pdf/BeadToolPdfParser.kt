package com.techyshishy.beadmanager.data.pdf

import android.util.Log
import javax.inject.Inject

/**
 * Parses BeadTool 4 PDF exported word charts into a [PdfProject].
 *
 * Accepts the per-page text produced by [extractPdfText]. Color mapping is not
 * extractable from BeadTool 4 PDFs (the bead legend is a rasterized image);
 * the returned [PdfProject.colorMapping] is always empty. Use
 * [BeadToolColorKeyExtractor] to populate it from the rendered color key page.
 *
 * Row-parsing logic is ported from the rowguide TypeScript reference
 * implementation (`beadtool-pdf.service.ts`).
 */
class BeadToolPdfParser @Inject constructor() {

    companion object {
        private const val TAG = "PdfImport"
    }

    /**
     * Parses [pages] (one string per PDF page, as returned by [extractPdfText])
     * into a [PdfProject] with empty [PdfProject.colorMapping].
     *
     * When [diagnostics] is provided, each intermediate text transformation and
     * the row-block extraction result are captured for post-mortem debugging.
     *
     * @throws [PdfParseException.NoPatternFound] when no recognizable row
     *   notation is found after all cleaning steps.
     */
    fun parse(
        pages: List<String>,
        diagnostics: PdfImportDiagnosticsCollector? = null,
    ): PdfProject {
        diagnostics?.beadToolAttempted = true
        val name = extractName(pages)
        val allText = pages.joinToString("\n")
        val stripped = stripPageHeaders(allText)
        val cleaned = cleanText(stripped)
        val continued = joinContinuationLines(cleaned)
        diagnostics?.beadToolStrippedText = stripped
        diagnostics?.beadToolCleanedText = cleaned
        diagnostics?.beadToolContinuedText = continued
        val rowBlock = extractRowBlock(continued)
        diagnostics?.beadToolRowBlockFound = rowBlock != null
        diagnostics?.beadToolRowBlock = rowBlock
        if (rowBlock == null) {
            Log.d(TAG, "BeadTool: no row block matched")
            throw PdfParseException.NoPatternFound()
        }
        Log.d(TAG, "BeadTool: row block found (${rowBlock.length} chars), first line='${rowBlock.lines().firstOrNull()}'")
        val rows = parseRows(rowBlock)
        diagnostics?.beadToolRowCount = rows.size
        diagnostics?.beadToolRowSummary = rows.map { r -> "row ${r.id}: ${r.steps.sumOf { it.count }} beads" }
        Log.d(TAG, "BeadTool: parsed ${rows.size} rows")
        return PdfProject(name = name, colorMapping = emptyMap(), rows = rows)
    }

    // ── Name extraction ───────────────────────────────────────────────────────

    private fun extractName(pages: List<String>): String =
        pages.firstOrNull()
            ?.lineSequence()
            ?.firstOrNull(String::isNotBlank)
            ?.trim()
            ?: ""

    // ── Text cleaning ─────────────────────────────────────────────────────────

    /**
     * Removes per-page "Created with BeadTool 4" headers and "Page N of M"
     * footers injected into the text stream by BeadTool 4's PDF writer.
     *
     * Also strips form feed bytes (\u000C) that pdfbox inserts at page
     * boundaries; leaving them in breaks the row-block regex continuity.
     */
    private fun stripPageHeaders(text: String): String =
        text
            .replace("\u000C", "")
            .replace(
                Regex("""[^\n]*\n?\n?Created with BeadTool 4 - www\.beadtool\.net\n?\n?"""),
                "",
            )
            .replace(Regex("""[^\n]* ?Page [0-9]+(?: of [0-9]+)?\n"""), "")

    /**
     * Removes asterisk-wrapped title artifacts and "Word Chart" section
     * headers that appear as visual separators in the PDF text stream.
     */
    private fun cleanText(text: String): String =
        text
            .replace(Regex("""\*\*\*.*\*\*\*"""), "")
            .replace(Regex("""Word Chart \((?:Peyote|Loom)\)"""), "")

    /**
     * Joins continuation lines so each row occupies exactly one line.
     *
     * BeadTool 4 PDFs sometimes wrap long rows across line boundaries, either
     * with a trailing comma or starting the next fragment directly with a step
     * notation `(N)X`. Both cases are handled.
     *
     * Ported verbatim from the rowguide TypeScript reference implementation.
     */
    private fun joinContinuationLines(text: String): String =
        text
            .replace(Regex(""",\n+"""), ", ")
            .replace(Regex("""\n+(?=\(\d+\)\w)"""), ", ")

    // ── Row block extraction ──────────────────────────────────────────────────

    /**
     * Extracts the contiguous block of row-notation lines from [text].
     *
     * Tries the paired-row variant first ("Row 1&2 …"), then the single-row
     * fallback ("Row 1 …"). Returns null when neither matches.
     */
    private fun extractRowBlock(text: String): String? {
        val pairedPattern = Regex(
            """((?:Row 1&2 \([LR]\) +(?:\(\d+\)\w+(?:,\s+)?)+\n*)(?:Row \d+ \([LR]\) +(?:\(\d+\)\w+(?:,\s+)?)+\n*)+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val singlePattern = Regex(
            """((?:Row 1 \([LR]\) +(?:\(\d+\)\w+(?:,\s+)?)+\n*)(?:Row \d+ \([LR]\) +(?:\(\d+\)\w+(?:,\s+)?)+\n*)+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return pairedPattern.find(text)?.groupValues?.get(1)?.trim()
            ?: singlePattern.find(text)?.groupValues?.get(1)?.trim()
    }

    // ── Row parsing ───────────────────────────────────────────────────────────

    /** Matches `Row N` or `Row N&M` header with direction indicator. */
    private val rowLineRegex = Regex("""Row (\d+)(?:&(\d+))? \([LR]\) (.+)""")

    /** Matches a single step token, e.g. `(3)AD` or `(1)A`. */
    private val stepRegex = Regex("""\((\d+)\)([A-Z]{1,2})""")

    /**
     * Parses every row line in [rowBlock] into [PdfRow] objects.
     *
     * Paired rows ("Row 1&2") emit two [PdfRow] entries with detangled step
     * sequences — lower-numbered row first. The raw buffer interleaves the two
     * rows' beads: even-indexed beads belong to the higher-numbered row (row 2)
     * and odd-indexed beads belong to the lower-numbered row (row 1).
     */
    private fun parseRows(rowBlock: String): List<PdfRow> {
        val rows = mutableListOf<PdfRow>()
        for (line in rowBlock.lineSequence()) {
            val match = rowLineRegex.find(line.trim()) ?: continue
            val id = match.groupValues[1].toInt()
            val pairedId = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            val steps = stepRegex.findAll(match.groupValues[3]).map { m ->
                PdfStep(count = m.groupValues[1].toInt(), colorLetter = m.groupValues[2])
            }.toList()
            if (steps.isEmpty()) continue
            if (pairedId != null) {
                val (row1Steps, row2Steps) = detangleSteps(steps)
                Log.d(TAG, "Detangled rows $id&$pairedId: buffer=${steps.sumOf { it.count }} beads → row $id=${row1Steps.sumOf { it.count }}, row $pairedId=${row2Steps.sumOf { it.count }}")
                rows.add(PdfRow(id = id, steps = row1Steps))
                rows.add(PdfRow(id = pairedId, steps = row2Steps))
            } else {
                rows.add(PdfRow(id = id, steps = steps))
            }
        }
        return rows
    }

    /**
     * Splits an interleaved paired-row buffer into two independent step sequences.
     *
     * BeadTool 4 encodes "Row 1&2" lines by alternating beads from each row:
     * even-indexed beads (0, 2, 4, …) belong to the higher-numbered row (row 2),
     * odd-indexed beads (1, 3, 5, …) belong to the lower-numbered row (row 1).
     * No reversal is applied to either sequence — the beads are taken in the
     * order they appear at their respective indices (see issue #45).
     *
     * @return Pair of (row1Steps, row2Steps)
     */
    private fun detangleSteps(steps: List<PdfStep>): Pair<List<PdfStep>, List<PdfStep>> {
        val flat = steps.flatMap { step -> List(step.count) { step.colorLetter } }
        val row1Beads = flat.filterIndexed { i, _ -> i % 2 == 1 }
        val row2Beads = flat.filterIndexed { i, _ -> i % 2 == 0 }
        return encodeRle(row1Beads) to encodeRle(row2Beads)
    }

    /** Re-encodes a flat bead list into RLE [PdfStep] form. */
    private fun encodeRle(beads: List<String>): List<PdfStep> {
        if (beads.isEmpty()) return emptyList()
        val result = mutableListOf<PdfStep>()
        var current = beads[0]
        var count = 1
        for (i in 1 until beads.size) {
            if (beads[i] == current) {
                count++
            } else {
                result.add(PdfStep(count, current))
                current = beads[i]
                count = 1
            }
        }
        result.add(PdfStep(count, current))
        return result
    }
}
