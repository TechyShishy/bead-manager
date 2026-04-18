package com.techyshishy.beadmanager.data.pdf

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Extracts the text content of a PDF identified by [uri], returning one [String] per page.
 *
 * Runs on [Dispatchers.IO]. Throws [PdfParseException.NotPdf] when:
 * - [ContentResolver.openInputStream] returns null (permission denied, file not found, etc.)
 * - the stream is not a valid or readable PDF
 *
 * Font resource initialization (PDFBoxResourceLoader) is intentionally omitted here.
 * BeadTool 4 and XLSM exports embed their fonts, so text extraction works without it.
 * If full font resolution is ever needed, wire PDFBoxResourceLoader.init(context) in the
 * Application class before any call to this function.
 */
suspend fun extractPdfText(contentResolver: ContentResolver, uri: Uri): List<String> =
    withContext(Dispatchers.IO) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw PdfParseException.NotPdf(
                IOException("ContentResolver returned null stream for URI: $uri")
            )
        // PDDocument.load() buffers the content internally and does not close the original
        // stream — close it explicitly via the outer use{} to avoid a file descriptor leak.
        // PDFBoxResourceLoader.init() intentionally omitted:
        // BeadTool 4 / XLSM exports embed all fonts; no font resolution needed.
        stream.use { s ->
            try {
                PDDocument.load(s).use { doc ->
                    val stripper = PDFTextStripper()
                    (1..doc.numberOfPages).map { pageNum ->
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        stripper.getText(doc)
                    }
                }
            } catch (e: IOException) {
                throw PdfParseException.NotPdf(e)
            }
        }
    }
