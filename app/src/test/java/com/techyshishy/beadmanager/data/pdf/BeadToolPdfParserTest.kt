package com.techyshishy.beadmanager.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeadToolPdfParserTest {

    private val parser = BeadToolPdfParser()

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Wraps [wordChartRows] in just enough surrounding text to simulate what
     * [extractPdfText] would return for a minimal two-section BeadTool 4 PDF.
     *
     * Page 0: title
     * Page 1: word chart with per-page footer
     */
    private fun minimalPages(
        title: String = "Test Pattern",
        wordChartRows: String,
    ): List<String> = listOf(
        "$title\nCreated by Some Artist\n",
        "Word Chart (Peyote)\n$wordChartRows\nTest Pattern  Page 1 of 1\nCreated with BeadTool 4 - www.beadtool.net\n",
    )

    // ── name extraction ───────────────────────────────────────────────────────

    @Test
    fun `parse extracts pattern name from first non-blank line of first page`() {
        val pages = minimalPages(
            title = "Dragon's Wrath-Peyote",
            wordChartRows = "Row 1&2 (L) (3)A\nRow 3 (R) (2)B",
        )
        val result = parser.parse(pages)
        assertEquals("Dragon's Wrath-Peyote", result.name)
    }

    // ── happy path — paired rows ──────────────────────────────────────────────

    @Test
    fun `parse emits two PdfRows for a paired Row 1andersand2 line`() {
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)A, (2)B\nRow 3 (R) (1)C",
        )
        val result = parser.parse(pages)

        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(result.rows[0].steps, result.rows[1].steps) // row 2 is a copy of row 1
        assertEquals(2, result.rows[1].id)
        assertEquals(3, result.rows[2].id)
    }

    @Test
    fun `parse correctly parses steps from a paired row`() {
        val pages = minimalPages(wordChartRows = "Row 1&2 (L) (3)A, (2)B\nRow 3 (R) (1)C")
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(2, steps.size)
        assertEquals(PdfStep(count = 3, colorLetter = "A"), steps[0])
        assertEquals(PdfStep(count = 2, colorLetter = "B"), steps[1])
    }

    // ── happy path — single rows ──────────────────────────────────────────────

    @Test
    fun `parse handles single-row fallback when no Row 1and2 is present`() {
        val pages = minimalPages(
            wordChartRows = "Row 1 (L) (5)A\nRow 2 (R) (5)B",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(2, result.rows[1].id)
    }

    // ── multi-letter color codes ──────────────────────────────────────────────

    @Test
    fun `parse handles two-letter color codes`() {
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)AD, (2)AF, (1)AA\nRow 3 (R) (6)AB",
        )
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(3, steps.size)
        assertEquals("AD", steps[0].colorLetter)
        assertEquals("AF", steps[1].colorLetter)
        assertEquals("AA", steps[2].colorLetter)
    }

    // ── continuation lines ────────────────────────────────────────────────────

    @Test
    fun `parse joins continuation lines split at a trailing comma`() {
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)A,\n(2)B\nRow 3 (R) (1)C",
        )
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(2, steps.size)
        assertEquals(PdfStep(3, "A"), steps[0])
        assertEquals(PdfStep(2, "B"), steps[1])
    }

    @Test
    fun `parse joins continuation lines that start directly with a step token`() {
        // No trailing comma — the line break falls between two step tokens
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)A\n(2)B\nRow 3 (R) (1)C",
        )
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(2, steps.size)
        assertEquals(PdfStep(3, "A"), steps[0])
        assertEquals(PdfStep(2, "B"), steps[1])
    }

    // ── header/footer stripping ───────────────────────────────────────────────

    @Test
    fun `parse strips Created with BeadTool 4 footers`() {
        // The footer must not end up in a row line; the pattern should still parse.
        val pages = listOf(
            "My Pattern\n",
            "Row 1 (L) (2)A\nMy Pattern  Page 1 of 1\nCreated with BeadTool 4 - www.beadtool.net\n",
            "Row 2 (R) (2)B\nMy Pattern  Page 2 of 1\nCreated with BeadTool 4 - www.beadtool.net\n",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
    }

    // ── color mapping is always empty ─────────────────────────────────────────

    @Test
    fun `parse always returns empty colorMapping`() {
        val pages = minimalPages(wordChartRows = "Row 1&2 (L) (1)A\nRow 3 (R) (1)B")
        val result = parser.parse(pages)
        assertTrue(result.colorMapping.isEmpty())
    }

    // ── error cases ───────────────────────────────────────────────────────────

    @Test
    fun `parse throws NoPatternFound when pages contain no row notation`() {
        val pages = listOf(
            "My Pattern\nCreated by Artist\n",
            "Some intro text, no rows here.\n",
        )
        val result = runCatching { parser.parse(pages) }
        assertTrue(
            "Expected NoPatternFound but got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is PdfParseException.NoPatternFound,
        )
    }

    @Test
    fun `parse throws NoPatternFound for empty page list`() {
        val result = runCatching { parser.parse(emptyList()) }
        assertTrue(result.exceptionOrNull() is PdfParseException.NoPatternFound)
    }
}
