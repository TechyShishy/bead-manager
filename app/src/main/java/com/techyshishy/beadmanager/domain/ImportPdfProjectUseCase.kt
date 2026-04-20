package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.techyshishy.beadmanager.BuildConfig
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.pdf.BeadToolColorKeyExtractor
import com.techyshishy.beadmanager.data.pdf.BeadToolPdfParser
import com.techyshishy.beadmanager.data.pdf.PdfImportDiagnosticsCollector
import com.techyshishy.beadmanager.data.pdf.PdfImportDiagnosticsWriter
import com.techyshishy.beadmanager.data.pdf.PdfParseException
import com.techyshishy.beadmanager.data.pdf.PdfProject
import com.techyshishy.beadmanager.data.pdf.PdfTextExtractor
import com.techyshishy.beadmanager.data.pdf.XlsmPdfParser
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import javax.inject.Inject

class ImportPdfProjectUseCase @Inject constructor(
    private val contentResolver: ContentResolver,
    private val catalogRepository: CatalogRepository,
    private val projectRepository: ProjectRepository,
    private val beadToolParser: BeadToolPdfParser,
    private val xlsmParser: XlsmPdfParser,
    private val colorKeyExtractor: BeadToolColorKeyExtractor,
    private val textExtractor: PdfTextExtractor,
    private val diagnosticsWriter: PdfImportDiagnosticsWriter,
) {
    companion object {
        private const val TAG = "PdfImport"
    }

    suspend fun import(uri: Uri): ImportResult {
        val diagnostics = PdfImportDiagnosticsCollector()

        // 1. Extract text pages from the PDF. NotPdf covers corrupt files and wrong file types.
        val pages = try {
            textExtractor.extract(contentResolver, uri).also {
                Log.d(TAG, "Extracted ${it.size} pages from PDF")
                if (it.isNotEmpty()) Log.d(TAG, "Page 1 preview (first 300 chars): ${it[0].take(300)}")
                diagnostics.pageCount = it.size
                diagnostics.pageTexts.addAll(it)
            }
        } catch (e: PdfParseException.NotPdf) {
            Log.w(TAG, "Text extraction failed (NotPdf): ${e.message}", e)
            diagnostics.failureReason = "NotPdf — text extraction: ${e.message}"
            diagnosticsWriter.write(diagnostics)
            return ImportResult.Failure.NotPdf
        }

        // 2. Detect format and parse. BeadTool 4 is tried first; XLSM/Word Chart is the fallback.
        val pdfProject = when (val outcome = detectAndParse(pages, uri, diagnostics)) {
            is ParseOutcome.Success -> outcome.project
            is ParseOutcome.Failure -> {
                Log.w(TAG, "detectAndParse failed: ${outcome.result::class.simpleName}")
                diagnostics.failureReason = diagnostics.failureReason
                    ?: "detectAndParse: ${outcome.result::class.simpleName}"
                diagnosticsWriter.write(diagnostics)
                return outcome.result
            }
        }

        // 3. A blank name indicates the parser could not identify document structure.
        if (pdfProject.name.isBlank()) {
            Log.w(TAG, "Parsed project has a blank name — NoPatternFound")
            diagnostics.parsedProjectName = "(blank)"
            diagnostics.failureReason = "Blank project name after successful parse"
            diagnosticsWriter.write(diagnostics)
            return ImportResult.Failure.NoPatternFound
        }
        diagnostics.parsedProjectName = pdfProject.name
        Log.d(TAG, "Parsed project: name='${pdfProject.name}', rows=${pdfProject.rows.size}, colorMapping=${pdfProject.colorMapping}")

        // 4. Derive the project name from the URI filename rather than the in-document title.
        //    In-document titles are often generic ("Bead Pattern", "Sheet1"); the filename is
        //    the most reliable user-controlled signal for what the project should be called.
        val projectName = deriveProjectName(uri)
        Log.d(TAG, "Derived project name from filename: '$projectName'")

        // 5. Validate all DB codes in colorMapping against the local catalog.
        val catalogMap = catalogRepository.allBeadsAsMap()
        val unrecognized = pdfProject.colorMapping.values.filter { it !in catalogMap }.sorted()
        if (unrecognized.isNotEmpty()) {
            Log.w(TAG, "Unrecognized catalog codes: $unrecognized")
            diagnostics.unrecognizedCatalogCodes = unrecognized
            diagnostics.failureReason = "UnrecognizedCodes: $unrecognized"
            diagnosticsWriter.write(diagnostics)
            return ImportResult.Failure.UnrecognizedCodes(unrecognized)
        }

        // 6. Map PdfProject → Firestore shape and write.
        val projectRows = pdfProject.rows.map { row ->
            ProjectRgpRow(
                id = row.id,
                steps = row.steps.mapIndexed { idx, step ->
                    // PdfStep has no id; assign a 1-based sequential id within each row.
                    ProjectRgpStep(id = idx + 1, count = step.count, description = step.colorLetter)
                },
            )
        }
        val projectId = try {
            projectRepository.createProject(
                ProjectEntry(
                    name = projectName,
                    colorMapping = pdfProject.colorMapping,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "createProject failed", e)
            // WriteError is a Firestore / connectivity failure, not a parse failure.
            // The PDF parsed successfully so no diagnostics are written.
            return ImportResult.Failure.WriteError
        }
        try {
            projectRepository.writeProjectGrid(projectId, projectRows)
        } catch (e: Exception) {
            Log.e(TAG, "writeProjectGrid failed", e)
            // WriteError is a Firestore / connectivity failure, not a parse failure.
            // The PDF parsed successfully so no diagnostics are written.
            runCatching { projectRepository.deleteProject(projectId) }
                .onSuccess { Log.d(TAG, "Partial project $projectId cleaned up") }
                .onFailure { Log.e(TAG, "Cleanup of partial project $projectId failed", it) }
            return ImportResult.Failure.WriteError
        }
        Log.d(TAG, "Import succeeded: name='$projectName', projectId=$projectId")
        // In debug builds write a diagnostics report even on success so that
        // parse correctness (e.g. per-row step counts) can be verified without
        // needing to trigger a failure.
        if (BuildConfig.DEBUG) diagnosticsWriter.write(diagnostics)
        return ImportResult.Success(projectId = projectId, name = projectName)
    }

    /**
     * Tries BeadTool 4 parsing first, then XLSM/Word Chart.
     *
     * BeadTool 4 PDFs store the color key as a rasterized image; the parser returns an empty
     * [PdfProject.colorMapping] which is populated here via [BeadToolColorKeyExtractor] OCR.
     * XLSM PDFs include the color key as text, so [XlsmPdfParser] returns a fully-populated
     * [PdfProject.colorMapping] directly.
     *
     * [PdfParseException.IncompleteColorMapping] (color letters with no color key entry) is
     * mapped to [ImportResult.Failure.NoPatternFound] — the pattern cannot be usefully
     * imported without a complete color table.
     */
    private suspend fun detectAndParse(
        pages: List<String>,
        uri: Uri,
        diagnostics: PdfImportDiagnosticsCollector,
    ): ParseOutcome {
        // --- BeadTool 4 ---
        val beadToolProject = try {
            beadToolParser.parse(pages, diagnostics).also {
                Log.d(TAG, "BeadTool parser matched: ${it.rows.size} rows")
            }
        } catch (e: PdfParseException.NoPatternFound) {
            Log.d(TAG, "BeadTool parser: NoPatternFound — trying XLSM fallback")
            null
        }

        if (beadToolProject != null) {
            val colorKeyIndex = colorKeyExtractor.findColorKeyPageIndex(pages)
            diagnostics.colorKeyPageIndex = colorKeyIndex
            Log.d(TAG, "Color key page index: $colorKeyIndex")
            if (colorKeyIndex == -1) {
                Log.w(TAG, "No color key page found in BeadTool PDF")
                diagnostics.failureReason = "BeadTool: no color key page found"
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            val colorMap = try {
                colorKeyExtractor.extractColorKey(
                    contentResolver, uri, colorKeyIndex, diagnostics
                ).also {
                    Log.d(TAG, "OCR color map: ${it.size} entries = ${it.keys.sorted()}")
                }
            } catch (e: PdfParseException.NotPdf) {
                Log.e(TAG, "OCR: PdfRenderer failed on page $colorKeyIndex", e)
                diagnostics.failureReason = "BeadTool OCR: PdfRenderer failure on page $colorKeyIndex"
                return ParseOutcome.Failure(ImportResult.Failure.NotPdf)
            } catch (e: Exception) {
                // ML Kit ExecutionException or other unexpected extractor failure — the PDF is
                // readable but we couldn't recover a usable color key from it.
                Log.e(TAG, "OCR: color key extraction failed on page $colorKeyIndex", e)
                diagnostics.failureReason = "BeadTool OCR: extractor exception — ${e::class.simpleName}: ${e.message}"
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            // Validate that OCR recovered an entry for every color letter in the parsed rows.
            val usedLetters = beadToolProject.rows.flatMap { it.steps }.mapTo(mutableSetOf()) { it.colorLetter }
            val missingFromOcr = usedLetters - colorMap.keys
            diagnostics.ocrMissingLetters = missingFromOcr
            Log.d(TAG, "Letters used by pattern: $usedLetters; OCR covered: ${colorMap.keys.sorted()}")
            if (missingFromOcr.isNotEmpty()) {
                Log.w(TAG, "OCR missing letters needed by pattern: $missingFromOcr")
                diagnostics.failureReason = "BeadTool OCR: missing letters $missingFromOcr"
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            return ParseOutcome.Success(beadToolProject.copy(colorMapping = colorMap))
        }

        // --- XLSM / Word Chart fallback ---
        Log.d(TAG, "Trying XLSM / Word Chart parser")
        val sourceName = uri.lastPathSegment ?: ""
        return try {
            ParseOutcome.Success(
                xlsmParser.parse(pages, sourceName = sourceName, diagnostics = diagnostics).also {
                    Log.d(TAG, "XLSM parser matched: name='${it.name}', rows=${it.rows.size}, colorMapping=${it.colorMapping}")
                },
            )
        } catch (e: PdfParseException.NoPatternFound) {
            Log.w(TAG, "XLSM parser: NoPatternFound — ${e.message}")
            diagnostics.failureReason = diagnostics.failureReason
                ?: "XLSM: NoPatternFound (both parsers exhausted)"
            ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
        } catch (e: PdfParseException.IncompleteColorMapping) {
            Log.w(TAG, "XLSM parser: IncompleteColorMapping — missing letters: ${e.missingLetters}")
            diagnostics.failureReason = diagnostics.failureReason
                ?: "XLSM: IncompleteColorMapping — missing ${e.missingLetters}"
            ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
        }
    }

    /**
     * Derives a project name from the URI's display name.
     *
     * Queries [ContentResolver] for [OpenableColumns.DISPLAY_NAME], strips a trailing `.pdf`
     * extension (case-insensitive), and trims surrounding whitespace. Returns
     * `"Imported Project"` if the display name cannot be retrieved or is blank after
     * stripping.
     */
    private fun deriveProjectName(uri: Uri): String {
        // TODO: contentResolver.query() is unguarded. A ContentProvider crash (e.g. provider
        //  process death, stale URI) will propagate as an unhandled exception through import()
        //  instead of gracefully degrading to "Imported Project". Wrap the query block in a
        //  try/catch(Exception) that returns null so the fallback path is always taken on any
        //  query failure.
        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0) cursor.getString(col) else null
            } else null
        }
        val stripped = displayName
            ?.trim()
            ?.let { if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return stripped ?: "Imported Project"
    }

    private sealed class ParseOutcome {
        data class Success(val project: PdfProject) : ParseOutcome()
        data class Failure(val result: ImportResult.Failure) : ParseOutcome()
    }
}
