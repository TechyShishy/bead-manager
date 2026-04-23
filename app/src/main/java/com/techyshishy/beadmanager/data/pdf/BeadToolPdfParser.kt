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
        val allText = pages.joinToString("\n")
        val stripped = stripPageHeaders(allText)
        // Some BeadTool 4 PDFs export both a Loom Word Chart and a Peyote Word Chart.
        // Parsing the full text merges rows from both sections, doubling the row count.
        // Prefer the Peyote section when the section header is present.
        val isolated = isolatePeyoteSection(stripped)
        val sectionText = isolated ?: stripped
        val cleaned = cleanText(sectionText)
        val legendStripped = stripLegendBlocks(cleaned)
        val continued = joinContinuationLines(legendStripped)
        Log.d(TAG, "BeadTool pipeline: allText=${allText.length}ch stripped=${stripped.length}ch " +
            "section=${sectionText.length}ch (isolated=${isolated != null}) " +
            "cleaned=${cleaned.length}ch legendStripped=${legendStripped.length}ch " +
            "continued=${continued.length}ch")
        Log.d(TAG, "BeadTool 'Row 1' presence: allText=${allText.contains("Row 1")} " +
            "stripped=${stripped.contains("Row 1")} " +
            "section=${sectionText.contains("Row 1")} " +
            "cleaned=${cleaned.contains("Row 1")} " +
            "legendStripped=${legendStripped.contains("Row 1")} " +
            "continued=${continued.contains("Row 1")}")
        diagnostics?.beadToolStrippedText = stripped
        diagnostics?.beadToolSectionText = sectionText
        diagnostics?.beadToolSectionIsolated = isolated != null
        diagnostics?.beadToolCleanedText = cleaned
        diagnostics?.beadToolLegendStrippedText = legendStripped
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
        return PdfProject(colorMapping = emptyMap(), rows = rows)
    }

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
     * Isolates the Peyote Word Chart section from [stripped] text when the PDF
     * contains explicit "Word Chart (Peyote)" / "Word Chart (Loom)" section
     * markers (present in BeadTool 4 exports that include both stitch types).
     *
     * Returns the substring starting at "Word Chart (Peyote)" and ending before
     * any subsequent "Word Chart (Loom)" marker, or null when no peyote marker
     * is found — in which case the caller falls back to the full stripped text.
     */
    private fun isolatePeyoteSection(stripped: String): String? {
        val peyoteMarker = "Word Chart (Peyote)"
        val startIdx = stripped.indexOf(peyoteMarker)
        if (startIdx == -1) return null
        val loomMarker = "Word Chart (Loom)"
        val endIdx = stripped.indexOf(loomMarker, startIdx + peyoteMarker.length)
            .takeIf { it != -1 } ?: stripped.length
        return stripped.substring(startIdx, endIdx)
    }

    /**
     * Removes asterisk-wrapped title artifacts and "Word Chart" section
     * headers that appear as visual separators in the PDF text stream.
     */
    private fun cleanText(text: String): String =
        text
            .replace(Regex("""\*\*\*.*\*\*\*"""), "")
            .replace(Regex("""Word Chart \((?:Peyote|Loom)\)"""), "")

    /**
     * Removes the `N of MBead Legend … Word Chart` footer block injected mid-stream
     * at page boundaries in PatternsByAliia lighter-cover PDFs.
     *
     * BeadTool 4 renders a bead legend at the bottom of certain pages. In the
     * PatternsByAliia lighter-cover format this block appears *inside* a row
     * continuation — the row's trailing comma precedes it on the same page, and
     * the continuation tokens follow it on the next page. If not stripped here,
     * `joinContinuationLines` attaches the legend text to the row and orphans the
     * continuation, producing a row with fewer beads than expected.
     *
     * The match is deliberately narrow:
     * - `\d+ of \d+Bead Legend` anchors to the concatenated page-counter artifact
     * - `(?:.*\n)*?` lazily matches the variable-count body lines (Chart #, DB code,
     *   Count) without crossing the `Word Chart` terminator
     * - `Word Chart\n*` consumes the terminator and any trailing blank lines
     */
    private fun stripLegendBlocks(text: String): String =
        text.replace(
            Regex("""\d+ of \d+Bead Legend\n(?:.*\n)*?Word Chart\n*"""),
            "",
        )

    /**
     * Joins continuation lines so each row occupies exactly one line.
     *
     * BeadTool 4 PDFs sometimes wrap long rows across line boundaries, either
     * with a trailing comma or starting the next fragment directly with a step
     * notation `(N)X`. Both cases are handled.
     *
     * The lookahead mirrors [stepRegex]: it tolerates optional whitespace inside
     * the count group and between `)` and the color letter so that a continuation
     * line beginning with a whitespace-variant token such as `( 1)B` is still
     * stitched to the preceding row line.
     *
     * Ported verbatim from the rowguide TypeScript reference implementation.
     */
    private fun joinContinuationLines(text: String): String =
        text
            .replace(Regex(""",\n+"""), ", ")
            .replace(Regex("""\n+(?=\(\s*\d+\s*\)\s*\w)"""), ", ")

    // ── Row block extraction ──────────────────────────────────────────────────

    /**
     * Collects all row-notation lines from [text] and returns them sorted by
     * row number, or null when no recognizable rows are found.
     *
     * The previous approach required all row lines to form a single contiguous
     * block in the text. That assumption breaks for PDFs whose row lines are
     * separated by space-only blank lines (`\n \n`) or interrupted by per-page
     * custom headers — both of which appear in third-party patterns exported in
     * a two-column layout (e.g. the "Bead with Bugs" format). Collecting rows
     * with [Regex.findAll] and sorting by row number is robust to both cases.
     */
    private fun extractRowBlock(text: String): String? {
        val rowLinePattern = Regex("""Row (\d+)(?:&\d+)? \([LR]\) .+""")
        val rows = rowLinePattern.findAll(text)
            .sortedBy { it.groupValues[1].toInt() }
            .map { it.value }
            .toList()
        if (rows.isEmpty()) {
            Log.d(TAG, "extractRowBlock: no rows matched in ${text.length}-char text. " +
                "Contains 'Row '=${text.contains("Row ")}. " +
                "First 500 chars: ${text.take(500).replace('\n', '↵')}")
            return null
        }
        val hasStart = rows.any { it.startsWith("Row 1&2 ") || it.startsWith("Row 1 ") }
        if (!hasStart) {
            Log.d(TAG, "extractRowBlock: ${rows.size} rows found but none is Row 1 or Row 1&2. " +
                "Lowest row: '${rows.first().take(80)}'")
            return null
        }
        return rows.joinToString("\n")
    }

    // ── Row parsing ───────────────────────────────────────────────────────────

    /** Matches `Row N` or `Row N&M` header with direction indicator. */
    private val rowLineRegex = Regex("""Row (\d+)(?:&(\d+))? \([LR]\) (.+)""")

    /** Matches a single step token, e.g. `(3)AD` or `(1)A`.
     *
     * Optional whitespace is allowed inside the count group and between `)` and
     * the color letter to handle BeadTool 4 PDF text extraction artifacts such as
     * `( 1)B`, `(1) A`, and `( 1) A`. The `\s*` is bounded by the required
     * `[A-Z]{1,2}` anchor so it cannot consume delimiter characters.
     */
    private val stepRegex = Regex("""\(\s*(\d+)\s*\)\s*([A-Z]{1,2})""")

    /**
     * Parses every row line in [rowBlock] into [PdfRow] objects.
     *
     * Paired rows ("Row 1&2") emit two [PdfRow] entries with detangled step
     * sequences — lower-numbered row first. The raw buffer interleaves the two
     * rows' beads: odd-indexed beads belong to the lower-numbered row (row 1)
     * and even-indexed beads belong to the higher-numbered row (row 2).
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
     * odd-indexed beads (1, 3, 5, …) belong to the lower-numbered row (row 1),
     * even-indexed beads (0, 2, 4, …) belong to the higher-numbered row (row 2).
     *
     * Row 1 ends up at list index 0 (even) in the rows array passed to
     * [GenerateProjectPreviewUseCase]. The renderer places even-index rows flush
     * (no vertical offset) without reversal, so row 1 must be stored reversed to
     * produce the correct display direction.
     *
     * Row 2 ends up at list index 1 (odd). The renderer reverses odd-index rows
     * before placing them into offset pixel columns, so row 2 must be stored in
     * natural extraction order for the rendered result to be correct.
     *
     * @return Pair of (row1Steps, row2Steps)
     */
    private fun detangleSteps(steps: List<PdfStep>): Pair<List<PdfStep>, List<PdfStep>> {
        val flat = steps.flatMap { step -> List(step.count) { step.colorLetter } }
        val evenBeads = flat.filterIndexed { i, _ -> i % 2 == 0 }
        val oddBeads = flat.filterIndexed { i, _ -> i % 2 == 1 }
        return encodeRle(oddBeads.reversed()) to encodeRle(evenBeads)
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
