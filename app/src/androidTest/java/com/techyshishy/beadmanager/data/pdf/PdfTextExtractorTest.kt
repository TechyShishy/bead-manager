package com.techyshishy.beadmanager.data.pdf

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Instrumented test for [extractPdfText].
 *
 * PDFBox-Android's text extraction engine triggers GlyphList/AFM static initialisers that
 * load resources via Android's AssetManager. Those resources are unavailable in JVM unit
 * tests, so the positive extraction case lives here where a real Context is available.
 *
 * The two negative cases (null stream, non-PDF bytes) remain in the JVM unit test because
 * they never reach PDFBox internals.
 */
@RunWith(AndroidJUnit4::class)
class PdfTextExtractorTest {

    private val uri: Uri = mockk(relaxed = true)

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
    fun extractPdfTextReturnsPageTextForAValidSinglePagePdf() = runBlocking {
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
}
