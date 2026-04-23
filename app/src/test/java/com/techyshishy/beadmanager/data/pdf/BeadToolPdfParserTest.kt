package com.techyshishy.beadmanager.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ── happy path — paired rows ──────────────────────────────────────────────

    @Test
    fun `parse emits two PdfRows for a paired Row 1andersand2 line`() {
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)A, (2)B\nRow 3 (R) (1)C",
        )
        val result = parser.parse(pages)

        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertNotEquals(result.rows[0].steps, result.rows[1].steps)
        assertEquals(2, result.rows[1].id)
        assertEquals(3, result.rows[2].id)
    }

    @Test
    fun `parse correctly parses steps from a paired row`() {
        val pages = minimalPages(wordChartRows = "Row 1&2 (L) (3)A, (2)B\nRow 3 (R) (1)C")
        val result = parser.parse(pages)
        // Buffer: A A A B B (5 beads, indices 0-4)
        // Row 1 (odd indices [1,3]): A, B → reversed for storage → B, A
        //   → [PdfStep(1,"B"), PdfStep(1,"A")]
        // Row 2 (even indices [0,2,4]): A, A, B → natural order
        //   → [PdfStep(2,"A"), PdfStep(1,"B")]
        // Row 1 is reversed because it ends up at even list index 0; the renderer
        // does not reverse even-index rows, so pre-reversing here produces the
        // correct display direction. Row 2 is stored natural so the renderer's
        // odd-row reversal produces the correct result.
        val row1Steps = result.rows[0].steps
        val row2Steps = result.rows[1].steps
        assertEquals(2, row1Steps.size)
        assertEquals(PdfStep(count = 1, colorLetter = "B"), row1Steps[0])
        assertEquals(PdfStep(count = 1, colorLetter = "A"), row1Steps[1])
        assertEquals(listOf(PdfStep(2, "A"), PdfStep(1, "B")), row2Steps)
    }

    @Test
    fun `parse handles double-space separator between direction and first step (paired-row)`() {
        // BeadTool 4 PDFs use two spaces: "Row 1&2 (L)  (23)D, ..."
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L)  (3)A, (2)B\nRow 3 (R)  (1)C",
        )
        val result = parser.parse(pages)
        assertEquals(3, result.rows.size)
        // Row 1 odd-indexed beads from (3)A,(2)B buffer: indices 1,3 → [A,B]; reversed → B first
        assertEquals(PdfStep(1, "B"), result.rows[0].steps[0])
    }

    @Test
    fun `parseRows detangles paired-row buffer into distinct row1 and row2 step sequences`() {
        // Buffer: E D F C G B H A (8 individually counted beads, interleaved)
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (1)E, (1)D, (1)F, (1)C, (1)G, (1)B, (1)H, (1)A\nRow 3 (R) (1)X",
        )
        val result = parser.parse(pages)
        val row1 = result.rows[0]
        val row2 = result.rows[1]
        assertEquals(1, row1.id)
        assertEquals(2, row2.id)
        // Buffer: E D F C G B H A (8 individually counted beads, interleaved)
        // Odd  indices [1,3,5,7] = D,C,B,A → Row 1; reversed for storage → A,B,C,D
        // Even indices [0,2,4,6] = E,F,G,H → Row 2; natural order
        assertEquals(
            listOf(PdfStep(1, "A"), PdfStep(1, "B"), PdfStep(1, "C"), PdfStep(1, "D")),
            row1.steps,
        )
        assertEquals(
            listOf(PdfStep(1, "E"), PdfStep(1, "F"), PdfStep(1, "G"), PdfStep(1, "H")),
            row2.steps,
        )
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

    @Test
    fun `parse handles double-space separator between direction and first step (single-row)`() {
        // BeadTool 4 PDFs use two spaces: "Row 1 (L)  (23)D, ..."
        val pages = minimalPages(
            wordChartRows = "Row 1 (L)  (5)A\nRow 2 (R)  (5)B",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
        assertEquals(PdfStep(5, "A"), result.rows[0].steps[0])
    }

    // ── multi-letter color codes ──────────────────────────────────────────────

    @Test
    fun `parse handles two-letter color codes`() {
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)AD, (2)AF, (1)AA\nRow 3 (R) (6)AB",
        )
        // Row 1 odd-indexed beads from [AD,AD,AD,AF,AF,AA]: indices 1,3,5 → [AD,AF,AA];
        // reversed → [AA,AF,AD] → RLE: [PdfStep(1,"AA"), PdfStep(1,"AF"), PdfStep(1,"AD")]
        val steps = parser.parse(pages).rows[0].steps
        assertEquals(3, steps.size)
        assertEquals("AA", steps[0].colorLetter)
        assertEquals("AF", steps[1].colorLetter)
        assertEquals("AD", steps[2].colorLetter)
    }

    // ── continuation lines ────────────────────────────────────────────────────

    @Test
    fun `parse joins continuation lines split at a trailing comma`() {
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)A,\n(2)B\nRow 3 (R) (1)C",
        )
        val steps = parser.parse(pages).rows[0].steps
        // Row 1 odd-indexed beads: indices 1,3 → [A,B]; reversed for storage → [B,A]
        assertEquals(2, steps.size)
        assertEquals(PdfStep(1, "B"), steps[0])
        assertEquals(PdfStep(1, "A"), steps[1])
    }

    @Test
    fun `parse joins continuation lines that start directly with a step token`() {
        // No trailing comma — the line break falls between two step tokens
        val pages = minimalPages(
            wordChartRows = "Row 1&2 (L) (3)A\n(2)B\nRow 3 (R) (1)C",
        )
        val steps = parser.parse(pages).rows[0].steps
        // Row 1 odd-indexed beads: indices 1,3 → [A,B]; reversed for storage → [B,A]
        assertEquals(2, steps.size)
        assertEquals(PdfStep(1, "B"), steps[0])
        assertEquals(PdfStep(1, "A"), steps[1])
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

    // ── form feed page breaks ─────────────────────────────────────────────────

    @Test
    fun `parse extracts all rows across a form feed page boundary (loom single-row)`() {
        // pdfbox inserts \u000C at every page boundary; rows after page breaks
        // must still be found, not silently truncated.
        val pages = listOf(
            "Test Pattern\n",
            "Row 1 (L) (3)A\u000C\nRow 2 (R) (2)B\nTest Pattern  Page 1 of 1\nCreated with BeadTool 4 - www.beadtool.net\n",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(2, result.rows[1].id)
    }

    @Test
    fun `parse extracts all rows across a form feed page boundary (peyote paired-row)`() {
        val pages = listOf(
            "Test Pattern\n",
            "Row 1&2 (L) (3)A\u000C\nRow 3 (R) (2)B\nTest Pattern  Page 1 of 1\nCreated with BeadTool 4 - www.beadtool.net\n",
        )
        val result = parser.parse(pages)
        // Row 1&2 emits two PdfRows (id 1 and id 2), then Row 3
        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(2, result.rows[1].id)
        assertEquals(3, result.rows[2].id)
    }

    @Test
    fun `parse extracts rows separated by a bare form-feed line (double newline after strip)`() {
        // Models pdfbox placing \u000C as a standalone page-separator: after
        // pages.joinToString("\n") and \u000C removal, two bare newlines remain
        // between rows — must not prevent row extraction.
        val pages = listOf(
            "Test Pattern\n",
            "Row 1 (L) (3)A\n\u000C\nRow 2 (R) (2)B\nTest Pattern  Page 1 of 1\nCreated with BeadTool 4 - www.beadtool.net\n",
        )
        val result = parser.parse(pages)
        assertEquals(2, result.rows.size)
        assertEquals(1, result.rows[0].id)
        assertEquals(2, result.rows[1].id)
    }

    // ── non-standard layout (third-party "Bead with Bugs" format) ────────────

    @Test
    fun `parse extracts rows interleaved across two columns separated by blank space lines`() {
        // Mirrors the AbsentFlowers "Bead with Bugs" format:
        //  - Rows appear out of text order due to two-column layout: PDFBox
        //    extracts the left column (rows 1&2, 3, 6) before the right column
        //    (rows 4, 5) on the same page.
        //  - Adjacent rows are separated by space-only blank lines ("\n \n"),
        //    which the old contiguous-block regex could not bridge.
        //  - A custom per-page header ("Designed by / Email") appears before
        //    the first row on each page and is not removed by stripPageHeaders.
        val page2 = buildString {
            append("Absent Flowers\n")
            append("All rights reserved to beadwithbugs.com. Page 2 of 4\n")
            append("Designed by:  Kim Herron          Email:  author@example.com\n")
            append("Row 1&2 (L)  (1)A, (5)C\n")
            append(" \n")
            append("Row 3 (R)  (2)B\n")
            append(" \n")
            append("Row 6 (L)  (3)A\n")  // right-column row interspersed before 4 & 5
            append(" \n")
            append("Row 4 (L)  (2)C\n")
            append(" \n")
            append("Row 5 (R)  (1)A, (1)B\n")
        }
        val pages = listOf("Absent Flowers\nDesigned by:  Kim Herron\n", page2)

        val result = parser.parse(pages)

        // Row 1&2 emits rows 1 and 2; rows 3–6 are sorted by number regardless
        // of their position in the extracted text.
        assertEquals(6, result.rows.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), result.rows.map { it.id })
    }

    @Test
    fun `parse joins comma-wrapped continuation lines separated by space-only blank lines (AbsentFlowers combined format)`() {
        // AbsentFlowers "Bead with Bugs" PDFs combine both layout challenges at once:
        //  1. Each row's step list may span multiple text lines, with a trailing comma
        //     signalling that the next line is a continuation (not a new row).
        //  2. Adjacent rows are separated by space-only blank lines ("\n \n"), which
        //     the old contiguous-block regex treated as a hard block boundary.
        // joinContinuationLines must stitch the comma-trailing lines before
        // extractRowBlock runs, and extractRowBlock must bridge the space-only gaps.
        val page = buildString {
            append("Absent Flowers\n")
            append("All rights reserved to beadwithbugs.com. Page 3 of 6\n")
            append("Designed by:  Kim Herron          Email:  author@example.com\n")
            append("Row 1&2 (L)  (1)A, (2)C, (1)B,\n")   // trailing comma → continuation
            append("(1)C, (1)A\n")                          // continuation for Row 1&2
            append(" \n")                                    // space-only blank separator
            append("Row 3 (R)  (2)B, (1)A,\n")             // trailing comma → continuation
            append("(3)C\n")                                 // continuation for Row 3
            append(" \n")
            append("Row 4 (L)  (2)C\n")
        }
        val pages = listOf("Absent Flowers\nDesigned by:  Kim Herron\n", page)

        val result = parser.parse(pages)

        // Row 1&2 emits two PdfRows (ids 1 and 2); rows 3–4 follow in order.
        assertEquals(4, result.rows.size)
        assertEquals(listOf(1, 2, 3, 4), result.rows.map { it.id })

        // Row 1&2: joined continuation "(1)A, (2)C, (1)B, (1)C, (1)A"
        // Flat buffer: A C C B C A (indices 0–5)
        // Row 1 (odd indices [1,3,5]): C,B,A → reversed → A,B,C
        assertEquals(listOf(PdfStep(1, "A"), PdfStep(1, "B"), PdfStep(1, "C")), result.rows[0].steps)
        // Row 2 (even indices [0,2,4]): A,C,C → natural order
        assertEquals(listOf(PdfStep(1, "A"), PdfStep(2, "C")), result.rows[1].steps)

        // Row 3: joined continuation "(2)B, (1)A, (3)C" → steps B B A C C C
        assertEquals(listOf(PdfStep(2, "B"), PdfStep(1, "A"), PdfStep(3, "C")), result.rows[2].steps)
        // Row 4: no continuation needed → steps C C
        assertEquals(listOf(PdfStep(2, "C")), result.rows[3].steps)
    }

    // ── dual-chart PDFs (loom + peyote) ──────────────────────────────────────

    @Test
    fun `parse imports only peyote rows when PDF contains both loom and peyote word chart sections`() {
        // Models a BeadTool 4 PDF that exports both a Loom Word Chart (single rows,
        // full pattern width) and a Peyote Word Chart (paired rows, half pattern width).
        // The two sections both use "Row N (L/R)" notation. Without section isolation,
        // the parser merges all row lines and emits 5 rows (2 loom + 3 peyote) with
        // mismatched widths. Only the 3 peyote rows should be produced.
        val pages = listOf(
            "Carnival Mask\n",
            buildString {
                append("Word Chart (Loom)\n")
                append("Row 1 (L) (5)A\n")
                append("Row 2 (R) (5)B\n")
                append("Carnival Mask  Page 20 of 87\n")
                append("Created with BeadTool 4 - www.beadtool.net\n")
            },
            buildString {
                append("Word Chart (Peyote)\n")
                append("Row 1&2 (L) (3)A, (2)B\n")
                append("Row 3 (R) (2)C\n")
                append("Carnival Mask  Page 58 of 87\n")
                append("Created with BeadTool 4 - www.beadtool.net\n")
            },
        )
        val result = parser.parse(pages)
        // Row 1&2 → 2 buffer rows (ids 1 and 2), Row 3 → 1 buffer row (id 3).
        // The loom rows (ids 1 and 2, full-width) must be discarded entirely.
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it.id })
        // Verify step content comes from the peyote section, not the loom section.
        // Peyote "Row 1&2 (L) (3)A, (2)B": 5-bead buffer split across rows 1 and 2.
        // Loom "Row 1 (L) (5)A" would be a single 5-bead step; that must not appear here.
        val peyoteBuffer = result.rows[0].steps.sumOf { it.count } + result.rows[1].steps.sumOf { it.count }
        assertEquals("rows 1+2 should total the 5-bead peyote buffer, not the 5-bead loom row", 5, peyoteBuffer)
        assertEquals("Row 1 must not be a single (5)A loom step", false, result.rows[0].steps == listOf(PdfStep(5, "A")))
    }

    // ── whitespace-variant step tokens (#88) ─────────────────────────────────

    @Test
    fun `parse joins continuation line whose first token has internal whitespace`() {
        // A wrapped row whose continuation starts with a whitespace-variant token.
        // Without the \s* fix in joinContinuationLines the lookahead would not fire,
        // the newline would not be replaced, and the continuation would be silently dropped.
        val pages = minimalPages(
            wordChartRows = "Row 1 (R) (1)A,\n( 1) B, (1)C",
        )
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows[0].steps.sumOf { it.count })
        assertEquals(PdfStep(1, "A"), result.rows[0].steps[0])
        assertEquals(PdfStep(1, "B"), result.rows[0].steps[1])
        assertEquals(PdfStep(1, "C"), result.rows[0].steps[2])
    }

    @Test
    fun `parse handles space before digit in step token`() {
        // Token: "( 1)B" — space inside opening paren before digit
        val pages = minimalPages(wordChartRows = "Row 1 (R)  ( 1)B, (1)A, (1)B")
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows[0].steps.sumOf { it.count })
        assertEquals(PdfStep(1, "B"), result.rows[0].steps[0])
        assertEquals(PdfStep(1, "A"), result.rows[0].steps[1])
        assertEquals(PdfStep(1, "B"), result.rows[0].steps[2])
    }

    @Test
    fun `parse handles space after closing paren in step token`() {
        // Token: "(1) A" — space between ) and color letter
        val pages = minimalPages(wordChartRows = "Row 1 (R)  (1) A, (1)B")
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        assertEquals(2, result.rows[0].steps.sumOf { it.count })
        assertEquals(PdfStep(1, "A"), result.rows[0].steps[0])
        assertEquals(PdfStep(1, "B"), result.rows[0].steps[1])
    }

    @Test
    fun `parse handles space in both positions in step token`() {
        // Token: "( 1) A" — space before digit and after )
        val pages = minimalPages(wordChartRows = "Row 1 (R)  ( 1) A, (1)B")
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        assertEquals(2, result.rows[0].steps.sumOf { it.count })
        assertEquals(PdfStep(1, "A"), result.rows[0].steps[0])
        assertEquals(PdfStep(1, "B"), result.rows[0].steps[1])
    }

    @Test
    fun `parse handles multiple spaces before two-letter color code`() {
        // Token: "(2)  AB" — multiple spaces between ) and two-letter code
        val pages = minimalPages(wordChartRows = "Row 1 (R)  (2)  AB, (1)C")
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows[0].steps.sumOf { it.count })
        assertEquals(PdfStep(2, "AB"), result.rows[0].steps[0])
        assertEquals(PdfStep(1, "C"), result.rows[0].steps[1])
    }

    @Test
    fun `parse handles mix of whitespace and normal tokens in same row`() {
        // Models the actual Row 11 content from DiagonalFlagLargeLighterCoverPeyote.pdf:
        // "( 1)B, (1)A, (1)B,  (1) A,  ( 1) B, ( 1)A, (1)B, (1)A,  (1) B,  ( 1) A, ( 1)B, (1)A"
        // 12 tokens present, 7 of which would be silently skipped by the old regex.
        // With the fix, all 12 tokens parse and yield 12 beads.
        val rowContent = "( 1)B, (1)A, (1)B,  (1) A,  ( 1) B, ( 1)A, (1)B, (1)A,  (1) B,  ( 1) A, ( 1)B, (1)A"
        val pages = minimalPages(wordChartRows = "Row 1 (R)  $rowContent")
        val result = parser.parse(pages)
        assertEquals(1, result.rows.size)
        assertEquals(12, result.rows[0].steps.sumOf { it.count })
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
