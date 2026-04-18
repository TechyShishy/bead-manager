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
        assertEquals("DB-0010", result["A"])
        assertEquals("DB-2368", result["B"])
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
        assertEquals("DB-2143", result["AA"])
        assertEquals("DB-1054", result["AB"])
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
        assertEquals("DB-0010", result["A"])
        assertFalse("B should be absent when DB code is missing", result.containsKey("B"))
        assertEquals("DB-0168", result["C"])
    }

    @Test
    fun `parseColorKeyText returns empty map for empty input`() {
        assertTrue(extractor.parseColorKeyText("").isEmpty())
    }

    @Test
    fun `parseColorKeyText handles full 33-entry sample without duplicates or gaps`() {
        // Exercises the real OCR text structure from the Dragons Wrath PDF sample.
        // Each entry follows: Chart #:X / DB-NNNN / Name / Count:N
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
        for ((letter, code) in entries) {
            assertEquals("Mapping for $letter", code, result[letter])
        }
        assertEquals(entries.size, result.size)
    }
}
