package com.techyshishy.beadmanager.data.rgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgpPatternSynthesisTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Expand synthesized rows back into a [bufferWidth × bufferHeight] color array, applying
     * the same even-row reversal that pxlpxl performs on import. Each element is a key string
     * (letter). Returns a 2-D list indexed [bufferRow][bufferColumn].
     *
     * This mirrors pxlpxl's `importRgp` logic:
     *   - Expand each row's steps from RLE to a flat list of color keys
     *   - Reverse even rows (0-indexed) to undo the RTL storage convention
     *   - Write into the buffer
     */
    private fun decodeBuffer(
        rows: List<com.techyshishy.beadmanager.data.firestore.ProjectRgpRow>,
        bufferWidth: Int,
    ): List<List<String>> {
        return rows.mapIndexed { by, row ->
            val flat = mutableListOf<String>()
            for (step in row.steps) {
                repeat(step.count) { flat.add(step.description) }
            }
            if (by % 2 == 0) flat.reverse()          // pxlpxl undoes RTL on even rows
            flat.take(bufferWidth)
        }
    }

    // ── empty input ──────────────────────────────────────────────────────────

    @Test
    fun `empty colorMapping returns empty list`() {
        val result = synthesizeStripeRows(emptyMap())
        assertTrue(result.isEmpty())
    }

    // ── grid dimensions ──────────────────────────────────────────────────────

    @Test
    fun `single color produces 1 column and 2 rows`() {
        val rows = synthesizeStripeRows(mapOf("A" to "#FF0000"))
        assertEquals(2, rows.size)
        val bufferWidth = rows.maxOf { row -> row.steps.sumOf { it.count } }
        assertEquals(1, bufferWidth)
    }

    @Test
    fun `single color — each row has exactly one step with count 1 and the correct key`() {
        val rows = synthesizeStripeRows(mapOf("A" to "#FF0000"))
        for ((index, row) in rows.withIndex()) {
            assertEquals("Row $index should have 1 step", 1, row.steps.size)
            assertEquals("Row $index step count", 1, row.steps[0].count)
            assertEquals("Row $index step key", "A", row.steps[0].description)
        }
    }

    @Test
    fun `n colors produces n buffer columns and 2n rows`() {
        val map = mapOf("A" to "#FF0000", "B" to "#00FF00", "C" to "#0000FF")
        val rows = synthesizeStripeRows(map)
        val n = 3
        assertEquals(n * 2, rows.size)
        val bufferWidth = rows.maxOf { row -> row.steps.sumOf { it.count } }
        assertEquals(n, bufferWidth)
    }

    // ── row IDs ──────────────────────────────────────────────────────────────

    @Test
    fun `row ids are 1-indexed sequential`() {
        val rows = synthesizeStripeRows(mapOf("A" to "#FF0000", "B" to "#00FF00"))
        rows.forEachIndexed { index, row ->
            assertEquals(index + 1, row.id)
        }
    }

    // ── RLE encoding and RTL convention ──────────────────────────────────────

    @Test
    fun `even rows are stored RTL — first step color matches last visual column`() {
        // n=2, sorted keys = [A, B]
        // Row 0 (by=0, even, visualRow=0): visual LTR = [A, B], stored RTL = [B, A]
        // So the first step should be B (rightmost LTR column = leftmost RTL step)
        val rows = synthesizeStripeRows(mapOf("A" to "#FF0000", "B" to "#00FF00"))
        val row0Steps = rows[0].steps
        assertEquals("B", row0Steps[0].description)
        assertEquals("A", row0Steps[1].description)
    }

    @Test
    fun `odd rows are stored LTR — first step color matches first visual column`() {
        // n=2, Row 1 (by=1, odd, visualRow=0): visual LTR = [A, B], stored LTR = [A, B]
        val rows = synthesizeStripeRows(mapOf("A" to "#FF0000", "B" to "#00FF00"))
        val row1Steps = rows[1].steps
        assertEquals("A", row1Steps[0].description)
        assertEquals("B", row1Steps[1].description)
    }

    // ── decoded buffer matches expected diagonal pattern ──────────────────────

    @Test
    fun `two-color pattern decodes to correct diagonal stripe in buffer`() {
        // n=2, colorKeys (sorted) = [A, B]
        // Expected buffer after pxlpxl import reversal (each cell = color at that bx):
        //   by=0 (even, reversed):  bx=0→A, bx=1→B     (visual row 0)
        //   by=1 (odd, as-is):      bx=0→A, bx=1→B
        //   by=2 (even, reversed):  bx=0→B, bx=1→A     (visual row 1, +1 shift)
        //   by=3 (odd, as-is):      bx=0→B, bx=1→A
        val rows = synthesizeStripeRows(mapOf("A" to "#FF0000", "B" to "#00FF00"))
        val buf = decodeBuffer(rows, 2)

        assertEquals("A", buf[0][0])
        assertEquals("B", buf[0][1])
        assertEquals("A", buf[1][0])
        assertEquals("B", buf[1][1])
        assertEquals("B", buf[2][0])
        assertEquals("A", buf[2][1])
        assertEquals("B", buf[3][0])
        assertEquals("A", buf[3][1])
    }

    @Test
    fun `four-color pattern advances by one color per pair of buffer rows`() {
        val map = mapOf("A" to "#1", "B" to "#2", "C" to "#3", "D" to "#4")
        val rows = synthesizeStripeRows(map)
        val buf = decodeBuffer(rows, 4)

        // Visual row 0 (by=0 and by=1): each column k has color k
        assertEquals("A", buf[0][0]); assertEquals("B", buf[0][1])
        assertEquals("C", buf[0][2]); assertEquals("D", buf[0][3])
        assertEquals("A", buf[1][0]); assertEquals("B", buf[1][1])

        // Visual row 1 (by=2 and by=3): each column k has color (k+1) % 4
        assertEquals("B", buf[2][0]); assertEquals("C", buf[2][1])
        assertEquals("D", buf[2][2]); assertEquals("A", buf[2][3])
        assertEquals("B", buf[3][0]); assertEquals("C", buf[3][1])
    }

    // ── step IDs ─────────────────────────────────────────────────────────────

    @Test
    fun `step ids within each row are 1-indexed sequential`() {
        val rows = synthesizeStripeRows(mapOf("A" to "#1", "B" to "#2", "C" to "#3"))
        for (row in rows) {
            row.steps.forEachIndexed { index, step ->
                assertEquals(index + 1, step.id)
            }
        }
    }

    // ── determinism ──────────────────────────────────────────────────────────

    @Test
    fun `key order in the input map does not affect the pattern`() {
        val alphabetical = synthesizeStripeRows(mapOf("A" to "#1", "B" to "#2", "C" to "#3"))
        val reversed = synthesizeStripeRows(mapOf("C" to "#3", "B" to "#2", "A" to "#1"))
        assertEquals(alphabetical.size, reversed.size)
        alphabetical.zip(reversed).forEach { (a, b) ->
            assertEquals(a.steps, b.steps)
        }
    }
}
