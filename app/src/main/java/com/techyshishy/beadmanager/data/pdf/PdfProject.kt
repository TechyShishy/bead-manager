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

/**
 * One chart variant extracted from a multi-variant PDF (e.g. a Size1 and a Size2 chart
 * embedded in the same file). [label] is assigned by document order: "Variant 1",
 * "Variant 2", etc. The color mapping is shared across all variants and lives in
 * [com.techyshishy.beadmanager.domain.ImportResult.PendingVariantChoice].
 */
data class PdfVariant(val label: String, val rows: List<PdfRow>)
