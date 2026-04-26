package com.techyshishy.beadmanager.ui.catalog

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

internal const val TRAY_COLS = 10
internal const val TRAY_ROWS_PER_CARD = 5
internal const val TRAY_CARDS_PER_PAGE = 4
internal const val TRAY_SLOTS_PER_CARD = TRAY_COLS * TRAY_ROWS_PER_CARD
internal const val TRAY_SLOTS_PER_PAGE = TRAY_SLOTS_PER_CARD * TRAY_CARDS_PER_PAGE

// Physical tray target: 10 cells must span this many mm on the printed page.
internal const val TRAY_TARGET_WIDTH_MM = 235f

// Cell dimensions in PDF points (1 pt = 1/72 in).
// TRAY_CELL_WIDTH_PT is the uncalibrated reference value, derived from the standard
// 50-slot test-tube tray slot pitch of 0.93 in: 0.93 × 72 = 66.96 pt, rounded to 67 pt.
// Per-device compensation (to hit TRAY_TARGET_WIDTH_MM on a specific printer) is applied
// at print time by TrayCardPrintDocumentAdapter using the device's stored calibration.
// Height: 27 pt (= 9.525 mm).
internal const val TRAY_CELL_WIDTH_PT = 67f
internal const val TRAY_CELL_HEIGHT_PT = 27

// Per-card grid dimensions (10 cols × 5 rows at cell size above).
internal const val TRAY_CARD_WIDTH_PT = TRAY_COLS * TRAY_CELL_WIDTH_PT       // 670 pt ≈ 236.4 mm (uncalibrated)
internal const val TRAY_CARD_HEIGHT_PT = TRAY_ROWS_PER_CARD * TRAY_CELL_HEIGHT_PT // 135 pt

// US Letter landscape: 11 × 8.5 in = 792 × 612 pt.
internal const val TRAY_PAGE_WIDTH_PT = 792
internal const val TRAY_PAGE_HEIGHT_PT = 612

// Margins that center the 4-card content block on the page.
// Horizontal: (792 − 670) / 2 = 61 pt each side (uncalibrated; runtime uses cellWidthPt).
// Vertical:   (612 − 4 × 135) / 2 = 36 pt top/bottom.
internal const val TRAY_PAGE_MARGIN_LEFT_PT: Float = (TRAY_PAGE_WIDTH_PT - TRAY_CARD_WIDTH_PT) / 2   // 61 pt
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
 * Maps a slot index within a single card to its (row, col) position using
 * south-then-east ordering: slots fill down each column before advancing east.
 *
 * slotIndex 0 → (row 0, col 0), 1 → (row 1, col 0), …, 4 → (row 4, col 0),
 * 5 → (row 0, col 1), …, 49 → (row 4, col 9).
 */
internal fun slotRowCol(slotInCard: Int): Pair<Int, Int> {
    val row = slotInCard % TRAY_ROWS_PER_CARD
    val col = slotInCard / TRAY_ROWS_PER_CARD
    return Pair(row, col)
}

/**
 * Generates a printable tray card PDF at [outputFile].
 *
 * Pages are US Letter landscape (792 × 612 pt by default, or the printer's printable area
 * when called from [TrayCardPrintDocumentAdapter]). Each page holds 4 tray card grids of
 * 10 cols × 5 rows stacked vertically, centered on the page. Bold cut lines are drawn at
 * the top edge, bottom edge, and between every card on the page.
 *
 * [cellWidthPt] controls the width of each of the 10 columns in PDF points. Pass the
 * calibrated value from [TrayCardPrintDocumentAdapter] to compensate for device-specific
 * printer scaling. Defaults to [TRAY_CELL_WIDTH_PT] (the reference value).
 *
 * [pageWidthPt] and [pageHeightPt] define the canvas size. Pass the printable area reported
 * by [PrintAttributes] to generate a PDF sized exactly to the printer's usable area,
 * eliminating framework-level fit-to-page scaling.
 */
