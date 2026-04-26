package com.techyshishy.beadmanager.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class TrayCardGeneratorTest {

    @Test
    fun `pageCount returns 0 for empty list`() {
        assertEquals(0, pageCount(0))
    }

    @Test
    fun `pageCount returns 1 for a single code`() {
        assertEquals(1, pageCount(1))
    }

    @Test
    fun `pageCount returns 1 for exactly 200 codes`() {
        assertEquals(1, pageCount(TRAY_SLOTS_PER_PAGE))
    }

    @Test
    fun `pageCount returns 2 for 201 codes`() {
        assertEquals(2, pageCount(TRAY_SLOTS_PER_PAGE + 1))
    }

    @Test
    fun `pageCount returns 2 for 400 codes`() {
        assertEquals(2, pageCount(2 * TRAY_SLOTS_PER_PAGE))
    }

    @Test
    fun `pageCount returns 3 for 401 codes`() {
        assertEquals(3, pageCount(401))
    }

    @Test
    fun `truncateToFit returns original text when it fits`() {
        val result = truncateToFit("DB1851F", 63f) { text -> text.length.toFloat() }
        assertEquals("DB1851F", result)
    }

    @Test
    fun `truncateToFit returns original text when it fits exactly`() {
        // Text measuring exactly maxWidth should pass through unchanged (≤, not <).
        val result = truncateToFit("ABCDE", 50f) { _ -> 50f }
        assertEquals("ABCDE", result)
    }

    @Test
    fun `truncateToFit truncates and appends ellipsis when text is too wide`() {
        // Each char measures 10f. 26 chars = 260f, which exceeds 63f.
        // "ABCDE\u2026" = 6 chars × 10f = 60f ≤ 63f; "ABCDEF\u2026" = 70f > 63f.
        val result = truncateToFit("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 63f) { text -> text.length * 10f }
        assertEquals("ABCDE\u2026", result)
    }

    @Test
    fun `truncateToFit returns only ellipsis when nothing fits alongside it`() {
        // Each char = 100f, maxWidth = 10f; even a single char + ellipsis overflows.
        val result = truncateToFit("A", 10f) { text -> text.length * 100f }
        assertEquals("\u2026", result)
    }

    // ---- Print-dimension sanity ----

    @Test
    fun `TRAY_CARD_WIDTH_PT matches 10 cells at the declared cell width`() {
        assertEquals(TRAY_COLS * TRAY_CELL_WIDTH_PT, TRAY_CARD_WIDTH_PT, 0.01f)
    }

    @Test
    fun `TRAY_PAGE_MARGIN_LEFT_PT centers the card on a Letter-landscape page`() {
        // Both side margins must be equal and non-negative.
        val rightMargin = TRAY_PAGE_WIDTH_PT - TRAY_CARD_WIDTH_PT - TRAY_PAGE_MARGIN_LEFT_PT
        assertEquals(TRAY_PAGE_MARGIN_LEFT_PT, rightMargin, 0.01f)
    }

    // ---- Slot ordering (south-then-east) ----

    @Test
    fun `slotRowCol slot 0 maps to row 0 col 0`() {
        assertEquals(Pair(0, 0), slotRowCol(0))
    }

    @Test
    fun `slotRowCol slot 1 maps to row 1 col 0`() {
        assertEquals(Pair(1, 0), slotRowCol(1))
    }

    @Test
    fun `slotRowCol slot 4 maps to row 4 col 0 — last slot of first column`() {
        assertEquals(Pair(4, 0), slotRowCol(4))
    }

    @Test
    fun `slotRowCol slot 5 maps to row 0 col 1 — first slot of second column`() {
        assertEquals(Pair(0, 1), slotRowCol(5))
    }

    @Test
    fun `slotRowCol slot 49 maps to row 4 col 9 — last slot of card`() {
        assertEquals(Pair(4, 9), slotRowCol(49))
    }

    // ---- Palette label generation ----

    @Test
    fun `paletteLabel index 0 is A`() {
        assertEquals("A", paletteLabel(0))
    }

    @Test
    fun `paletteLabel index 25 is Z`() {
        assertEquals("Z", paletteLabel(25))
    }

    @Test
    fun `paletteLabel index 26 is AA`() {
        assertEquals("AA", paletteLabel(26))
    }

    @Test
    fun `paletteLabel index 49 is AX`() {
        assertEquals("AX", paletteLabel(49))
    }

    @Test
    fun `paletteLabel generates 50 distinct labels from A to AX`() {
        val labels = (0 until TRAY_SLOTS_PER_CARD).map { paletteLabel(it) }
        assertEquals(50, labels.distinct().size)
        assertEquals("A", labels.first())
        assertEquals("AX", labels.last())
    }
}
