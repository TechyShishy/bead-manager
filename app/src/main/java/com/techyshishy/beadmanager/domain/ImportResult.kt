package com.techyshishy.beadmanager.domain

import com.techyshishy.beadmanager.data.pdf.PdfVariant

sealed class ImportResult {
    data class Success(val projectId: String, val name: String) : ImportResult()

    /**
     * Returned by [ImportPdfProjectUseCase.detect] when the PDF contains two or more chart
     * variants. No Firestore write has occurred yet. The caller must present variant-selection
     * UI and then invoke [ImportPdfProjectUseCase.importVariants].
     *
     * [colorMapping] is shared across all variants — color-key extraction runs once on the
     * full document.
     */
    data class PendingVariantChoice(
        val fileName: String,
        val colorMapping: Map<String, String>,
        val variants: List<PdfVariant>,
    ) : ImportResult()

    /** Returned by [ImportPdfProjectUseCase.importVariants] when more than one variant was written. */
    data class MultiSuccess(val firstProjectId: String, val firstName: String) : ImportResult()
    sealed class Failure : ImportResult() {
        data object NotGzip : Failure()
        data object InvalidJson : Failure()
        /** The file's colorMapping contains only hex colors — no Delica codes to import. */
        data object NoDelicaCodes : Failure()
        /** One or more DB codes in colorMapping were not found in the local catalog. */
        data class UnrecognizedCodes(val codes: List<String>) : Failure()
        /** Project write to Firestore failed. Any partially created document has been cleaned up. */
        data object WriteError : Failure()
        /** The selected file is not a valid PDF (used by PDF import path). */
        data object NotPdf : Failure()
        /**
         * The PDF is readable but contains no recognizable bead pattern, or the color key is
         * incomplete (used by PDF import path).
         */
        data object NoPatternFound : Failure()
    }
}
