package com.techyshishy.beadmanager.ui.catalog

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File

internal const val TRAY_COLS = 10
internal const val TRAY_ROWS_PER_PAGE = 5
internal const val TRAY_SLOTS_PER_PAGE = TRAY_COLS * TRAY_ROWS_PER_PAGE

// Cell dimensions in PDF points (1 pt = 1/72 in).
// Physical size: 0.93 in wide × 0.38 in tall  →  66.96 pt × 27.36 pt, rounded to whole points.
internal const val TRAY_CELL_WIDTH_PT = 67
internal const val TRAY_CELL_HEIGHT_PT = 27

internal const val TRAY_PAGE_WIDTH_PT = TRAY_COLS * TRAY_CELL_WIDTH_PT
internal const val TRAY_PAGE_HEIGHT_PT = TRAY_ROWS_PER_PAGE * TRAY_CELL_HEIGHT_PT

// Maximum quantity a single test-tube tray slot can hold.
internal const val TRAY_SLOT_MAX_GRAMS = 10.0

/**
 * Number of pages required to lay out [totalCodes] bead codes in a 10×5 tray card grid.
 *
 * Returns 0 if [totalCodes] is 0 (no pages needed for an empty inventory).
 */
internal fun pageCount(totalCodes: Int): Int =
    if (totalCodes == 0) 0 else (totalCodes + TRAY_SLOTS_PER_PAGE - 1) / TRAY_SLOTS_PER_PAGE

/**
 * Returns [text] unchanged if it fits within [maxWidth] as measured by [measureText].
 * Otherwise truncates from the right, appending `…`, until the result fits.
 */
internal fun truncateToFit(
    text: String,
    maxWidth: Float,
    measureText: (String) -> Float,
): String {
    if (measureText(text) <= maxWidth) return text
    val ellipsis = "\u2026"
    var truncated = text
    while (truncated.isNotEmpty() && measureText(truncated + ellipsis) > maxWidth) {
        truncated = truncated.dropLast(1)
    }
    return truncated + ellipsis
}

/**
 * Generates a printable tray card PDF at [outputFile].
 *
 * Beads are laid out in a 10-column × 5-row grid. Each cell is 67 × 27 PDF points
 * (≈ 0.93 in × 0.38 in when printed), matching standard 50-slot test tube bead trays.
 * Additional pages continue the sorted order when there are more than 50 codes.
 *
 * The caller is responsible for providing [codes] in the desired order; this function
 * renders them as-is. An empty [codes] list produces a zero-byte output file.
 */
fun generateTrayCard(codes: List<String>, outputFile: File) {
    val numPages = pageCount(codes.size)

    val document = PdfDocument()
    try {
        if (numPages == 0) {
            outputFile.outputStream().use { document.writeTo(it) }
            return
        }

        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }
        val textPaint = Paint().apply {
            textSize = 9f
            isFakeBoldText = true
            isAntiAlias = true
        }

        for (pageIndex in 0 until numPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                TRAY_PAGE_WIDTH_PT,
                TRAY_PAGE_HEIGHT_PT,
                pageIndex + 1,
            ).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val sliceStart = pageIndex * TRAY_SLOTS_PER_PAGE
            val sliceEnd = minOf(sliceStart + TRAY_SLOTS_PER_PAGE, codes.size)

            for (i in sliceStart until sliceEnd) {
                val slotIndex = i - sliceStart
                val col = slotIndex % TRAY_COLS
                val row = slotIndex / TRAY_COLS

                val cellLeft = (col * TRAY_CELL_WIDTH_PT).toFloat()
                val cellTop = (row * TRAY_CELL_HEIGHT_PT).toFloat()
                val cellRight = cellLeft + TRAY_CELL_WIDTH_PT
                val cellBottom = cellTop + TRAY_CELL_HEIGHT_PT

                canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, borderPaint)

                // Centre text vertically within the cell.
                val textBaseline = cellTop + TRAY_CELL_HEIGHT_PT / 2f +
                    (textPaint.textSize / 2f) - textPaint.descent()
                val displayCode = truncateToFit(
                    codes[i],
                    TRAY_CELL_WIDTH_PT - 4f, // 2f left pad (see drawText x offset) + 2f right margin
                    textPaint::measureText,
                )
                canvas.drawText(displayCode, cellLeft + 2f, textBaseline, textPaint)
            }

            document.finishPage(page)
        }

        outputFile.outputStream().use { document.writeTo(it) }
    } finally {
        document.close()
    }
}
