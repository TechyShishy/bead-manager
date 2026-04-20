package com.techyshishy.beadmanager.data.pdf

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accumulates intermediate state from the PDF import pipeline for post-mortem
 * debugging of failed imports.
 *
 * Each [ImportPdfProjectUseCase.import] call creates one instance. Both parsers
 * and the use case itself populate fields as the import progresses. On failure the
 * use case passes the collector to [PdfImportDiagnosticsWriter] which persists
 * [toReport] to a local file.
 *
 * Large text captures are capped at [MAX_TEXT_CHARS] characters in [toReport]
 * to keep diagnostics files a manageable size.
 */
class PdfImportDiagnosticsCollector {

    companion object {
        /** Maximum characters emitted per large text blob in [toReport]. */
        private const val MAX_TEXT_CHARS = 50_000
    }

    // ── Text extraction ───────────────────────────────────────────────────────

    var pageCount: Int = 0
    val pageTexts: MutableList<String> = mutableListOf()

    // ── BeadTool 4 parser state ───────────────────────────────────────────────

    var beadToolAttempted: Boolean = false

    /** Text after stripPageHeaders + formfeed removal. */
    var beadToolStrippedText: String? = null

    /** Text after cleanText (asterisk/Word Chart header removal). */
    var beadToolCleanedText: String? = null

    /** Text after joinContinuationLines. */
    var beadToolContinuedText: String? = null

    /** Whether a row block was extracted. */
    var beadToolRowBlockFound: Boolean = false

    /** Full row block text (may be long). */
    var beadToolRowBlock: String? = null

    /** Number of rows parsed from the row block. */
    var beadToolRowCount: Int? = null

    /**
     * Per-row summary of parsed step counts, e.g. `["row 1: 107 beads", "row 2: 106 beads"]`.
     * Populated on both paired and single-row patterns; useful for verifying detangle output.
     */
    var beadToolRowSummary: List<String>? = null

    // ── BeadTool color-key OCR state ──────────────────────────────────────────

    /** 0-based page index of the color key page; -1 if not found. */
    var colorKeyPageIndex: Int = -1

    /** Number of ML Kit TextBlocks returned for the color key page. */
    var ocrBlockCount: Int? = null

    /** Raw OCR text blocks (one string per ML Kit TextBlock). */
    val ocrBlockTexts: MutableList<String> = mutableListOf()

    /** Color map recovered by OCR. */
    var ocrColorMap: Map<String, String>? = null

    /** Letters used by the pattern that were absent from [ocrColorMap]. */
    var ocrMissingLetters: Set<String>? = null

    // ── XLSM / Word Chart parser state ───────────────────────────────────────

    var xlsmAttempted: Boolean = false

    /** Color mapping extracted from the text (before row parsing). */
    var xlsmColorMap: Map<String, String>? = null

    /** Number of rows parsed. */
    var xlsmRowCount: Int? = null

    /** Letters present in rows but absent from [xlsmColorMap]. */
    var xlsmMissingLetters: List<String>? = null

    // ── Use-case level ────────────────────────────────────────────────────────

    /** Name of the final parsed project (may be blank on failure). */
    var parsedProjectName: String? = null

    /** Catalog codes not found in Room (UnrecognizedCodes failure path). */
    var unrecognizedCatalogCodes: List<String>? = null

    /** Human-readable description of the terminal failure reason. */
    var failureReason: String? = null

    // ── Report generation ─────────────────────────────────────────────────────

    fun toReport(): String = buildString {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("=== PDF Import Diagnostics ===")
        appendLine("Timestamp : $ts")
        appendLine("Failure   : ${failureReason ?: "(none — import succeeded)"}")
        appendLine()

        // Text extraction
        appendLine("── Text Extraction ──────────────────────────────────────────")
        appendLine("Pages extracted : $pageCount")
        pageTexts.forEachIndexed { i, text ->
            appendLine()
            appendLine("--- Page ${i + 1} (${text.length} chars) ---")
            appendLine(text.take(MAX_TEXT_CHARS))
            if (text.length > MAX_TEXT_CHARS) appendLine("[... truncated at $MAX_TEXT_CHARS chars]")
        }
        appendLine()

        // BeadTool 4
        appendLine("── BeadTool 4 Parser ────────────────────────────────────────")
        appendLine("Attempted       : $beadToolAttempted")
        if (beadToolAttempted) {
            appendLine("Row block found : $beadToolRowBlockFound")
            appendLine("Row count       : ${beadToolRowCount ?: "N/A"}")
            beadToolRowSummary?.let { summary ->
                appendLine("Row summary     :")
                summary.forEach { line -> appendLine("  $line") }
            }
            beadToolStrippedText?.let {
                appendLine()
                appendLine("--- Stripped text (${it.length} chars) ---")
                appendLine(it.take(MAX_TEXT_CHARS))
                if (it.length > MAX_TEXT_CHARS) appendLine("[... truncated at $MAX_TEXT_CHARS chars]")
            }
            beadToolCleanedText?.let {
                appendLine()
                appendLine("--- Cleaned text (${it.length} chars) ---")
                appendLine(it.take(MAX_TEXT_CHARS))
                if (it.length > MAX_TEXT_CHARS) appendLine("[... truncated at $MAX_TEXT_CHARS chars]")
            }
            beadToolContinuedText?.let {
                appendLine()
                appendLine("--- Continued text (${it.length} chars) ---")
                appendLine(it.take(MAX_TEXT_CHARS))
                if (it.length > MAX_TEXT_CHARS) appendLine("[... truncated at $MAX_TEXT_CHARS chars]")
            }
            beadToolRowBlock?.let {
                appendLine()
                appendLine("--- Row block (${it.length} chars) ---")
                appendLine(it.take(MAX_TEXT_CHARS))
                if (it.length > MAX_TEXT_CHARS) appendLine("[... truncated at $MAX_TEXT_CHARS chars]")
            }
        }
        appendLine()

        // OCR
        appendLine("── Color Key OCR ────────────────────────────────────────────")
        appendLine("Color key page  : ${if (colorKeyPageIndex == -1) "not found" else colorKeyPageIndex}")
        appendLine("OCR blocks      : ${ocrBlockCount ?: "N/A"}")
        ocrColorMap?.let { appendLine("OCR color map   : $it") }
        ocrMissingLetters?.let { appendLine("OCR missing     : $it") }
        if (ocrBlockTexts.isNotEmpty()) {
            appendLine()
            appendLine("--- OCR block texts ---")
            ocrBlockTexts.forEachIndexed { i, text ->
                appendLine("[Block $i] $text")
            }
        }
        appendLine()

        // XLSM
        appendLine("── XLSM / Word Chart Parser ─────────────────────────────────")
        appendLine("Attempted       : $xlsmAttempted")
        if (xlsmAttempted) {
            appendLine("Row count       : ${xlsmRowCount ?: "N/A"}")
            xlsmColorMap?.let { appendLine("Color map       : $it") }
            xlsmMissingLetters?.let { appendLine("Missing letters : $it") }
        }
        appendLine()

        // Use-case
        appendLine("── Use Case ─────────────────────────────────────────────────")
        appendLine("Project name    : ${parsedProjectName ?: "(not set)"}")
        unrecognizedCatalogCodes?.let { appendLine("Unknown codes   : $it") }
        appendLine()
        appendLine("=== End of Report ===")
    }
}
