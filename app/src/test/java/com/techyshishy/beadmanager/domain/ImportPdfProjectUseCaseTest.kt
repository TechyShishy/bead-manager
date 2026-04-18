package com.techyshishy.beadmanager.domain

import android.net.Uri
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.pdf.BeadToolColorKeyExtractor
import com.techyshishy.beadmanager.data.pdf.BeadToolPdfParser
import com.techyshishy.beadmanager.data.pdf.PdfParseException
import com.techyshishy.beadmanager.data.pdf.PdfProject
import com.techyshishy.beadmanager.data.pdf.PdfRow
import com.techyshishy.beadmanager.data.pdf.PdfStep
import com.techyshishy.beadmanager.data.pdf.XlsmPdfParser
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.data.pdf.PdfTextExtractor
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
    }

    private fun buildUseCase(
        textExtractor: PdfTextExtractor = this.textExtractor(),
        catalog: CatalogRepository = catalogWith(),
        projectRepository: ProjectRepository = successProjectRepo(),
        beadToolParser: BeadToolPdfParser = mockk(),
        xlsmParser: XlsmPdfParser = mockk(),
        colorKeyExtractor: BeadToolColorKeyExtractor = mockk(),
    ) = ImportPdfProjectUseCase(
        contentResolver = mockk(relaxed = true),
        catalogRepository = catalog,
        projectRepository = projectRepository,
        beadToolParser = beadToolParser,
        xlsmParser = xlsmParser,
        colorKeyExtractor = colorKeyExtractor,
        textExtractor = textExtractor,
    )

    // ── fixtures ──────────────────────────────────────────────────────────────

    private val twoRowPdfProject = PdfProject(
        name = "Test Pattern",
        colorMapping = emptyMap(),
        rows = listOf(
            PdfRow(id = 1, steps = listOf(PdfStep(3, "A"), PdfStep(2, "B"))),
            PdfRow(id = 2, steps = listOf(PdfStep(4, "A"))),
        ),
    )

    private val xlsmProject = PdfProject(
        name = "XLSM Pattern",
        colorMapping = mapOf("A" to "DB-0001", "B" to "DB-0002"),
        rows = listOf(
            PdfRow(id = 1, steps = listOf(PdfStep(3, "A"), PdfStep(2, "B"))),
        ),
    )

    private val ocrColorMap = mapOf("A" to "DB-0001", "B" to "DB-0002")

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `returns NotPdf when text extractor throws NotPdf`() = runTest {
        val result = buildUseCase(textExtractor = textExtractorThrowingNotPdf()).import(uri)
        assertTrue("Expected NotPdf but got $result", result is ImportResult.Failure.NotPdf)
    }

    @Test
    fun `returns NoPatternFound when both parsers throw NoPatternFound`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } throws PdfParseException.NoPatternFound() }
        val result = buildUseCase(beadToolParser = btParser, xlsmParser = xParser).import(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when XLSM parser throws IncompleteColorMapping`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk {
            every { parse(any(), any()) } throws PdfParseException.IncompleteColorMapping(listOf("X"))
        }
        val result = buildUseCase(beadToolParser = btParser, xlsmParser = xParser).import(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when BeadTool color key page is absent`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } returns twoRowPdfProject }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns -1
        }
        val result = buildUseCase(beadToolParser = btParser, colorKeyExtractor = extractor).import(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when OCR result is missing a color letter`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } returns twoRowPdfProject }
        // OCR only returned mapping for A; B is used in rows but absent.
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            coEvery { extractColorKey(any(), any(), any()) } returns mapOf("A" to "DB-0001")
        }
        val result = buildUseCase(beadToolParser = btParser, colorKeyExtractor = extractor).import(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns NoPatternFound when extractColorKey throws unexpected exception`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } returns twoRowPdfProject }
        // Simulates ML Kit ExecutionException or any other extractor failure.
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            coEvery { extractColorKey(any(), any(), any()) } throws RuntimeException("ML Kit failed")
        }
        val result = buildUseCase(beadToolParser = btParser, colorKeyExtractor = extractor).import(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns UnrecognizedCodes when a DB code is not in the catalog`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
        // xlsmProject references DB-0001 and DB-0002; catalog only has DB-0001.
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001"),
            xlsmParser = xParser,
            beadToolParser = btParser,
        ).import(uri)
        assertTrue("Expected UnrecognizedCodes but got $result", result is ImportResult.Failure.UnrecognizedCodes)
        assertEquals(listOf("DB-0002"), (result as ImportResult.Failure.UnrecognizedCodes).codes)
    }

    @Test
    fun `returns NoPatternFound when parsed project has a blank name`() = runTest {
        val blankNameProject = xlsmProject.copy(name = "")
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns blankNameProject }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            beadToolParser = btParser,
            xlsmParser = xParser,
        ).import(uri)
        assertTrue("Expected NoPatternFound but got $result", result is ImportResult.Failure.NoPatternFound)
    }

    @Test
    fun `returns WriteError when createProject throws`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
        val xParser: XlsmPdfParser = mockk { every { parse(any(), any()) } returns xlsmProject }
        val repo: ProjectRepository = mockk {
            coEvery { createProject(any()) } throws RuntimeException("Firestore unavailable")
        }
        val result = buildUseCase(
            catalog = catalogWith("DB-0001", "DB-0002"),
            projectRepository = repo,
            beadToolParser = btParser,
            xlsmParser = xParser,
        ).import(uri)
        assertTrue("Expected WriteError but got $result", result is ImportResult.Failure.WriteError)
    }

    @Test
    fun `returns WriteError and deletes project when writeProjectGrid throws`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
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
        ).import(uri)
        assertTrue("Expected WriteError but got $result", result is ImportResult.Failure.WriteError)
        coVerify { repo.deleteProject("proj-abc") }
    }

    @Test
    fun `BeadTool happy path writes correct ProjectEntry and grid rows`() = runTest {
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } returns twoRowPdfProject }
        val extractor: BeadToolColorKeyExtractor = mockk {
            every { findColorKeyPageIndex(any()) } returns 0
            coEvery { extractColorKey(any(), any(), any()) } returns ocrColorMap
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
        ).import(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("Test Pattern", (result as ImportResult.Success).name)
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
        val btParser: BeadToolPdfParser = mockk { every { parse(any()) } throws PdfParseException.NoPatternFound() }
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
        ).import(uri)
        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        assertEquals("XLSM Pattern", (result as ImportResult.Success).name)
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
}
