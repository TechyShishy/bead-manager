package com.techyshishy.beadmanager.domain

sealed class ImportResult {
    data class Success(val projectId: String, val name: String) : ImportResult()
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