fun generateTrayCard(
    codes: List<String>,
    outputFile: File,
    pageWidthPt: Float = TRAY_PAGE_WIDTH_PT.toFloat(),
    pageHeightPt: Float = TRAY_PAGE_HEIGHT_PT.toFloat(),
    cellWidthPt: Float = TRAY_CELL_WIDTH_PT,
) {
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
        val cutLinePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        val cardWidthPt = TRAY_COLS * cellWidthPt
        val marginLeft = (pageWidthPt - cardWidthPt) / 2
        val marginTop = (pageHeightPt - TRAY_CARDS_PER_PAGE * TRAY_CARD_HEIGHT_PT) / 2
        val contentRight = marginLeft + cardWidthPt

        for (pageIndex in 0 until numPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                pageWidthPt.roundToInt(),
                pageHeightPt.roundToInt(),
                pageIndex + 1,
            ).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val pageSliceStart = pageIndex * TRAY_SLOTS_PER_PAGE
            val pageSliceEnd = minOf(pageSliceStart + TRAY_SLOTS_PER_PAGE, codes.size)

            // Number of full or partial cards on this page.
            val codesOnPage = pageSliceEnd - pageSliceStart
            val cardsOnPage = (codesOnPage + TRAY_SLOTS_PER_CARD - 1) / TRAY_SLOTS_PER_CARD

            // Render all cells for every card on this page — borders always, text only for
            // occupied slots. This ensures the full 50-slot grid is visible even when the
            // last card on a page is only partially filled.
            val slotsForPage = cardsOnPage * TRAY_SLOTS_PER_CARD
            for (slotIndex in 0 until slotsForPage) {
                val codeIndex = pageSliceStart + slotIndex
                val cardIndex = slotIndex / TRAY_SLOTS_PER_CARD
                val slotInCard = slotIndex % TRAY_SLOTS_PER_CARD
                val (row, col) = slotRowCol(slotInCard)

                val cellLeft = marginLeft + col * cellWidthPt
                val cellTop = marginTop + cardIndex * TRAY_CARD_HEIGHT_PT + row * TRAY_CELL_HEIGHT_PT
                val cellRight = cellLeft + cellWidthPt
                val cellBottom = cellTop + TRAY_CELL_HEIGHT_PT

                canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, borderPaint)

                if (codeIndex < codes.size) {
                    // Centre text vertically within the cell.
                    val textBaseline = cellTop + TRAY_CELL_HEIGHT_PT / 2f +
                        (textPaint.textSize / 2f) - textPaint.descent()
                    val displayCode = truncateToFit(
                        codes[codeIndex],
                        cellWidthPt - 4f, // 2f margin on each side of centered text
                        textPaint::measureText,
                    )
                    canvas.drawText(displayCode, cellLeft + cellWidthPt / 2f, textBaseline, textPaint)
                }
            }

            // Draw bold cut lines last so they paint over the thinner cell border strokes
            // that share the same Y coordinates at each card boundary.
            for (cardIndex in 0..cardsOnPage) {
                val cutY = marginTop + cardIndex * TRAY_CARD_HEIGHT_PT
                canvas.drawLine(marginLeft, cutY, contentRight, cutY, cutLinePaint)
            }

            document.finishPage(page)
        }

        outputFile.outputStream().use { document.writeTo(it) }
    } finally {
        document.close()
    }
}

/**
 * [PrintDocumentAdapter] that renders the tray card grid via [generateTrayCard] and writes
 * the result to the destination provided by the Android print framework.
 *
 * The PDF canvas is sized to the **printable area** reported by [PrintAttributes] in [onLayout],
 * computed as media size minus hardware margins (both in PDF points). This ensures a 1:1 match
 * between the generated PDF and the area the printer can actually use, which eliminates any
 * framework-level fit-to-page scaling.
 *
 * [onWrite] is called on the main thread by the framework; it runs [generateTrayCard]
 * synchronously. For typical inventories the generation is fast enough not to cause ANR;
 * if that changes, move the heavy work to a background thread and call the callback from there.
 */
