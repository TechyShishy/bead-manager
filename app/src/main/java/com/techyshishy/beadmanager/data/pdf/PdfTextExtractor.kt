package com.techyshishy.beadmanager.data.pdf

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

import javax.inject.Inject

/**
 * Extracts the text content of a PDF identified by [uri], returning one [String] per page.
 *
 * Runs on [Dispatchers.IO]. Throws [PdfParseException.NotPdf] when:
 * - [ContentResolver.openInputStream] returns null (permission denied, file not found, etc.)
 * - the stream is not a valid or readable PDF
 *
 * Font resource initialization (PDFBoxResourceLoader.init) is called in BeadManagerApp.onCreate
 * and must happen before any call to this function.
 */
suspend fun extractPdfText(contentResolver: ContentResolver, uri: Uri): List<String> =
    withContext(Dispatchers.IO) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw PdfParseException.NotPdf(
                IOException("ContentResolver returned null stream for URI: $uri")
            )
        // PDDocument.load() buffers the content internally and does not close the original
        // stream — close it explicitly via the outer use{} to avoid a file descriptor leak.
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

/**
 * Injectable wrapper around [extractPdfText].
 *
 * Exists solely so [com.techyshishy.beadmanager.domain.ImportPdfProjectUseCase] can receive a
 * mockable collaborator in unit tests. All production logic lives in the top-level function.
 */
class PdfTextExtractor @Inject constructor() {
    suspend fun extract(contentResolver: ContentResolver, uri: Uri): List<String> =
        extractPdfText(contentResolver, uri)
}
