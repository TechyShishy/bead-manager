# PDF Debug Drop Zone

Drop failing PDFs here when debugging import issues. Files in this directory
are ignored by git (see the `.gitignore` entry for `debug-pdfs/*.pdf`).

## Workflow

1. Trigger a failing import on the device.
2. Retrieve the diagnostic report:
   ```
   # List available reports (paths are relative to the app home directory)
   adb shell run-as com.techyshishy.beadmanager.debug ls cache/pdf-debug/
   ```
   Copy the file you want to `/sdcard/`, then pull it:
   ```
   adb shell run-as com.techyshishy.beadmanager.debug cp cache/pdf-debug/<filename>.txt /sdcard/
   adb pull /sdcard/<filename>.txt debug-pdfs/
   ```
   > **Why the detour through `/sdcard/`?** `adb pull` runs as the adb user, which
   > doesn't have permission to read `/data/user/0/` or `/data/data/` directly, even
   > on debug builds. `run-as` executes the `cp` as the app's own UID, which does
   > have access. See [jevakallio's gist](https://gist.github.com/jevakallio/452c54ef613792f25e45663ab2db117b) for the full explanation.
3. Drop the failing PDF into this directory:
   ```
   cp ~/Downloads/failing-pattern.pdf debug-pdfs/
   ```
4. Hand both the PDF path and the `.txt` report to Copilot and invoke the
   `pdf-debug` skill: *"This PDF fails to import. Debug it."*

## What the diagnostic report contains

- Full per-page text as extracted by PDFBox (the raw material for parsers).
- BeadTool 4 pipeline: stripped text → cleaned text → joined text → row block.
- XLSM pipeline: extracted color map, parsed row count, missing letters.
- OCR pipeline: ML Kit block count, raw block texts, recovered color map.
- Terminal failure reason and any unrecognized catalog codes.

## What Copilot will do

Given the PDF and the report, Copilot will:
1. Identify which parser ran, which step failed, and why.
2. Produce a minimal failing unit test in the appropriate `*Test.kt` file.
3. Fix the parser to handle the new format.
4. Verify the fix against the new test and all existing tests.
