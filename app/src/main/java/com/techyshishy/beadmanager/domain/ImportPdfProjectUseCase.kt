package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
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
    suspend fun import(uri: Uri): ImportResult {
        // 1. Extract text pages from the PDF. NotPdf covers corrupt files and wrong file types.
        val pages = try {
            textExtractor.extract(contentResolver, uri)
        } catch (e: PdfParseException.NotPdf) {
            return ImportResult.Failure.NotPdf
        }

        // 2. Detect format and parse. BeadTool 4 is tried first; XLSM/Word Chart is the fallback.
        val pdfProject = when (val outcome = detectAndParse(pages, uri)) {
            is ParseOutcome.Success -> outcome.project
            is ParseOutcome.Failure -> return outcome.result
        }

        // 3. A blank name indicates the parser could not identify document structure.
        if (pdfProject.name.isBlank()) return ImportResult.Failure.NoPatternFound

        // 4. Validate all DB codes in colorMapping against the local catalog.
        val catalogMap = catalogRepository.allBeadsAsMap()
        val unrecognized = pdfProject.colorMapping.values.filter { it !in catalogMap }.sorted()
        if (unrecognized.isNotEmpty()) {
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
            return ImportResult.Failure.WriteError
        }
        try {
            projectRepository.writeProjectGrid(projectId, projectRows)
        } catch (e: Exception) {
            runCatching { projectRepository.deleteProject(projectId) }
            return ImportResult.Failure.WriteError
        }
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
            beadToolParser.parse(pages)
        } catch (e: PdfParseException.NoPatternFound) {
            null
        } catch (e: PdfParseException.IncompleteColorMapping) {
            null
        }

        if (beadToolProject != null) {
            val colorKeyIndex = colorKeyExtractor.findColorKeyPageIndex(pages)
            if (colorKeyIndex == -1) {
                // No color key page — can't resolve bead codes, treat as no pattern.
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            val colorMap = try {
                colorKeyExtractor.extractColorKey(contentResolver, uri, colorKeyIndex)
            } catch (e: PdfParseException.NotPdf) {
                return ParseOutcome.Failure(ImportResult.Failure.NotPdf)
            } catch (e: Exception) {
                // ML Kit ExecutionException or other unexpected extractor failure — the PDF is
                // readable but we couldn't recover a usable color key from it.
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            // Validate that OCR recovered an entry for every color letter in the parsed rows.
            val usedLetters = beadToolProject.rows.flatMap { it.steps }.mapTo(mutableSetOf()) { it.colorLetter }
            if ((usedLetters - colorMap.keys).isNotEmpty()) {
                return ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
            }
            return ParseOutcome.Success(beadToolProject.copy(colorMapping = colorMap))
        }

        // --- XLSM / Word Chart fallback ---
        val sourceName = uri.lastPathSegment ?: ""
        return try {
            ParseOutcome.Success(xlsmParser.parse(pages, sourceName = sourceName))
        } catch (e: PdfParseException.NoPatternFound) {
            ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
        } catch (e: PdfParseException.IncompleteColorMapping) {
            ParseOutcome.Failure(ImportResult.Failure.NoPatternFound)
        }
    }

    private sealed class ParseOutcome {
        data class Success(val project: PdfProject) : ParseOutcome()
        data class Failure(val result: ImportResult.Failure) : ParseOutcome()
    }
}
