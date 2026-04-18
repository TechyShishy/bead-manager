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
   Pull the file directly to `debug-pdfs/` using `dd` piped through `run-as`:
   ```
   adb shell "run-as com.techyshishy.beadmanager.debug dd if='cache/pdf-debug/<filename>.txt'" | dd of="debug-pdfs/<filename>.txt"
   ```
   > **Why `dd` instead of `adb pull`?** On Android 10+, `run-as` can no longer copy
   > files to `/sdcard/`, so the classic `cp /sdcard/ → adb pull` workaround no longer
   > works. `dd` reads the file as the app's UID and pipes it directly to the host
   > without touching shared storage. See [tuanchauict's writeup](https://iamtuna.org/2023-10-08/use-adb-backup-restore-local-data-2)
   > and [jevakallio's gist](https://gist.github.com/jevakallio/452c54ef613792f25e45663ab2db117b) for background.
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
