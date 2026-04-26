package com.techyshishy.beadmanager.ui.catalog

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File

internal const val TRAY_COLS = 10
internal const val TRAY_ROWS_PER_CARD = 5
internal const val TRAY_CARDS_PER_PAGE = 4
internal const val TRAY_SLOTS_PER_CARD = TRAY_COLS * TRAY_ROWS_PER_CARD
internal const val TRAY_SLOTS_PER_PAGE = TRAY_SLOTS_PER_CARD * TRAY_CARDS_PER_PAGE

// Cell dimensions in PDF points (1 pt = 1/72 in).
// Physical size: 0.93 in wide × 0.38 in tall  →  66.96 pt × 27.36 pt, rounded to whole points.
internal const val TRAY_CELL_WIDTH_PT = 67
internal const val TRAY_CELL_HEIGHT_PT = 27

// Per-card grid dimensions (10 cols × 5 rows at cell size above).
internal const val TRAY_CARD_WIDTH_PT = TRAY_COLS * TRAY_CELL_WIDTH_PT       // 670 pt
internal const val TRAY_CARD_HEIGHT_PT = TRAY_ROWS_PER_CARD * TRAY_CELL_HEIGHT_PT // 135 pt

// US Letter landscape: 11 × 8.5 in = 792 × 612 pt.
internal const val TRAY_PAGE_WIDTH_PT = 792
internal const val TRAY_PAGE_HEIGHT_PT = 612

// Margins that center the 4-card content block on the page.
// Horizontal: (792 − 670) / 2 = 61 pt each side.
// Vertical:   (612 − 4 × 135) / 2 = 36 pt top/bottom.
internal const val TRAY_PAGE_MARGIN_LEFT_PT = (TRAY_PAGE_WIDTH_PT - TRAY_CARD_WIDTH_PT) / 2   // 61
internal const val TRAY_PAGE_MARGIN_TOP_PT = (TRAY_PAGE_HEIGHT_PT - TRAY_CARDS_PER_PAGE * TRAY_CARD_HEIGHT_PT) / 2 // 36

// Maximum quantity a single test-tube tray slot can hold.
internal const val TRAY_SLOT_MAX_GRAMS = 10.0

/**
 * Number of pages required to lay out [totalCodes] bead codes across 4-up letter-landscape pages.
 *
 * Each page holds 4 tray card grids of 50 slots each (200 slots/page).
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
 * Pages are US Letter landscape (792 × 612 pt). Each page holds 4 tray card grids of
 * 10 cols × 5 rows, stacked vertically and centered on the page (~61 pt left/right margin,
 * ~36 pt top/bottom margin). Dashed horizontal cut guides are drawn at the top edge,
 * bottom edge, and between every card on the page. Cell dimensions remain 67 × 27 pt
 * (≈ 0.93 × 0.38 in), matching standard 50-slot test-tube bead trays.
 *
 * The caller is responsible for providing [codes] in the desired order; this function
 * renders them as-is. An empty [codes] list produces a minimal empty PDF.
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
            textSize = 12f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val cutGuidePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }

        val marginLeft = TRAY_PAGE_MARGIN_LEFT_PT.toFloat()
        val marginTop = TRAY_PAGE_MARGIN_TOP_PT.toFloat()
        val contentRight = marginLeft + TRAY_CARD_WIDTH_PT

        for (pageIndex in 0 until numPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                TRAY_PAGE_WIDTH_PT,
                TRAY_PAGE_HEIGHT_PT,
                pageIndex + 1,
            ).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val pageSliceStart = pageIndex * TRAY_SLOTS_PER_PAGE
            val pageSliceEnd = minOf(pageSliceStart + TRAY_SLOTS_PER_PAGE, codes.size)

            // Number of full or partial cards on this page.
            val codesOnPage = pageSliceEnd - pageSliceStart
            val cardsOnPage = (codesOnPage + TRAY_SLOTS_PER_CARD - 1) / TRAY_SLOTS_PER_CARD

            // Draw cut guides: one at the top edge and one below each card present on the page.
            for (cardIndex in 0..cardsOnPage) {
                val guideY = marginTop + cardIndex * TRAY_CARD_HEIGHT_PT
                canvas.drawLine(marginLeft, guideY, contentRight, guideY, cutGuidePaint)
            }

            // Render each card's cells.
            for (i in pageSliceStart until pageSliceEnd) {
                val slotIndex = i - pageSliceStart
                val cardIndex = slotIndex / TRAY_SLOTS_PER_CARD
                val slotInCard = slotIndex % TRAY_SLOTS_PER_CARD
                val col = slotInCard % TRAY_COLS
                val row = slotInCard / TRAY_COLS

                val cellLeft = marginLeft + col * TRAY_CELL_WIDTH_PT
                val cellTop = marginTop + cardIndex * TRAY_CARD_HEIGHT_PT + row * TRAY_CELL_HEIGHT_PT
                val cellRight = cellLeft + TRAY_CELL_WIDTH_PT
                val cellBottom = cellTop + TRAY_CELL_HEIGHT_PT

                canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, borderPaint)

                // Centre text vertically within the cell.
                val textBaseline = cellTop + TRAY_CELL_HEIGHT_PT / 2f +
                    (textPaint.textSize / 2f) - textPaint.descent()
                val displayCode = truncateToFit(
                    codes[i],
                    TRAY_CELL_WIDTH_PT - 4f, // 2f margin on each side of centered text
                    textPaint::measureText,
                )
                canvas.drawText(displayCode, cellLeft + TRAY_CELL_WIDTH_PT / 2f, textBaseline, textPaint)
            }

            document.finishPage(page)
        }

        outputFile.outputStream().use { document.writeTo(it) }
    } finally {
        document.close()
    }
}
