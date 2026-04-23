package com.techyshishy.beadmanager.data.pdf

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PdfTextExtractorTest {

    private val uri: Uri = mockk(relaxed = true)

    // ── tests ─────────────────────────────────────────────────────────────────
    // NOTE: The positive "extracts text from a real PDF" case lives in
    // androidTest/data/pdf/PdfTextExtractorTest because PDFBox-Android's text
    // extraction engine triggers GlyphList/AFM static initialisers that require
    // PDFBoxResourceLoader.init(context) — unavailable in JVM unit tests.

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
