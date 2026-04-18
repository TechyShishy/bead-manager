package com.techyshishy.beadmanager.data.pdf

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Persists a [PdfImportDiagnosticsCollector] report to a file in the app's
 * private cache directory and logs the path so it can be retrieved with
 * `adb pull`.
 *
 * Files are named `pdf-import-debug-<timestamp>.txt` and written to
 * `<cacheDir>/pdf-debug/`. They accumulate across failed imports; older files
 * are **not** automatically purged here — clear the app cache to remove them.
 *
 * Only call [write] when an import fails; do not write on success.
 */
class PdfImportDiagnosticsWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "PdfImport"
    }

    /**
     * Writes [collector] to disk and logs the output path.
     *
     * Never throws — any I/O failure is logged as a warning and swallowed so
     * that the caller's failure-handling path is not disrupted.
     */
    fun write(collector: PdfImportDiagnosticsCollector) {
        try {
            val dir = File(context.cacheDir, "pdf-debug").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "pdf-import-debug-$ts.txt")
            file.writeText(collector.toReport())
            // adb pull cannot read /data/user/0/ directly — even on debug builds the adb
            // user lacks permission. The workaround is to copy via run-as (which executes
            // as the app's UID) to /sdcard/, then pull from there. Path passed to run-as
            // is relative to the app home directory (/data/user/0/<pkg>/).
            val relativePath = "cache/pdf-debug/${file.name}"
            Log.d(TAG, "PDF import diagnostics written: ${file.absolutePath}")
            Log.d(TAG, "Retrieve:")
            Log.d(TAG, "  adb shell run-as ${context.packageName} cp $relativePath /sdcard/")
            Log.d(TAG, "  adb pull /sdcard/${file.name} debug-pdfs/")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write PDF import diagnostics: ${e.message}")
        }
    }
}