class TrayCardPrintDocumentAdapter(
    private val context: Context,
    private val codes: List<String>,
    /**
     * The measured width in mm of 10 printed cells from a previous test print on this
     * device. Used to scale [TRAY_CELL_WIDTH_PT] so the output matches [TRAY_TARGET_WIDTH_MM].
     * Default = [TRAY_TARGET_WIDTH_MM] (235 mm), meaning no scale adjustment is applied.
     */
    private val calibrationMm: Float = TRAY_TARGET_WIDTH_MM,
) : PrintDocumentAdapter() {

    // Populated in onLayout, read in onWrite. Both callbacks are invoked on the main thread
    // so no synchronization is needed; the framework guarantees onLayout precedes onWrite.
    private var currentAttributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        // Return changed=true if the printable area actually changed so the framework
        // re-requests onWrite with a correctly sized PDF. Color/resolution/duplex don't
        // affect PDF content and are intentionally excluded from this check.
        val changed = currentAttributes?.let { old ->
            old.mediaSize != newAttributes.mediaSize || old.minMargins != newAttributes.minMargins
        } ?: true
        currentAttributes = newAttributes
        val info = PrintDocumentInfo.Builder("tray-card.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount(codes.size).coerceAtLeast(1))
            .build()
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val tempFile = File(context.cacheDir, "tray-card-print-${System.nanoTime()}.pdf")
        try {
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return
            }
            val (pageWidthPt, pageHeightPt) = currentAttributes.printableAreaPt()
            val calibratedCellWidthPt = TRAY_CELL_WIDTH_PT * (TRAY_TARGET_WIDTH_MM / calibrationMm)
            generateTrayCard(codes, tempFile, pageWidthPt, pageHeightPt, calibratedCellWidthPt)
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return
            }
            FileOutputStream(destination.fileDescriptor).use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            tempFile.delete()
        }
    }
}

/**
 * Computes the printable area in PDF points from [PrintAttributes].
 *
 * Printable area = media size − hardware margins. Both are expressed in mils
 * (thousandths of an inch) by the Android API; converting to PDF points requires
 * multiplying by 72/1000. Falls back to US Letter landscape with no margins when
 * the receiver is null (should only happen if onWrite is somehow called before onLayout).
 */
private fun PrintAttributes?.printableAreaPt(): Pair<Float, Float> {
    val media = this?.mediaSize ?: PrintAttributes.MediaSize.NA_LETTER.asLandscape()
    val margins = this?.minMargins ?: PrintAttributes.Margins.NO_MARGINS
    // Android Margins fields are in portrait-paper-space (never rotated). For landscape
    // media (widthMils > heightMils), left/right margins bound the short (height) axis
    // and top/bottom margins bound the long (width) axis.
    return if (media.widthMils > media.heightMils) {
        val w = (media.widthMils  - margins.topMils   - margins.bottomMils) * 72f / 1000f
        val h = (media.heightMils - margins.leftMils  - margins.rightMils)  * 72f / 1000f
        w to h
    } else {
        val w = (media.widthMils  - margins.leftMils  - margins.rightMils)  * 72f / 1000f
        val h = (media.heightMils - margins.topMils   - margins.bottomMils) * 72f / 1000f
        w to h
    }
}

/**
 * Returns the palette letter for a given slot index (0–49) using a single-then-double-letter
 * scheme: 0–25 → A–Z, 26–49 → AA–AX.
 *
 * slotIndex 0 → "A", 25 → "Z", 26 → "AA", 49 → "AX".
 */
internal fun paletteLabel(index: Int): String =
    if (index < 26) ('A' + index).toString()
    else "A" + ('A' + (index - 26))

