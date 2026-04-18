package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.pdf.BeadToolColorKeyExtractor
import com.techyshishy.beadmanager.data.pdf.BeadToolPdfParser
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
) {
    companion object {
        private const val TAG = "PdfImport"
    }

    suspend fun import(uri: Uri): ImportResult {
        // 1. Extract text pages from the PDF. NotPdf covers corrupt files and wrong file types.
        val pages = try {
            textExtractor.extract(contentResolver, uri).also {
                Log.d(TAG, "Extracted ${it.size} pages from PDF")
                if (it.isNotEmpty()) Log.d(TAG, "Page 1 preview (first 300 chars): ${it[0].take(300)}")
            }
        } catch (e: PdfParseException.NotPdf) {
            Log.w(TAG, "Text extraction failed (NotPdf): ${e.message}", e)
            return ImportResult.Failure.NotPdf
        }

        // 2. Detect format and parse. BeadTool 4 is tried first; XLSM/Word Chart is the fallback.
        val pdfProject = when (val outcome = detectAndParse(pages, uri)) {
            is ParseOutcome.Success -> outcome.project
            is ParseOutcome.Failure -> {
                Log.w(TAG, "detectAndParse failed: ${outcome.result::class.simpleName}")
                return outcome.result
            }
        }

        // 3. A blank name indicates the parser could not identify document structure.
        if (pdfProject.name.isBlank()) {
            Log.w(TAG, "Parsed project has a blank name — NoPatternFound")
            return ImportResult.Failure.NoPatternFound
        }
        Log.d(TAG, "Parsed project: name='${pdfProject.name}', rows=${pdfProject.rows.size}, colorMapping=${pdfProject.colorMapping}")

        // 4. Validate all DB codes in colorMapping against the local catalog.
        val catalogMap = catalogRepository.allBeadsAsMap()
        val unrecognized = pdfProject.colorMapping.values.filter { it !in catalogMap }.sorted()
        if (unrecognized.isNotEmpty()) {
            Log.w(TAG, "Unrecognized catalog codes: $unrecognized")
            return ImportResult.Failure.UnrecognizedCodes(unrecognized)
        }

        // 5. Map PdfProject → Firestore shape and write.
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
                    name = pdfProject.name,
                    colorMapping = pdfProject.colorMapping,
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
        Log.d(TAG, "Import succeeded: name='${pdfProject.name}', projectId=$projectId")
        return ImportResult.Success(projectId = projectId, name = pdfProject.name)
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
    private suspend fun detectAndParse(pages: List<String>, uri: Uri): ParseOutcome {
        // --- BeadTool 4 ---
        val beadToolProject = try {
            beadToolParser.parse(pages).also {
                Log.d(TAG, "BeadTool parser matched: ${it.rows.size} rows")
            }
        } catch (e: PdfParseException.NoPatternFound) {
            Log.d(TAG, "BeadTool parser: NoPatternFound — trying XLSM fallback")
            null
        }

        if (beadToolProject != null) {
            val colorKeyIndex = colorKeyExtractor.findColorKeyPageIndex(pages)
            Log.d(TAG, "Color key page index: $colorKeyIndex")
            if (colorKeyIndex == -1) {
                Log.w(TAG, "No color key page found in BeadTool PDF")
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            val colorMap = try {
                colorKeyExtractor.extractColorKey(contentResolver, uri, colorKeyIndex).also {
                    Log.d(TAG, "OCR color map: ${it.size} entries = ${it.keys.sorted()}")
                }
            } catch (e: PdfParseException.NotPdf) {
                Log.e(TAG, "OCR: PdfRenderer failed on page $colorKeyIndex", e)
                return ParseOutcome.Failure(ImportResult.Failure.NotPdf)
            } catch (e: Exception) {
                // ML Kit ExecutionException or other unexpected extractor failure — the PDF is
                // readable but we couldn't recover a usable color key from it.
                Log.e(TAG, "OCR: color key extraction failed on page $colorKeyIndex", e)
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            // Validate that OCR recovered an entry for every color letter in the parsed rows.
            val usedLetters = beadToolProject.rows.flatMap { it.steps }.mapTo(mutableSetOf()) { it.colorLetter }
            val missingFromOcr = usedLetters - colorMap.keys
            Log.d(TAG, "Letters used by pattern: $usedLetters; OCR covered: ${colorMap.keys.sorted()}")
            if (missingFromOcr.isNotEmpty()) {
                Log.w(TAG, "OCR missing letters needed by pattern: $missingFromOcr")
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            return ParseOutcome.Success(beadToolProject.copy(colorMapping = colorMap))
        }

        // --- XLSM / Word Chart fallback ---
        Log.d(TAG, "Trying XLSM / Word Chart parser")
        val sourceName = uri.lastPathSegment ?: ""
        return try {
            ParseOutcome.Success(
                xlsmParser.parse(pages, sourceName = sourceName).also {
                    Log.d(TAG, "XLSM parser matched: name='${it.name}', rows=${it.rows.size}, colorMapping=${it.colorMapping}")
                },
            )
        } catch (e: PdfParseException.NoPatternFound) {
            Log.w(TAG, "XLSM parser: NoPatternFound — ${e.message}")
            ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
        } catch (e: PdfParseException.IncompleteColorMapping) {
            Log.w(TAG, "XLSM parser: IncompleteColorMapping — missing letters: ${e.missingLetters}")
            ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
        }
    }

    private sealed class ParseOutcome {
        data class Success(val project: PdfProject) : ParseOutcome()
        data class Failure(val result: ImportResult.Failure) : ParseOutcome()
    }
}
