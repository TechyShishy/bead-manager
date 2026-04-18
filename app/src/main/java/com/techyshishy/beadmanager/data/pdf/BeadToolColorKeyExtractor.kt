package com.techyshishy.beadmanager.data.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Extracts the color key (letter → DB code) from a BeadTool 4 PDF.
 *
 * The color key is embedded as a rasterized image on a dedicated page — it is
 * not text-extractable. This class renders that page to a [Bitmap] via
 * [PdfRenderer] and reads it with ML Kit OCR.
 *
 * The [BeadToolPdfParser] produces a [PdfProject] with empty
 * [PdfProject.colorMapping]; this extractor supplies the missing map. Issue #33
 * orchestrates combining both.
 */
class BeadToolColorKeyExtractor @Inject constructor() {

    /**
     * Returns the 0-based index into [pages] (as returned by [extractPdfText])
     * of the color key page.
     *
     * The color key page is identified by the presence of "Color count" metadata
     * text, which BeadTool 4 consistently places on the same page as the bead
     * legend image. Returns -1 when no such page exists.
     */
    fun findColorKeyPageIndex(pages: List<String>): Int =
        pages.indexOfFirst { "Color count" in it }

    /**
     * Parses raw OCR text from a color key page into a letter-to-DB-code map.
     *
     * Expects text in BeadTool 4 format, e.g.:
     * ```
     * Chart #:A
     * DB-0010
     * Black
     * Count:9407
     * Chart #:B
     * DB-2368
     * …
     * ```
     * Entries without a DB code (e.g. dropped by OCR noise) are silently skipped.
     * If the last entry in [ocrText] has no DB code, the loop exits without
     * processing it — this is intentional; there is nothing further to scan.
     *
     * Exposed as a pure function for unit testing only — the runtime path uses
     * [parseBlockTexts] instead.
     */
    fun parseColorKeyText(ocrText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val letterRegex = Regex("""Chart #:([A-Z]{1,2})""")
        // DB codes range from 1–9999; BeadTool strips leading zeros (DB0003 → "DB-3").
        val dbCodeRegex = Regex("""(DB-\d{1,4})""")
        var searchFrom = 0
        while (true) {
            val letterMatch = letterRegex.find(ocrText, searchFrom) ?: break
            // Advance searchFrom before any early-exit so the next iteration never
            // re-processes the same Chart # entry even when the DB code is absent.
            searchFrom = letterMatch.range.last + 1
            val letter = letterMatch.groupValues[1]
            val dbMatch = dbCodeRegex.find(ocrText, letterMatch.range.last + 1) ?: continue
            // Associate only if no other Chart # entry appears before the DB code.
            val nextLetter = letterRegex.find(ocrText, letterMatch.range.last + 1)
            if (nextLetter == null || dbMatch.range.first < nextLetter.range.first) {
                result[letter] = normalizeDbCode(dbMatch.groupValues[1])
            }
        }
        return result
    }