/**
 * Generates a single-page project card PDF at [outputFile].
 *
 * The page is US Letter landscape (792 × 612 pt by default, or the printer's printable area
 * when called from [ProjectCardPrintDocumentAdapter]). The card is a single 10×5 grid of
 * 50 slots centered on the page, each labeled with its palette letter (A–AX) in bold centered
 * text using south-then-east ordering (via [slotRowCol]). Bold cut lines are drawn at the
 * top and bottom edges of the card.
 *
 * [cellWidthPt] and calibration semantics are identical to [generateTrayCard].
 */
fun generateProjectCard(
    outputFile: File,
    pageWidthPt: Float = TRAY_PAGE_WIDTH_PT.toFloat(),
    pageHeightPt: Float = TRAY_PAGE_HEIGHT_PT.toFloat(),
    cellWidthPt: Float = TRAY_CELL_WIDTH_PT,
) {
    val document = PdfDocument()
    try {
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
        val cutLinePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        val cardWidthPt = TRAY_COLS * cellWidthPt
        val marginLeft = (pageWidthPt - cardWidthPt) / 2
        val marginTop = (pageHeightPt - TRAY_CARD_HEIGHT_PT) / 2
        val contentRight = marginLeft + cardWidthPt

        val pageInfo = PdfDocument.PageInfo.Builder(
            pageWidthPt.roundToInt(),
            pageHeightPt.roundToInt(),
            1,
        ).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        for (slotIndex in 0 until TRAY_SLOTS_PER_CARD) {
            val (row, col) = slotRowCol(slotIndex)
            val cellLeft = marginLeft + col * cellWidthPt
            val cellTop = marginTop + row * TRAY_CELL_HEIGHT_PT
            val cellRight = cellLeft + cellWidthPt
            val cellBottom = cellTop + TRAY_CELL_HEIGHT_PT

            canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, borderPaint)

            val label = paletteLabel(slotIndex)
            val textBaseline = cellTop + TRAY_CELL_HEIGHT_PT / 2f +
                (textPaint.textSize / 2f) - textPaint.descent()
            canvas.drawText(label, cellLeft + cellWidthPt / 2f, textBaseline, textPaint)
        }

        // Draw bold cut lines last so they paint over the thinner cell border strokes
        // that share the same Y coordinates at the card's top and bottom edges.
        canvas.drawLine(marginLeft, marginTop, contentRight, marginTop, cutLinePaint)
        canvas.drawLine(
            marginLeft,
            marginTop + TRAY_CARD_HEIGHT_PT,
            contentRight,
            marginTop + TRAY_CARD_HEIGHT_PT,
            cutLinePaint,
        )

        document.finishPage(page)
        outputFile.outputStream().use { document.writeTo(it) }
    } finally {
        document.close()
    }
}

/**
 * [PrintDocumentAdapter] that renders the project card grid via [generateProjectCard] and
 * writes the result to the destination provided by the Android print framework.
 *
 * Printable-area sizing, calibration, and temp-file lifecycle follow the same conventions
 * as [TrayCardPrintDocumentAdapter]. The page count is always 1.
 */
class ProjectCardPrintDocumentAdapter(
    private val context: Context,
    private val calibrationMm: Float = TRAY_TARGET_WIDTH_MM,
) : PrintDocumentAdapter() {

    private var currentAttributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val changed = currentAttributes?.let { old ->
            old.mediaSize != newAttributes.mediaSize || old.minMargins != newAttributes.minMargins
        } ?: true
        currentAttributes = newAttributes
        val info = PrintDocumentInfo.Builder("project-card.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val tempFile = File(context.cacheDir, "project-card-print-${System.nanoTime()}.pdf")
        try {
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return
            }
            val (pageWidthPt, pageHeightPt) = currentAttributes.printableAreaPt()
            val calibratedCellWidthPt = TRAY_CELL_WIDTH_PT * (TRAY_TARGET_WIDTH_MM / calibrationMm)
            generateProjectCard(tempFile, pageWidthPt, pageHeightPt, calibratedCellWidthPt)
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return
            }
            FileOutputStream(destination.fileDescriptor).use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            tempFile.delete()
        }
    }
}
