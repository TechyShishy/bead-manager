package com.techyshishy.beadmanager.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsmPdfParserTest {

    private val parser = XlsmPdfParser()

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal two-page list simulating a real XLSM PDF:
     *  - page 0: title + color key table
     *  - page 1: Word Cart/Chart section with pattern rows, terminated by Grid
     */
    private fun minimalPages(
        title: String = "Test Pattern",
        colorEntries: String = " DB0001 A Color A\n DB0002 B Color B\n",
        wordChartRows: String,
        useWordChart: Boolean = false,
    ): List<String> {
        val header = if (useWordChart) "Word Chart" else "Word Cart"
        return listOf(
            "$title\n$colorEntries",
            "$header\nRow Direction Word Chart\n$wordChartRows\nGrid\n",
        )
    }

    // ── single-row format ─────────────────────────────────────────────────────

    @Test
    fun `parse handles single-row format`() {
        val pages = minimalPages(wordChartRows = "3 R 1(A) 3(B)\n")
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        val row = result.rows[0]
        assertEquals(3, row.id)
        assertEquals(listOf(PdfStep(1, "A"), PdfStep(3, "B")), row.steps)
    }

    // ── range-row format ──────────────────────────────────────────────────────

    @Test
    fun `parse emits two PdfRows for a range row with the same steps`() {
        val pages = minimalPages(wordChartRows = "1 & 2 L 3(A) 1(B)\n3 R 2(A)\n")
        val result = parser.parse(pages)
        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(2, result.rows[1].id)
        assertEquals(result.rows[0].steps, result.rows[1].steps)
        assertEquals(3, result.rows[2].id)
    }

    @Test
    fun `parse correctly maps steps from a range row`() {
        val pages = minimalPages(wordChartRows = "1 & 2 L 3(A) 1(B)\n3 R 2(A)\n")
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(listOf(PdfStep(3, "A"), PdfStep(1, "B")), steps)
    }

    // ── header detection ──────────────────────────────────────────────────────

    @Test
    fun `parse accepts misspelled Word Cart header`() {
        val pages = listOf(
            "Pattern\n DB0001 A Color A\n",
            "Word Cart\nRow Direction Word Chart\n1 R 2(A)\n",
        )
        assertEquals(1, parser.parse(pages).rows.size)
    }

    @Test
    fun `parse accepts correctly spelled Word Chart header`() {
        val pages = minimalPages(wordChartRows = "1 R 2(A)\n", useWordChart = true)
        assertEquals(1, parser.parse(pages).rows.size)
    }

    // ── Grid section stop ─────────────────────────────────────────────────────

    @Test
    fun `parse stops at Grid section header`() {
        val pages = listOf(
            "Pattern\n DB0001 A Color A\n",
            "Word Cart\nRow Direction Word Chart\n1 R 2(A)\n2 R 1(A)\nGrid\n3 R 1(A)\n",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(1, 2), result.rows.map { it.id })
    }

    // ── continuation lines ────────────────────────────────────────────────────

    @Test
    fun `parse merges continuation lines into the preceding row`() {
        val pages = listOf(
            "Pattern\n DB0001 A Color A\n DB0002 B Color B\n",
            "Word Cart\nRow Direction Word Chart\n1 R 3(A)\n2(B)\n2 R 1(A)\n",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(PdfStep(3, "A"), PdfStep(2, "B")), result.rows[0].steps)
    }

    @Test
    fun `parse merges multiple continuation lines`() {
        val pages = listOf(
            "Pattern\n DB0001 A Color A\n DB0002 B Color B\n",
            "Word Cart\nRow Direction Word Chart\n1 R 3(A)\n2(B)\n1(A)\n2 R 1(A)\n",
        )
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(listOf(PdfStep(3, "A"), PdfStep(2, "B"), PdfStep(1, "A")), steps)
    }

    @Test
    fun `parse merges continuation lines into a range row`() {
        val pages = listOf(
            "Pattern\n DB0001 A Color A\n DB0002 B Color B\n",
            "Word Cart\nRow Direction Word Chart\n1 & 2 L 3(A)\n2(B)\n3 R 1(A)\n",
        )
        val result = parser.parse(pages)
        assertEquals(3, result.rows.size)
        // Both rows 1 and 2 should include the continuation step
        assertEquals(listOf(PdfStep(3, "A"), PdfStep(2, "B")), result.rows[0].steps)
        assertEquals(result.rows[0].steps, result.rows[1].steps)
    }

    // ── XLSM notation conversion ──────────────────────────────────────────────

    @Test
    fun `parse converts XLSM count(color) notation to PdfSteps`() {
        val pages = minimalPages(
            colorEntries = " DB0001 A Color A\n",
            wordChartRows = "1 R 52(A)\n",
        )
        assertEquals(listOf(PdfStep(52, "A")), parser.parse(pages).rows[0].steps)
    }

    @Test
    fun `parse accepts peyote-shorthand notation as pass-through`() {
        val pages = minimalPages(
            wordChartRows = "1 R (3)A (2)B\n",
        )
        assertEquals(listOf(PdfStep(3, "A"), PdfStep(2, "B")), parser.parse(pages).rows[0].steps)
    }

    // ── color mapping extraction ──────────────────────────────────────────────

    @Test
    fun `parse extracts color mapping from DB code entries`() {
        val pages = minimalPages(
            colorEntries = " DB0005 A Deep Blue:MET IRIS   65\n DB0050 B Clear:TR LSTR   6\n",
            wordChartRows = "1 R 1(A) 1(B)\n",
        )
        val mapping = parser.parse(pages).colorMapping
        assertEquals("DB-0005", mapping["A"])
        assertEquals("DB-0050", mapping["B"])
    }

    @Test
    fun `parse normalizes DB code by inserting hyphen`() {
        val pages = minimalPages(
            colorEntries = " DB1270 J Azure:TR MAT   788\n",
            wordChartRows = "1 R 1(J)\n",
        )
        assertEquals("DB-1270", parser.parse(pages).colorMapping["J"])
    }

    // ── error cases ───────────────────────────────────────────────────────────

    @Test
    fun `parse throws NoPatternFound when no Word Chart section exists`() {
        // Input contains no "word chart" or "word cart" heading at all.
        val pages = listOf("Cover page only.\n DB0001 A Color A\n")
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

    @Test
    fun `parse throws IncompleteColorMapping when row references unmapped letter`() {
        val pages = listOf(
            "Pattern\n DB0001 A Color A\n",
            "Word Cart\nRow Direction Word Chart\n1 R 1(A) 1(Z)\n",
        )
        val result = runCatching { parser.parse(pages) }
        val ex = result.exceptionOrNull()
        assertTrue(
            "Expected IncompleteColorMapping but got $ex",
            ex is PdfParseException.IncompleteColorMapping,
        )
        assertEquals(listOf("Z"), (ex as PdfParseException.IncompleteColorMapping).missingLetters)
    }

    // ── real-world structure (Dog.pdf simulation) ─────────────────────────────

    @Test
    fun `parse handles multi-page Word Cart structure with page footers`() {
        // Simulates the Dog.pdf structure: two Word Cart pages, page footer artifacts,
        // and the color key on a separate Information page.
        val infoPage = "AZ Art Jewelry\n DB0005 A Deep Blue\n DB0068 C Orange Lt\n DB1270 J Azure\n"
        val wordCartPage1 = "Word Cart\nRow Direction Word Chart\n1 & 2 L 4(J)\n3 R 3(J)\n316039052005.xlsm\n"
        val wordCartPage2 = "Word Cart\nRow Direction Word Chart\n4 L 2(J) 1(C) 1(A)\n516039052005.xlsm\n"
        val gridPage = "Grid\nJ J J J\n"
        val pages = listOf(infoPage, wordCartPage1, wordCartPage2, gridPage)

        val result = parser.parse(pages)

        assertEquals(4, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(2, result.rows[1].id)
        assertEquals(3, result.rows[2].id)
        assertEquals(4, result.rows[3].id)
        assertEquals(listOf(PdfStep(4, "J")), result.rows[0].steps)
        assertEquals(result.rows[0].steps, result.rows[1].steps)
        assertEquals(listOf(PdfStep(2, "J"), PdfStep(1, "C"), PdfStep(1, "A")), result.rows[3].steps)
        assertEquals("DB-0005", result.colorMapping["A"])
        assertEquals("DB-0068", result.colorMapping["C"])
        assertEquals("DB-1270", result.colorMapping["J"])
    }
}
