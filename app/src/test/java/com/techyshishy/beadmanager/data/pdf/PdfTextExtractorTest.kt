package com.techyshishy.beadmanager.data.pdf

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PdfTextExtractorTest {

    private val uri: Uri = mockk(relaxed = true)

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal in-memory PDF with [text] on a single page and returns its bytes.
     * Uses PDFBox directly so no test fixture file is needed.
     */
    private fun singlePagePdf(text: String): ByteArray {
        val baos = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(100f, 700f)
                cs.showText(text)
                cs.endText()
            }
            doc.save(baos)
        }
        return baos.toByteArray()
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `extractPdfText returns page text for a valid single-page PDF`() = runTest {
        val pdfBytes = singlePagePdf("hello bead")
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns ByteArrayInputStream(pdfBytes)
        }

        val pages = extractPdfText(contentResolver, uri)

        assertEquals(1, pages.size)
        assertTrue(
            "Expected page text to contain 'hello bead' but was: ${pages[0]}",
            pages[0].contains("hello bead"),
        )
    }

    @Test
    fun `extractPdfText throws NotPdf when ContentResolver returns null stream`() = runTest {
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns null
        }

        val result = runCatching { extractPdfText(contentResolver, uri) }

        assertTrue(
            "Expected PdfParseException.NotPdf but got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is PdfParseException.NotPdf,
        )
    }

    @Test
    fun `extractPdfText throws NotPdf when stream contains non-PDF bytes`() = runTest {
        val garbage = ByteArrayInputStream("this is not a pdf".toByteArray(Charsets.UTF_8))
        val contentResolver: ContentResolver = mockk {
            every { openInputStream(uri) } returns garbage
        }

        val result = runCatching { extractPdfText(contentResolver, uri) }

        assertTrue(
            "Expected PdfParseException.NotPdf but got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is PdfParseException.NotPdf,
        )
    }
}
