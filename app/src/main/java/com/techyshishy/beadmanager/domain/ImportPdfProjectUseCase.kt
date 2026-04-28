package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.techyshishy.beadmanager.BuildConfig
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.pdf.BeadToolColorKeyExtractor
import com.techyshishy.beadmanager.data.pdf.BeadToolPdfParser
import com.techyshishy.beadmanager.data.pdf.PdfImportDiagnosticsCollector
import com.techyshishy.beadmanager.data.pdf.PdfImportDiagnosticsWriter
import com.techyshishy.beadmanager.data.pdf.PdfParseException
import com.techyshishy.beadmanager.data.pdf.PdfProject
import com.techyshishy.beadmanager.data.pdf.PdfVariant
import com.techyshishy.beadmanager.data.pdf.PdfTextExtractor
import com.techyshishy.beadmanager.data.pdf.XlsmPdfParser
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectImageRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class ImportPdfProjectUseCase @Inject constructor(
    private val contentResolver: ContentResolver,
    private val catalogRepository: CatalogRepository,
    private val projectRepository: ProjectRepository,
    private val projectImageRepository: ProjectImageRepository,
    private val generateProjectPreview: GenerateProjectPreviewUseCase,
    private val beadToolParser: BeadToolPdfParser,
    private val xlsmParser: XlsmPdfParser,
    private val colorKeyExtractor: BeadToolColorKeyExtractor,
    private val textExtractor: PdfTextExtractor,
    private val diagnosticsWriter: PdfImportDiagnosticsWriter,
) {
    companion object {
        private const val TAG = "PdfImport"
    }

    /**
     * Phase 1: extracts text, detects the format, parses all chart variants, and validates
     * the color mapping against the catalog.
     *
     * - **Single variant**: writes to Firestore and returns [ImportResult.Success] (same
     *   behaviour as the old `import()` API).
     * - **Multiple variants**: returns [ImportResult.PendingVariantChoice] without writing
     *   anything. The caller must present variant-selection UI and then call [importVariants].
     */
    suspend fun detect(uri: Uri): ImportResult {
        val diagnostics = PdfImportDiagnosticsCollector()
        diagnostics.pdfFilename = queryDisplayName(uri)

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

        // 2. Detect format and parse all variants.
        val pdfProjects = when (val outcome = detectAndParse(pages, uri, diagnostics)) {
            is ParseOutcome.Success -> outcome.projects
            is ParseOutcome.Failure -> {
                Log.w(TAG, "detectAndParse failed: ${outcome.result::class.simpleName}")
                diagnostics.failureReason = diagnostics.failureReason
                    ?: "detectAndParse: ${outcome.result::class.simpleName}"
                diagnosticsWriter.write(diagnostics)
                return outcome.result
            }
        }

        // 3. Derive the project name from the URI filename.
        val projectName = deriveProjectName(diagnostics.pdfFilename)
        Log.d(TAG, "Derived project name from filename: '$projectName'")

        // 4. Validate all DB codes in colorMapping against the local catalog.
        //    The mapping is shared across all variants so one check covers all of them.
        val catalogMap = catalogRepository.allBeadsAsMap()
        val colorMapping = pdfProjects.first().colorMapping
        val unrecognized = colorMapping.values.filter { it !in catalogMap }.sorted()
        if (unrecognized.isNotEmpty()) {
            Log.w(TAG, "Unrecognized catalog codes: $unrecognized")
            diagnostics.unrecognizedCatalogCodes = unrecognized
            diagnostics.failureReason = "UnrecognizedCodes: $unrecognized"
            diagnosticsWriter.write(diagnostics)
            return ImportResult.Failure.UnrecognizedCodes(unrecognized)
        }

        // 5a. Single variant: write immediately (same path as before multi-variant support).
        if (pdfProjects.size == 1) {
            return writeSingleVariant(pdfProjects.first(), projectName, catalogMap, diagnostics)
        }

        // 5b. Multiple variants: surface them to the caller for selection; nothing is written yet.
        Log.d(TAG, "Multiple variants (${pdfProjects.size}) — returning PendingVariantChoice")
        if (BuildConfig.DEBUG) diagnosticsWriter.write(diagnostics)
        return ImportResult.PendingVariantChoice(
            fileName = projectName,
            colorMapping = colorMapping,
            variants = pdfProjects.mapIndexed { i, p -> PdfVariant(label = "Variant ${i + 1}", rows = p.rows) },
        )
    }

    /**
     * Phase 2: writes the [selected] variants from a [PendingVariantChoice] to Firestore.
     *
     * Returns [ImportResult.Success] when exactly one variant was selected, or
     * [ImportResult.MultiSuccess] when more than one was written. Returns
     * [ImportResult.Failure.WriteError] and stops on the first Firestore failure.
     */
    suspend fun importVariants(
        pending: ImportResult.PendingVariantChoice,
        selected: List<PdfVariant>,
    ): ImportResult {
        if (selected.isEmpty()) return ImportResult.Failure.WriteError
        val catalogMap = catalogRepository.allBeadsAsMap()
        val written = mutableListOf<ImportResult.Success>()
        for (variant in selected) {
            val name = "${pending.fileName} (${variant.label})"
            val project = PdfProject(colorMapping = pending.colorMapping, rows = variant.rows)
            val diagnostics = PdfImportDiagnosticsCollector().apply { pdfFilename = name }
            when (val result = writeSingleVariant(project, name, catalogMap, diagnostics)) {
                is ImportResult.Success -> written.add(result)
                else -> {
                    // Roll back any variants already written before this failure.
                    for (success in written) {
                        runCatching { projectRepository.deleteProject(success.projectId) }
                            .onFailure { Log.w(TAG, "Rollback: deleteProject(${success.projectId}) failed", it) }
                    }
                    return result
                }
            }
        }
        return if (written.size == 1) {
            written.first()
        } else {
            ImportResult.MultiSuccess(
                firstProjectId = written.first().projectId,
                firstName = written.first().name,
            )
        }
    }

    /** Writes [project] to Firestore and returns [ImportResult.Success] or [ImportResult.Failure.WriteError]. */
    private suspend fun writeSingleVariant(
        project: PdfProject,
        projectName: String,
        catalogMap: Map<String, BeadEntity>,
        diagnostics: PdfImportDiagnosticsCollector,
    ): ImportResult {
        val projectRows = project.rows.map { row ->
            ProjectRgpRow(
                id = row.id,
                steps = row.steps.mapIndexed { idx, step ->
                    ProjectRgpStep(id = idx + 1, count = step.count, description = step.colorLetter)
                },
            )
        }
        val projectId = try {
            projectRepository.createProject(
                ProjectEntry(
                    name = projectName,
                    colorMapping = project.colorMapping,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "createProject failed", e)
            return ImportResult.Failure.WriteError
        }
        try {
            projectRepository.writeProjectGrid(projectId, projectRows)
        } catch (e: Exception) {
            Log.e(TAG, "writeProjectGrid failed", e)
            runCatching { projectRepository.deleteProject(projectId) }
                .onSuccess { Log.d(TAG, "Partial project $projectId cleaned up") }
                .onFailure { Log.e(TAG, "Cleanup of partial project $projectId failed", it) }
            return ImportResult.Failure.WriteError
        }
        // Best-effort cover image generation — any exception other than CancellationException
        // is swallowed; the import result is Success regardless.
        runCatching {
            val bytes = generateProjectPreview.render(projectRows, project.colorMapping, catalogMap)
            val imageUrl = projectImageRepository.uploadCoverBytes(projectId, bytes)
            projectRepository.setProjectImageUrl(projectId, imageUrl)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Cover image generation failed — import result unaffected", it)
        }
        Log.d(TAG, "Import succeeded: name='$projectName', projectId=$projectId")
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
        val beadToolProjects = try {
            beadToolParser.parseAllVariants(pages, diagnostics).also {
                Log.d(TAG, "BeadTool parser matched: ${it.size} variant(s), first has ${it.first().rows.size} rows")
            }
        } catch (e: PdfParseException.NoPatternFound) {
            Log.d(TAG, "BeadTool parser: NoPatternFound — trying XLSM fallback")
            null
        }

        if (beadToolProjects != null) {
            val colorKeyIndex = colorKeyExtractor.findColorKeyPageIndex(pages)
            diagnostics.colorKeyPageIndex = colorKeyIndex
            Log.d(TAG, "Color key page index: $colorKeyIndex")
            if (colorKeyIndex == -1) {
                Log.w(TAG, "No color key page found in BeadTool PDF")
                diagnostics.failureReason = "BeadTool: no color key page found"
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            // Collect all color letters used across every variant — the color key is shared.
            val usedLetters = beadToolProjects
                .flatMap { p -> p.rows.flatMap { it.steps } }
                .mapTo(mutableSetOf()) { it.colorLetter }
            // Prefer text-based color key extraction — cheaper, and works when the color
            // key is printed as selectable text rather than a rasterized image.
            val textColorMap = colorKeyExtractor.parseColorKeyText(pages[colorKeyIndex])
            if (textColorMap.keys.containsAll(usedLetters)) {
                Log.d(TAG, "Text color key complete (${textColorMap.size} entries) — skipping OCR")
                diagnostics.ocrMissingLetters = emptySet()
                return ParseOutcome.Success(beadToolProjects.map { it.copy(colorMapping = textColorMap) })
            }
            // Text extraction incomplete — fall through to ML Kit OCR (standard BeadTool 4 path).
            Log.d(TAG, "Text color key partial or empty (${textColorMap.size} entries) — running OCR on page $colorKeyIndex")
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
            // Validate that OCR recovered an entry for every color letter used across all variants.
            val missingFromOcr = usedLetters - colorMap.keys
            diagnostics.ocrMissingLetters = missingFromOcr
            Log.d(TAG, "Letters used by pattern: $usedLetters; OCR covered: ${colorMap.keys.sorted()}")
            if (missingFromOcr.isNotEmpty()) {
                Log.w(TAG, "OCR missing letters needed by pattern: $missingFromOcr")
                diagnostics.failureReason = "BeadTool OCR: missing letters $missingFromOcr"
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            return ParseOutcome.Success(beadToolProjects.map { it.copy(colorMapping = colorMap) })
        }

        // --- XLSM / Word Chart fallback ---
        Log.d(TAG, "Trying XLSM / Word Chart parser")
        return try {
            ParseOutcome.Success(
                listOf(
                    xlsmParser.parse(pages, diagnostics = diagnostics).also {
                        Log.d(TAG, "XLSM parser matched: rows=${it.rows.size}, colorMapping=${it.colorMapping}")
                    },
                ),
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
     * Returns the raw display name of the file at [uri] as reported by the
     * [ContentResolver] (e.g. `"MyPattern.pdf"`), or null when the query fails
     * or the column is absent.
     */
    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(
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
    } catch (e: Exception) {
        Log.w(TAG, "ContentProvider query failed; falling back to default project name", e)
        null
    }

    /**
     * Derives a project name from a raw display name string.
     *
     * Strips a trailing `.pdf` extension (case-insensitive) and trims surrounding
     * whitespace. Returns `"Imported Project"` when [displayName] is null or blank
     * after stripping.
     */
    private fun deriveProjectName(displayName: String?): String {
        val stripped = displayName
            ?.trim()
            ?.let { if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return stripped ?: "Imported Project"
    }

    private sealed class ParseOutcome {
        data class Success(val projects: List<PdfProject>) : ParseOutcome()
        data class Failure(val result: ImportResult.Failure) : ParseOutcome()
    }
}
