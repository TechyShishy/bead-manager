---
name: pdf-debug
description: 'Debug a failing PDF import in Bead Manager. Use when a PDF does not import, produces an empty project, garbled bead codes, or incorrect row data. Requires the failing PDF dropped in debug-pdfs/ and the diagnostics report pulled from the device. Walks through reading the report, classifying the failure, tracing parser logic, writing a failing test, and fixing the parser.'
---

# Skill: PDF Import Debugging

## When to Use

Use this skill when a PDF fails to import into Bead Manager — or produces garbled, empty, or incorrect project output — and you need to diagnose what went wrong.

## What You Need from the User

Before starting, ask for:

1. **The failing PDF** — dropped into `debug-pdfs/` in the project root.
2. **The diagnostics report** — a `pdf-import-debug-*.txt` file from the device. Retrieval instructions are in `debug-pdfs/README.md`.

Both are required. Do not attempt a diagnosis with only one.

## Step 1 — Read the Diagnostics Report

Open the `pdf-import-debug-*.txt` file and locate:

- `failureReason` — the terminal failure path in `ImportPdfProjectUseCase`. Start here.
- `beadToolAttempted` / `xlsmAttempted` — which parsers ran.
- If BeadTool ran:
  - `beadToolStrippedText` — raw text after header/footer strip.
  - `beadToolCleanedText` — after normalization.
  - `beadToolContinuedText` — after joining hyphenated lines.
  - `beadToolRowBlockFound` — whether the row-data block was located.
  - `beadToolRowBlock` — the raw extracted block (if found).
  - `beadToolRowCount` — parsed row count.
- If XLSM ran:
  - `ocrBlockCount` / `ocrBlockTexts` — ML Kit OCR results from the color key page.
  - `ocrColorMap` — letter-to-beadCode mappings extracted from color key.
  - `xlsmColorMap` — same map from a second extraction stage.
  - `xlsmRowCount` — number of grid rows parsed.
  - `xlsmMissingLetters` — letters in rows with no color-map entry.

## Step 2 — Classify the Failure

| `failureReason` pattern | Likely cause |
|---|---|
| `NoPatternFound` | Neither parser recognized the PDF structure. Check `beadToolStrippedText` — is there actual text, or only whitespace? If whitespace, the PDF may be scanned/image-based. Check `ocrBlockCount` — did ML Kit find any text blocks? |
| `IncompleteColorMapping` | XLSM parser ran but `xlsmMissingLetters` is non-empty. The color key page wasn't parsed fully. Check `ocrBlockTexts` for the raw OCR output — look for garbled letter labels or missing color entries. |
| `NoBeadsFound` | Parser ran and produced a project but the bead list is empty. Check `beadToolRowBlock` — are the rows present but failing to parse? |
| `BlankName` | XLSM produced a project with no name. The project title field in the PDF wasn't recognized. |
| `WriteError` | Parse succeeded but Firestore write failed. This is a connectivity or permission issue, not a PDF problem. |

## Step 3 — Inspect the PDF Directly

Read the PDF by opening it in a viewer or by examining the pages extracted in `beadToolStrippedText`. Look for:

- Whether text is selectable (text-based PDF vs. scanned image).
- Whether the header format, legend format, or row format differs from what the parser expects.
- Any unusual encoding, multi-column layout, or non-standard characters.

## Step 4 — Locate the Relevant Parser Code

| Parser | File |
|---|---|
| BeadTool export | `app/src/main/java/com/techyshishy/beadmanager/data/pdf/BeadToolPdfParser.kt` |
| XLSM/MacroBead export | `app/src/main/java/com/techyshishy/beadmanager/data/pdf/XlsmPdfParser.kt` |
| Color key OCR | `app/src/main/java/com/techyshishy/beadmanager/data/pdf/BeadToolColorKeyExtractor.kt` |
| Text extraction | `app/src/main/java/com/techyshishy/beadmanager/data/pdf/PdfTextExtractor.kt` |
| Orchestration | `app/src/main/java/com/techyshishy/beadmanager/domain/ImportPdfProjectUseCase.kt` |
| Diagnostics model | `app/src/main/java/com/techyshishy/beadmanager/data/pdf/PdfImportDiagnosticsCollector.kt` |
| Diagnostics writer | `app/src/main/java/com/techyshishy/beadmanager/data/pdf/PdfImportDiagnosticsWriter.kt` |

Read the relevant parser before modifying it.

## Step 5 — Identify the Root Cause

Cross-reference the diagnostics fields with the parser logic:

- If `beadToolStrippedText` has content but `beadToolRowBlockFound = false`, find the regex or delimiter that anchors the row block and compare it against the actual text.
- If `ocrColorMap` is empty or partial, inspect `ocrBlockTexts` — the raw OCR strings may show why the letter-to-color mapping failed (e.g., OCR misread the letter label, extra whitespace, unexpected formatting).
- If `xlsmMissingLetters` is populated, check whether those letters appear in `ocrColorMap` — if not, they weren't parsed from the color key.

## Step 6 — Write a Failing Test

Before touching parser logic, add a test in:

- `BeadToolPdfParserTest` for BeadTool issues
- `XlsmPdfParserTest` for XLSM issues
- `BeadToolColorKeyExtractorTest` for OCR/color key issues
- `ImportPdfProjectUseCaseTest` for orchestration issues

If the input text is too long, store it as a fixture in `src/test/resources/`.

## Step 7 — Fix the Parser

Make the minimal change that makes the new test pass without breaking existing tests. Run:

```
./gradlew :app:testDebugUnitTest --tests "com.techyshishy.beadmanager.data.pdf.*" \
  --tests "com.techyshishy.beadmanager.domain.ImportPdfProjectUseCaseTest" --no-daemon
```

## Step 8 — Verify on Device

Install the updated build and import the same PDF. Retrieve a new diagnostics report and confirm the failure reason is gone and the project imported correctly.

## Architecture Notes

- `PdfImportDiagnosticsCollector` is created fresh per `import()` call — one report per import attempt.
- All parser methods accept an optional `diagnostics: PdfImportDiagnosticsCollector?` parameter — passing `null` disables collection (used in tests that don't need it).
- The writer writes to `<cacheDir>/pdf-debug/`. The `adb pull` path is logged at `D/PdfImport` level.
- The diagnostics file is never sent to any server. It lives only on-device until pulled.
- The `debug-pdfs/` directory is `.gitignore`d for `*.pdf` — PDF files are never committed.
