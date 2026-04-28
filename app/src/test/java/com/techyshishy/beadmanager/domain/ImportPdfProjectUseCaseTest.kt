package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.pdf.BeadToolColorKeyExtractor
import com.techyshishy.beadmanager.data.pdf.BeadToolPdfParser
import com.techyshishy.beadmanager.data.pdf.PdfParseException
import com.techyshishy.beadmanager.data.pdf.PdfProject
import com.techyshishy.beadmanager.data.pdf.PdfRow
import com.techyshishy.beadmanager.data.pdf.PdfStep
import com.techyshishy.beadmanager.data.pdf.PdfVariant
import com.techyshishy.beadmanager.data.pdf.XlsmPdfParser
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectImageRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.data.pdf.PdfTextExtractor
import com.techyshishy.beadmanager.data.pdf.PdfImportDiagnosticsWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class ImportPdfProjectUseCaseTest {

    private val uri: Uri = mockk(relaxed = true)

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Default mock that returns a single blank page — sufficient for all tests where the
     * parsers are mocked. Avoids PDFBox resource loading (GlyphList) which fails in JVM
     * unit tests.
     */
    private fun textExtractor(pages: List<String> = listOf("")): PdfTextExtractor = mockk {
        coEvery { extract(any(), any()) } returns pages
    }

    private fun textExtractorThrowingNotPdf(): PdfTextExtractor = mockk {
        coEvery { extract(any(), any()) } throws
            PdfParseException.NotPdf(java.io.IOException("not a PDF"))
    }

    private fun catalogWith(vararg codes: String): CatalogRepository = mockk {
        val beadMap: Map<String, BeadEntity> = codes.associateWith { mockk(relaxed = true) }
        coEvery { allBeadsAsMap() } returns beadMap
    }

    private fun successProjectRepo(): ProjectRepository = mockk {
        coEvery { createProject(any()) } returns "proj-123"
        coEvery { writeProjectGrid("proj-123", any()) } returns Unit
        coEvery { setProjectImageUrl(any(), any()) } returns Unit
    }

    private fun buildUseCase(
        textExtractor: PdfTextExtractor = this.textExtractor(),
        catalog: CatalogRepository = catalogWith(),
        projectRepository: ProjectRepository = successProjectRepo(),
        projectImageRepository: ProjectImageRepository = mockk(relaxed = true),
        generatePreview: GenerateProjectPreviewUseCase = mockk(relaxed = true),
        beadToolParser: BeadToolPdfParser = mockk(),
        xlsmParser: XlsmPdfParser = mockk(),
        colorKeyExtractor: BeadToolColorKeyExtractor = mockk(),
        diagnosticsWriter: PdfImportDiagnosticsWriter = mockk(relaxed = true),
        contentResolver: ContentResolver = mockk(relaxed = true),
    ) = ImportPdfProjectUseCase(
        contentResolver = contentResolver,
        catalogRepository = catalog,
        projectRepository = projectRepository,
        projectImageRepository = projectImageRepository,
        generateProjectPreview = generatePreview,
        beadToolParser = beadToolParser,
        xlsmParser = xlsmParser,
        colorKeyExtractor = colorKeyExtractor,
        textExtractor = textExtractor,
        diagnosticsWriter = diagnosticsWriter,
    )

    /**
     * Returns a [ContentResolver] mock whose [ContentResolver.query] call yields a cursor
     * with the given [displayName] in [OpenableColumns.DISPLAY_NAME].
     */
    private fun contentResolverWithDisplayName(displayName: String): ContentResolver = mockk {
        val cursor: Cursor = mockk(relaxed = true) {
            every { moveToFirst() } returns true
            every { getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { getString(0) } returns displayName
        }
        every { query(any(), any(), null, null, null) } returns cursor
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private val twoRowPdfProject = PdfProject(
        colorMapping = emptyMap(),
        rows = listOf(
            PdfRow(id = 1, steps = listOf(PdfStep(3, "A"), PdfStep(2, "B"))),
            PdfRow(id = 2, steps = listOf(PdfStep(4, "A"))),
        ),
    )

    private val xlsmProject = PdfProject(
        colorMapping = mapOf("A" to "DB-0001", "B" to "DB-0002"),
        rows = listOf(
            PdfRow(id = 1, steps = listOf(PdfStep(3, "A"), PdfStep(2, "B"))),
        ),
    )

    private val ocrColorMap = mapOf("A" to "DB-0001", "B" to "DB-0002")

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `returns NotPdf when text extractor throws NotPdf`() = runTest {
        val result = buildUseCase(textExtractor = textExtractorThrowingNotPdf()).detect(uri)
        assertTrue("Expected NotPdf but got $result", result is ImportResult.Failure.NotPdf)
    }

    @Test
    fun `returns NoPatternFound when both parsers throw NoPatternFound`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } throws PdfParseException.NoPatternFound() }
        val result = buildUseCase(beadToolParser = btParser, xlsmParser = xParser).detect(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when XLSM parser throws IncompleteColorMapping`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk {
            every { parse(any(), any()) } throws PdfParseException.IncompleteColorMapping(listOf("X"))
        }
        val result = buildUseCase(beadToolParser = btParser, xlsmParser = xParser).detect(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when BeadTool color key page is absent`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } returns listOf(twoRowPdfProject) }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns -1
        }
        val result = buildUseCase(beadToolParser = btParser, colorKeyExtractor = extractor).detect(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when OCR result is missing a color letter`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } returns listOf(twoRowPdfProject) }
        // OCR only returned mapping for A; B is used in rows but absent.
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            every { parseColorKeyText(any()) } returns emptyMap()  // text path yields nothing → fall through to OCR
            coEvery { extractColorKey(any(), any(), any(), any()) } returns mapOf("A" to "DB-0001")
        }
        val result = buildUseCase(beadToolParser = btParser, colorKeyExtractor = extractor).detect(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when extractColorKey throws unexpected exception`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } returns listOf(twoRowPdfProject) }
        // Simulates ML Kit ExecutionException or any other extractor failure.
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            every { parseColorKeyText(any()) } returns emptyMap()  // text path yields nothing → fall through to OCR
            coEvery { extractColorKey(any(), any(), any(), any()) } throws RuntimeException("ML Kit failed")
        }
        val result = buildUseCase(beadToolParser = btParser, colorKeyExtractor = extractor).detect(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns UnrecognizedCodes when a DB code is not in the catalog`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        // xlsmProject references DB-0001 and DB-0002; catalog only has DB-0001.
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001"),
            xlsmParser = xParser,
            beadToolParser = btParser,
        ).detect(uri)
        assertTrue("Expected UnrecognizedCodes but got $result", result is ImportResult.Failure.UnrecognizedCodes)
        assertEquals(listOf("DB-0002"), (result as ImportResult.Failure.UnrecognizedCodes).codes)
    }

    @Test
    fun `succeeds and uses filename when XLSM parser returns a project`() = runTest {
        // The import should succeed with the filename-derived name regardless of
        // what the parser returns — the in-document title is not used.
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            beadToolParser = btParser,
            xlsmParser = xParser,
            contentResolver = contentResolverWithDisplayName("LandscapeTapestry.pdf"),
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("LandscapeTapestry", (result as ImportResult.Success).name)
    }

    @Test
    fun `returns WriteError when createProject throws`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } throws RuntimeException("Firestore unavailable")
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = repo,
            beadToolParser = btParser,
            xlsmParser = xParser,
        ).detect(uri)
        assertTrue("Expected WriteError but got $result", result is ImportResult.Failure.WriteError)
    }

    @Test
    fun `returns WriteError and deletes project when writeProjectGrid throws`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } returns "proj-abc"
            coEvery { writeProjectGrid("proj-abc", any()) } throws RuntimeException("grid write failed")
            coEvery { deleteProject("proj-abc") } returns Unit
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = repo,
            beadToolParser = btParser,
            xlsmParser = xParser,
        ).detect(uri)
        assertTrue("Expected WriteError but got $result", result is ImportResult.Failure.WriteError)
        coVerify { repo.deleteProject("proj-abc") }
    }

    @Test
    fun `uses text color key when it covers all required letters and skips OCR`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } returns listOf(twoRowPdfProject) }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            every { parseColorKeyText(any()) } returns ocrColorMap  // full map from text extraction
            coEvery { extractColorKey(any(), any(), any(), any()) } throws RuntimeException("should not be called")
        }
        val capturedEntry = slot<ProjectEntry>()
        val repo: ProjectRepository = mockk {
            coEvery { createProject(capture(capturedEntry)) } returns "proj-text"
            coEvery { writeProjectGrid("proj-text", any()) } returns Unit
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = repo,
            beadToolParser = btParser,
            colorKeyExtractor = extractor,
            contentResolver = contentResolverWithDisplayName("Text Key Pattern.pdf"),
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals(ocrColorMap, capturedEntry.captured.colorMapping)
        coVerify(exactly = 0) { extractor.extractColorKey(any(), any(), any(), any()) }
    }

    /**
     * Regression test for #52 (CurlyFlowers2LighterCoverPeyote.pdf).
     *
     * The PDF has a selectable-text color key — 9 colors A–I including letter I
     * (DB-2138). Before the text-first path was added to detectAndParse (commit
     * 7146476), the code went straight to OCR, which dropped letter I from its
     * output, causing "missing letters [I]".
     *
     * With the text-first path in place, parseColorKeyText covers all 9 letters
     * and OCR is never invoked.
     */
    @Test
    fun `BeadTool PDF with selectable 9-color key including I imports without OCR (regression #52)`() = runTest {
        val nineColorProject = PdfProject(
            colorMapping = emptyMap(),
            rows = listOf(
                PdfRow(
                    id = 1,
                    steps = listOf(
                        PdfStep(1, "A"), PdfStep(1, "B"), PdfStep(1, "C"),
                        PdfStep(1, "D"), PdfStep(1, "E"), PdfStep(1, "F"),
                        PdfStep(1, "G"), PdfStep(1, "H"), PdfStep(1, "I"),
                    ),
                ),
            ),
        )
        // Normalized codes matching what parseColorKeyText returns (no hyphen, 4-digit zero-padded).
        val textColorKey = mapOf(
            "A" to "DB0200", "B" to "DB0310", "C" to "DB0659", "D" to "DB0661",
            "E" to "DB1132", "F" to "DB1133", "G" to "DB2117", "H" to "DB2126", "I" to "DB2138",
        )
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } returns listOf(nineColorProject) }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 1
            every { parseColorKeyText(any()) } returns textColorKey
            coEvery { extractColorKey(any(), any(), any(), any()) } throws RuntimeException("OCR must not be invoked")
        }
        val capturedEntry = slot<ProjectEntry>()
        val repo: ProjectRepository = mockk {
            coEvery { createProject(capture(capturedEntry)) } returns "proj-52"
            coEvery { writeProjectGrid("proj-52", any()) } returns Unit
        }
        val result = buildUseCase(
            // Two pages so pages[1] is valid when colorKeyPageIndex = 1.
            textExtractor = textExtractor(pages = listOf("word chart page", "color key page")),
            catalog = catalogWith(
                "DB0200", "DB0310", "DB0659", "DB0661",
                "DB1132", "DB1133", "DB2117", "DB2126", "DB2138",
            ),
            beadToolParser = btParser,
            colorKeyExtractor = extractor,
            projectRepository = repo,
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        // Letter I→DB2138 must be present in the persisted color mapping.
        assertEquals(textColorKey, capturedEntry.captured.colorMapping)
        coVerify(exactly = 0) { extractor.extractColorKey(any(), any(), any(), any()) }
    }

    @Test
    fun `BeadTool happy path writes correct ProjectEntry and grid rows`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } returns listOf(twoRowPdfProject) }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            every { parseColorKeyText(any()) } returns emptyMap()  // text path yields nothing → fall through to OCR
            coEvery { extractColorKey(any(), any(), any(), any()) } returns ocrColorMap
        }
        val capturedEntry = slot<ProjectEntry>()
        val capturedRows = slot<List<ProjectRgpRow>>()
        val repo: ProjectRepository = mockk {
            coEvery { createProject(capture(capturedEntry)) } returns "proj-bt"
            coEvery { writeProjectGrid("proj-bt", capture(capturedRows)) } returns Unit
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = repo,
            beadToolParser = btParser,
            colorKeyExtractor = extractor,
            contentResolver = contentResolverWithDisplayName("My BeadTool Pattern.pdf"),
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("My BeadTool Pattern", (result as ImportResult.Success).name)
        // colorMapping in ProjectEntry is the OCR result (letter → DB code)
        assertEquals(ocrColorMap, capturedEntry.captured.colorMapping)
        // Grid rows: step.description holds the color letter; id is 1-based index within row
        val rows = capturedRows.captured
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].id)
        assertEquals(1, rows[0].steps[0].id)
        assertEquals("A", rows[0].steps[0].description)
        assertEquals(3, rows[0].steps[0].count)
        assertEquals(2, rows[0].steps[1].id)
        assertEquals("B", rows[0].steps[1].description)
        assertEquals(2, rows[0].steps[1].count)
    }

    @Test
    fun `XLSM happy path writes correct ProjectEntry and grid rows`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val capturedEntry = slot<ProjectEntry>()
        val capturedRows = slot<List<ProjectRgpRow>>()
        val repo: ProjectRepository = mockk {
            coEvery { createProject(capture(capturedEntry)) } returns "proj-xlsm"
            coEvery { writeProjectGrid("proj-xlsm", capture(capturedRows)) } returns Unit
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = repo,
            beadToolParser = btParser,
            xlsmParser = xParser,
            contentResolver = contentResolverWithDisplayName("My XLSM Pattern.pdf"),
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("My XLSM Pattern", (result as ImportResult.Success).name)
        assertEquals(mapOf("A" to "DB-0001", "B" to "DB-0002"), capturedEntry.captured.colorMapping)
        val rows = capturedRows.captured
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].steps[0].id)
        assertEquals("A", rows[0].steps[0].description)
        assertEquals(3, rows[0].steps[0].count)
        assertEquals(2, rows[0].steps[1].id)
        assertEquals("B", rows[0].steps[1].description)
        assertEquals(2, rows[0].steps[1].count)
    }

    // ── deriveProjectName tests ───────────────────────────────────────────────

    /** Shared success setup used by filename-derivation tests. */
    private fun buildXlsmSuccessUseCase(contentResolver: ContentResolver): ImportPdfProjectUseCase {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        return buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            beadToolParser = btParser,
            xlsmParser = xParser,
            contentResolver = contentResolver,
        )
    }

    @Test
    fun `project name is derived from URI filename with extension stripped`() = runTest {
        val result = buildXlsmSuccessUseCase(
            contentResolverWithDisplayName("My Pattern.pdf"),
        ).detect(uri)
        assertEquals("My Pattern", (result as ImportResult.Success).name)
    }

    @Test
    fun `project name strips PDF extension case-insensitively`() = runTest {
        val result = buildXlsmSuccessUseCase(
            contentResolverWithDisplayName("Pattern.PDF"),
        ).detect(uri)
        assertEquals("Pattern", (result as ImportResult.Success).name)
    }

    @Test
    fun `project name is trimmed of surrounding whitespace`() = runTest {
        val result = buildXlsmSuccessUseCase(
            contentResolverWithDisplayName("  My Pattern.pdf  "),
        ).detect(uri)
        assertEquals("My Pattern", (result as ImportResult.Success).name)
    }

    @Test
    fun `project name falls back to Imported Project when cursor is null`() = runTest {
        val nullCursorResolver: ContentResolver = mockk {
            every { query(any(), any(), null, null, null) } returns null
        }
        val result = buildXlsmSuccessUseCase(nullCursorResolver).detect(uri)
        assertEquals("Imported Project", (result as ImportResult.Success).name)
    }

    @Test
    fun `project name falls back to Imported Project when display name is blank after stripping`() = runTest {
        val result = buildXlsmSuccessUseCase(
            contentResolverWithDisplayName(".pdf"),
        ).detect(uri)
        assertEquals("Imported Project", (result as ImportResult.Success).name)
    }

    @Test
    fun `project name falls back to Imported Project when query throws`() = runTest {
        val throwingResolver: ContentResolver = mockk {
            every { query(any(), any(), null, null, null) } throws RuntimeException("ContentProvider died")
        }
        val result = buildXlsmSuccessUseCase(throwingResolver).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("Imported Project", (result as ImportResult.Success).name)
    }

    // ── cover image generation tests ──────────────────────────────────────────

    @Test
    fun `PDF import renders grid and uploads cover on success`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val renderedBytes = byteArrayOf(7, 8, 9)
        val previewUseCase: GenerateProjectPreviewUseCase = mockk {
            coEvery { render(any(), any(), any()) } returns renderedBytes
        }
        val imageRepo: ProjectImageRepository = mockk {
            coEvery { uploadCoverBytes("proj-123", renderedBytes) } returns "https://cdn.example.com/pdf-cover"
        }
        val projectRepository: ProjectRepository = mockk {
            coEvery { createProject(any()) } returns "proj-123"
            coEvery { writeProjectGrid("proj-123", any()) } returns Unit
            coEvery { setProjectImageUrl("proj-123", "https://cdn.example.com/pdf-cover") } returns Unit
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = projectRepository,
            projectImageRepository = imageRepo,
            generatePreview = previewUseCase,
            beadToolParser = btParser,
            xlsmParser = xParser,
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        coVerify(exactly = 1) { previewUseCase.render(any(), any(), any()) }
        coVerify(exactly = 1) { imageRepo.uploadCoverBytes("proj-123", renderedBytes) }
        coVerify(exactly = 1) { projectRepository.setProjectImageUrl("proj-123", "https://cdn.example.com/pdf-cover") }
    }

    @Test
    fun `PDF cover render failure still returns Success`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parseAllVariants(any(), any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val previewUseCase: GenerateProjectPreviewUseCase = mockk {
            coEvery { render(any(), any(), any()) } throws RuntimeException("OOM during render")
        }
        val imageRepo: ProjectImageRepository = mockk(relaxed = true)
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectImageRepository = imageRepo,
            generatePreview = previewUseCase,
            beadToolParser = btParser,
            xlsmParser = xParser,
        ).detect(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        coVerify(exactly = 0) { imageRepo.uploadCoverBytes(any(), any()) }
    }

    @Test
    fun `detect returns PendingVariantChoice when BeadTool parser returns two variants`() = runTest {
        val variant1 = PdfProject(emptyMap(), listOf(PdfRow(1, listOf(PdfStep(26, "A")))))
        val variant2 = PdfProject(emptyMap(), listOf(PdfRow(1, listOf(PdfStep(27, "A")))))
        val btParser: BeadToolPdfParser = mockk {
            every { parseAllVariants(any(), any()) } returns listOf(variant1, variant2)
        }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            every { parseColorKeyText(any()) } returns mapOf("A" to "DB-0001")
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001"),
            beadToolParser = btParser,
            colorKeyExtractor = extractor,
            contentResolver = contentResolverWithDisplayName("MyPattern.pdf"),
        ).detect(uri)
        assertTrue("Expected PendingVariantChoice but got $result", result is ImportResult.PendingVariantChoice)
        val pending = result as ImportResult.PendingVariantChoice
        assertEquals("MyPattern", pending.fileName)
        assertEquals(mapOf("A" to "DB-0001"), pending.colorMapping)
        assertEquals(2, pending.variants.size)
        assertEquals("Variant 1", pending.variants[0].label)
        assertEquals(26, pending.variants[0].rows.first().steps.sumOf { it.count })
        assertEquals("Variant 2", pending.variants[1].label)
        assertEquals(27, pending.variants[1].rows.first().steps.sumOf { it.count })
    }

    // ── importVariants tests ──────────────────────────────────────────────────

    private fun makePending(vararg variantRows: Int): ImportResult.PendingVariantChoice {
        val variants = variantRows.mapIndexed { i, beadCount ->
            PdfVariant(
                label = "Variant ${i + 1}",
                rows = listOf(PdfRow(1, listOf(PdfStep(beadCount, "A")))),
            )
        }
        return ImportResult.PendingVariantChoice(
            fileName = "MyPattern",
            colorMapping = mapOf("A" to "DB-0001"),
            variants = variants,
        )
    }

    @Test
    fun `importVariants returns Success when exactly one variant is selected`() = runTest {
        val pending = makePending(26, 27)
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } returns "proj-1"
            coEvery { writeProjectGrid("proj-1", any()) } returns Unit
        }
        val useCase = buildUseCase(
            catalog = catalogWith("DB-0001"),
            projectRepository = repo,
        )
        val result = useCase.importVariants(pending, listOf(pending.variants[0]))
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("MyPattern (Variant 1)", (result as ImportResult.Success).name)
    }

    @Test
    fun `importVariants returns MultiSuccess when two variants are selected`() = runTest {
        val pending = makePending(26, 27)
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } returnsMany listOf("proj-1", "proj-2")
            coEvery { writeProjectGrid("proj-1", any()) } returns Unit
            coEvery { writeProjectGrid("proj-2", any()) } returns Unit
        }
        val useCase = buildUseCase(
            catalog = catalogWith("DB-0001"),
            projectRepository = repo,
        )
        val result = useCase.importVariants(pending, pending.variants)
        assertTrue("Expected MultiSuccess but got $result", result is ImportResult.MultiSuccess)
        assertEquals("proj-1", (result as ImportResult.MultiSuccess).firstProjectId)
    }

    @Test
    fun `importVariants returns WriteError and leaves no orphan when first write fails`() = runTest {
        val pending = makePending(26, 27)
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } throws RuntimeException("Firestore unavailable")
            coEvery { deleteProject(any()) } returns Unit
        }
        val useCase = buildUseCase(
            catalog = catalogWith("DB-0001"),
            projectRepository = repo,
        )
        val result = useCase.importVariants(pending, pending.variants)
        assertTrue("Expected WriteError but got $result", result is ImportResult.Failure.WriteError)
        // Nothing was created, so nothing should be deleted.
        coVerify(exactly = 0) { repo.deleteProject(any()) }
    }

    @Test
    fun `importVariants rolls back first variant when second write fails`() = runTest {
        val pending = makePending(26, 27)
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } returnsMany listOf("proj-1", "proj-2")
            coEvery { writeProjectGrid("proj-1", any()) } returns Unit
            coEvery { writeProjectGrid("proj-2", any()) } throws RuntimeException("grid write failed")
            coEvery { deleteProject(any()) } returns Unit
        }
        val useCase = buildUseCase(
            catalog = catalogWith("DB-0001"),
            projectRepository = repo,
        )
        val result = useCase.importVariants(pending, pending.variants)
        assertTrue("Expected WriteError but got $result", result is ImportResult.Failure.WriteError)
        // writeSingleVariant deletes its own partial write (proj-2); importVariants rolls back proj-1.
        coVerify(exactly = 1) { repo.deleteProject("proj-1") }
        coVerify(exactly = 1) { repo.deleteProject("proj-2") }
    }
}