    /**
     * Renders the PDF page at [pageIndex] (0-based) to a [Bitmap], runs ML Kit
     * OCR on it, and returns the letter-to-DB-code mapping.
     *
     * Parses [Text.TextBlock] objects individually rather than the concatenated
     * [Text.text] string, which ensures correct association even when ML Kit
     * returns text from the 3-column grid in row-major order.
     *
     * When [diagnostics] is provided, the raw OCR block count, block texts, and
     * recovered color map are captured regardless of success or failure.
     *
     * Runs on [Dispatchers.IO]. BeadTool 4 PDFs carry `print:yes` permission,
     * which is sufficient for [PdfRenderer] to render pages even though `copy:no`
     * blocks text extraction. If a file carries a user password (non-standard
     * export), [PdfRenderer] will throw [IOException] — surfaced as [PdfParseException.NotPdf].
     *
     * @throws [PdfParseException.NotPdf] if the URI cannot be opened or is not a
     *   valid PDF.
     * @throws [com.google.android.gms.tasks.ExecutionException] if ML Kit OCR fails.
     * @throws [InterruptedException] if the coroutine is cancelled while awaiting OCR.
     */
    suspend fun extractColorKey(
        contentResolver: ContentResolver,
        uri: Uri,
        pageIndex: Int,
        diagnostics: PdfImportDiagnosticsCollector? = null,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw PdfParseException.NotPdf(
                IOException("Cannot open file descriptor for URI: $uri"),
            )
        pfd.use { descriptor ->
            // PdfRenderer throws IOException for non-PDF or password-protected files.
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: IOException) {
                throw PdfParseException.NotPdf(e)
            }
            renderer.use {
                renderer.openPage(pageIndex).use { page ->
                    val scale = RENDER_DPI / POINTS_PER_INCH
                    val width = (page.width * scale).toInt()
                    val height = (page.height * scale).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val recognizer =
                            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        recognizer.use {
                            val visionText = try {
                                Tasks.await(recognizer.process(image))
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw kotlinx.coroutines.CancellationException("OCR interrupted", e)
                            }
                            val blocks = visionText.textBlocks
                            diagnostics?.ocrBlockCount = blocks.size
                            diagnostics?.ocrBlockTexts?.addAll(blocks.map { it.text })
                            val colorMap = parseTextBlocks(blocks)
                            diagnostics?.ocrColorMap = colorMap
                            colorMap
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    /**
     * Parses ML Kit [Text.TextBlock] objects into a letter-to-DB-code map.
     *
     * Delegates to [parseBlockTexts] after mapping each block to its text string.
     */
    private fun parseTextBlocks(textBlocks: List<Text.TextBlock>): Map<String, String> {
        Log.d(TAG, "OCR: ${textBlocks.size} text blocks returned by ML Kit")
        return parseBlockTexts(textBlocks.map { it.text })
    }

    /**
     * Parses a list of OCR text block strings into a letter-to-DB-code map.
     *
     * Stateful: tracks a pending letter across consecutive blocks to handle the
     * case where ML Kit splits a `Chart #:X` label and its DB code into
     * separate blocks. Also normalises OCR noise via [normalizeLetter] and
     * [normalizeDbCode].
     *
     * Also handles OCR noise specific to BeadTool 4 color key pages:
     * - Missing colon (`Chart #N` instead of `Chart #:N`)
     * - Lowercase letter (`Chart #:s` → S)
     * - Digit–letter confusion (`0`→O, `1`→I)
     *
     * Exposed as `internal` for unit testing on JVM; the production entry point
     * is [parseTextBlocks], which maps [Text.TextBlock] objects to their text
     * strings before calling this function.
     */
    internal fun parseBlockTexts(blockTexts: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Colon is optional (OCR sometimes drops it). Letters may be lowercase
        // or confusable digits (0→O, 1→I). The "Chart " prefix is optional to
        // tolerate OCR garbling of "Chart" (e.g. "lart #:R" from "Chart #:R").
        val letterRegex = Regex("""(?:\w{3,5} )?#:?([A-Za-z01]{1,2})""")
        // DB codes range from 1–9999; BeadTool strips leading zeros so DB0003
        // prints as "DB-3". Allow 1–4 digits on the canonical DB- arm.
        // The garbled arm (digit-dash) retains the 3-4 digit constraint because
        // a 1-2 digit garbled prefix is indistinguishable from noise.
        val dbCodeRegex = Regex("""(DB-\d{1,4}|\d-\d{3,4})""")
        var pendingLetter: String? = null

        for (text in blockTexts) {
            val letterMatch = letterRegex.find(text)
            val dbMatch = dbCodeRegex.find(text)

            when {
                letterMatch != null && dbMatch != null
                        && dbMatch.range.first > letterMatch.range.last -> {
                    // Both in the same block with Chart # before DB code — associate directly.
                    val letter = normalizeLetter(letterMatch.groupValues[1])
                    result[letter] = normalizeDbCode(dbMatch.groupValues[1])
                    pendingLetter = null
                }
                letterMatch != null -> {
                    // Chart # present; DB code is absent or precedes the Chart #.
                    // A DB code appearing before the Chart # closes the previous pending entry.
                    if (dbMatch != null && dbMatch.range.first < letterMatch.range.first) {
                        pendingLetter?.let {
                            result[it] = normalizeDbCode(dbMatch.groupValues[1])
                        }
                    }
                    pendingLetter = normalizeLetter(letterMatch.groupValues[1])
                }
                dbMatch != null -> {
                    // DB code only — pair with whichever letter is pending.
                    pendingLetter?.let {
                        result[it] = normalizeDbCode(dbMatch.groupValues[1])
                        pendingLetter = null
                    }
                }
            }
        }
        return result
    }

    /**
     * Normalizes OCR-garbled chart letters to uppercase.
     *
     * Substitutions applied in order:
     * 1. `"Il"` → `"I"` — capital I followed by a lowercase-l artifact (OCR reads one
     *    capital I glyph as two tokens: "I" + the stem "l"). Must run before the
     *    character-level pass or the trailing `l` promotes to `I`, yielding `"II"`.
     * 2. `l` → `I` — standalone lowercase l misread as capital I.
     * 3. Uppercase — normalises any remaining lowercase input.
     * 4. `0` → `O`, `1` → `I` — digit/letter confusion in OCR output.
     */
    private fun normalizeLetter(raw: String): String =
        raw.replace("Il", "I").replace('l', 'I').uppercase().replace('0', 'O').replace('1', 'I')

    /**
     * Normalizes an OCR-extracted DB code to the catalog format.
     *
     * OCR output: `DB-3`, `DB-161`, `DB-0010` (hyphen, 1–4 digit number after
     * stripping leading zeros — BeadTool omits them). Also handles garbled
     * variants such as `3-663` where the `DB-` prefix was corrupted (D dropped,
     * B→3); in that case the digits after the dash are used.
     * Catalog format: `DB0003`, `DB0161`, `DB0010` (no hyphen, zero-padded to 4 digits).
     */
    private fun normalizeDbCode(raw: String): String {
        val digits = if (raw.startsWith("DB-")) raw.removePrefix("DB-") else raw.substringAfter("-")
        return "DB" + digits.padStart(4, '0')
    }

    private companion object {
        const val TAG = "PdfImport"
        const val RENDER_DPI = 300f
        const val POINTS_PER_INCH = 72f
    }
}
