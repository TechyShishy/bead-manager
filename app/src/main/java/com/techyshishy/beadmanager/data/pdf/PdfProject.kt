package com.techyshishy.beadmanager.data.pdf

/**
 * Intermediate representation of a bead pattern extracted from a PDF.
 *
 * All PDF format parsers (BeadTool 4, XLSM/Word Chart) produce this model.
 * [colorMapping] maps single-letter color abbreviations (e.g. "A") to Delica
 * bead codes (e.g. "DB-0001").
 */
data class PdfProject(
    val colorMapping: Map<String, String>,
    val rows: List<PdfRow>,
)

/** A single row in a bead pattern. [id] is 1-based. */
data class PdfRow(val id: Int, val steps: List<PdfStep>)

/** A run of [count] beads of [colorLetter] within a row. */
data class PdfStep(val count: Int, val colorLetter: String)
