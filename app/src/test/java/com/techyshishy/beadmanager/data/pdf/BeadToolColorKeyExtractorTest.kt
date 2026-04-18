package com.techyshishy.beadmanager.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeadToolColorKeyExtractorTest {

    private val extractor = BeadToolColorKeyExtractor()

    // ── findColorKeyPageIndex ─────────────────────────────────────────────────

    @Test
    fun `findColorKeyPageIndex returns index of first page containing Color count`() {
        val pages = listOf(
            "Dragon's Wrath-Peyote\nCreated by Nataliia Horbenko\n",
            "It is recommended for intermediate creators.\n",
            "This design utilizes Miyuki Delica seed beads in size 11.\nColor count - 33. Bead count - 129500.\n",
            "Bead Graph (Peyote) AD AD AD AD\n",
        )
        assertEquals(2, extractor.findColorKeyPageIndex(pages))
    }

    @Test
    fun `findColorKeyPageIndex returns -1 when no page contains Color count`() {
        val pages = listOf("Page one", "Page two", "Page three")
        assertEquals(-1, extractor.findColorKeyPageIndex(pages))
    }

    @Test
    fun `findColorKeyPageIndex returns -1 for an empty page list`() {
        assertEquals(-1, extractor.findColorKeyPageIndex(emptyList()))
    }

    @Test
    fun `findColorKeyPageIndex returns first match when multiple pages could match`() {
        val pages = listOf(
            "Color count - 5. Bead count - 1000.\n",
            "Color count - 10. Bead count - 2000.\n",
        )
        assertEquals(0, extractor.findColorKeyPageIndex(pages))
    }

    // ── parseColorKeyText ─────────────────────────────────────────────────────

    @Test
    fun `parseColorKeyText maps single-letter chart codes to DB codes`() {
        val ocrText = """
            Chart #:A
            DB-0010
            Black
            Count:9407
            Chart #:B
            DB-2368
            Opaque Charcoal Duracoat
            Count:3741
        """.trimIndent()
        val result = extractor.parseColorKeyText(ocrText)
        assertEquals("DB0010", result["A"])
        assertEquals("DB2368", result["B"])
    }

    @Test
    fun `parseColorKeyText maps two-letter chart codes to DB codes`() {
        val ocrText = """
            Chart #:AA
            DB-2143
            Opaque Navy
            Count:2804
            Chart #:AB
            DB-1054
            Opaque MatteGold Iris Plum
            Count:1570
        """.trimIndent()
        val result = extractor.parseColorKeyText(ocrText)
        assertEquals("DB2143", result["AA"])
        assertEquals("DB1054", result["AB"])
    }

    @Test
    fun `parseColorKeyText skips an entry whose DB code is absent`() {
        // Entry B is malformed (OCR noise dropped the DB code line)
        val ocrText = """
            Chart #:A
            DB-0010
            Black
            Chart #:B
            Garbled OCR noise no db code here
            Chart #:C
            DB-0168
            Rainbow Gray
        """.trimIndent()
        val result = extractor.parseColorKeyText(ocrText)
        assertEquals("DB0010", result["A"])
        assertFalse("B should be absent when DB code is missing", result.containsKey("B"))
        assertEquals("DB0168", result["C"])
    }

    @Test
    fun `parseColorKeyText returns empty map for empty input`() {
        assertTrue(extractor.parseColorKeyText("").isEmpty())
    }

    @Test
    fun `parseColorKeyText maps three-digit DB codes and zero-pads to four digits`() {
        // Delica codes below 1000 have 3 digits in OCR output (DB-161, DB-310).
        // They must be normalized to the 4-digit catalog format (DB0161, DB0310).
        val ocrText = """
            Chart #:B
            DB-161
            Opaque Orange AB
            Count:1136
            Chart #:D
            DB-310
            Matte Black
            Count:5408
            Chart #:L
            DB-1134
            Opaque Currant
            Count:5335
        """.trimIndent()
        val result = extractor.parseColorKeyText(ocrText)
        assertEquals("DB0161", result["B"])
        assertEquals("DB0310", result["D"])
        assertEquals("DB1134", result["L"])
    }

    @Test
    fun `parseColorKeyText does not break early when a mid-sequence entry has no DB code`() {
        // Previously the loop used ?: break on the DB code search, which would
        // exit the loop at the first entry with no recognizable DB code, losing
        // all subsequent entries. It should skip and continue.
        val ocrText = """
            Chart #:A
            DB-0010
            Black
            Count:1000
            Chart #:B
            Completely garbled OCR noise
            Count:500
            Chart #:C
            DB-2368
            Opaque Charcoal Duracoat
            Count:800
        """.trimIndent()
        val result = extractor.parseColorKeyText(ocrText)
        assertEquals("DB0010", result["A"])
        assertFalse("B should be absent (no DB code in OCR)", result.containsKey("B"))
        assertEquals("DB2368", result["C"])
    }

    @Test
    fun `parseColorKeyText handles full 21-entry sample without duplicates or gaps`() {
        // Exercises the real OCR text structure from the Dragons Wrath PDF sample.
        // Each entry follows: Chart #:X / DB-NNNN / Name / Count:N
        // Expected values are catalog format (no hyphen, zero-padded to 4 digits).
        val entries = listOf(
            "A" to "DB-0010", "B" to "DB-2368", "C" to "DB-0168",
            "D" to "DB-1134", "E" to "DB-1530", "F" to "DB-0630",
            "G" to "DB-1579", "H" to "DB-1570", "I" to "DB-0731",
            "J" to "DB-0734", "K" to "DB-1683", "L" to "DB-0764",
            "M" to "DB-2106", "N" to "DB-2103", "O" to "DB-0202",
            "AA" to "DB-2143", "AB" to "DB-1054", "AC" to "DB-0323",
            "AD" to "DB-0749", "AE" to "DB-0025", "AF" to "DB-0863",
        )
        val ocrText = entries.joinToString("\n") { (letter, code) ->
            "Chart #:$letter\n$code\nSome Bead Name\nCount:100"
        }
        val result = extractor.parseColorKeyText(ocrText)
        for ((letter, rawCode) in entries) {
            val digits = rawCode.removePrefix("DB-").removePrefix("DB")
            val expected = "DB" + digits.padStart(4, '0')
            assertEquals("Mapping for $letter", expected, result[letter])
        }
        assertEquals(entries.size, result.size)
    }

    // ── parseBlockTexts ───────────────────────────────────────────────────────

    @Test
    fun `parseBlockTexts pairs Chart letter and DB code in the same block`() {
        val blocks = listOf("Chart #:A\nDB-0010\nBlack\nCount:9407")
        val result = extractor.parseBlockTexts(blocks)
        assertEquals("DB0010", result["A"])
        assertEquals(1, result.size)
    }

    @Test
    fun `parseBlockTexts pairs Chart letter in one block with DB code in the next`() {
        val blocks = listOf(
            "Chart #:A",
            "DB-0010",
        )
        val result = extractor.parseBlockTexts(blocks)
        assertEquals("DB0010", result["A"])
    }

    @Test
    fun `parseBlockTexts does not carry an orphaned pending letter to a later unrelated DB code`() {
        // A has no DB code; the next DB code belongs to C, not A.
        val blocks = listOf(
            "Chart #:A",
            "Some noise",
            "Chart #:C",
            "DB-0168",
        )
        val result = extractor.parseBlockTexts(blocks)
        assertFalse("A should be absent", result.containsKey("A"))
        assertEquals("DB0168", result["C"])
    }

    @Test
    fun `parseBlockTexts handles a block where DB code precedes the Chart letter`() {
        // In some layouts OCR returns the DB code line before the Chart # line
        // within the same block. The DB code should close the previous pending
        // entry, not the current one.
        val blocks = listOf(
            "Chart #:A",
            "DB-0010\nChart #:B",
            "DB-2368",
        )
        val result = extractor.parseBlockTexts(blocks)
        assertEquals("DB0010", result["A"])
        assertEquals("DB2368", result["B"])
    }

    @Test
    fun `parseBlockTexts normalises OCR noise in chart letters`() {
        val blocks = listOf(
            "Chart #:s\nDB-0010",   // lowercase s → S
            "Chart #:1\nDB-2368",   // digit 1 → I
            "Chart #N\nDB-0168",    // missing colon
        )
        val result = extractor.parseBlockTexts(blocks)
        assertEquals("DB0010", result["S"])
        assertEquals("DB2368", result["I"])
        assertEquals("DB0168", result["N"])
    }

    @Test
    fun `parseBlockTexts returns empty map for empty block list`() {
        assertTrue(extractor.parseBlockTexts(emptyList()).isEmpty())
    }

    @Test
    fun `parseBlockTexts recovers letter when Chart word is garbled and DB prefix is garbled to digit-dash`() {
        // Regression for DragonEye-MiyukiDelica.pdf: OCR garbled "Chart #:R" to
        // "lart #:R" (C+h merged into l) and "DB-663" to "3-663" (D dropped, B→3).
        // Both the letter regex and the DB code regex must tolerate these mutations.
        val blocks = listOf(
            "lart #:R",
            "3-663\nOpaque Olive\nCount:2004",
        )
        val result = extractor.parseBlockTexts(blocks)
        assertEquals("DB0663", result["R"])
    }

    @Test
    fun `parseBlockTexts ignores false letter match from intro text hash-number before first Chart entry`() {
        // The relaxed letterRegex matches #11 in "using #11 Miyuki Delica" as letter "II".
        // That false pendingLetter must be overwritten by the first real Chart entry
        // and must never appear in the result map.
        val blocks = listOf(
            "This design uses #11 Miyuki Delica beads",
            "Chart #:A",
            "DB-0010",
        )
        val result = extractor.parseBlockTexts(blocks)
        assertEquals("DB0010", result["A"])
        assertFalse("II should not appear — false match from intro text", result.containsKey("II"))
    }
}
