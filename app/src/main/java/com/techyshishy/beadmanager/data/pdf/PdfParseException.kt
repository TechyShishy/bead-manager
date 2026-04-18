package com.techyshishy.beadmanager.data.pdf

sealed class PdfParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The input stream is not a valid PDF (unreadable, corrupt, or wrong file type). */
    class NotPdf(cause: Throwable) : PdfParseException("Stream is not a valid PDF", cause)

    /** The PDF is readable but contains no recognizable bead pattern rows. */
    class NoPatternFound : PdfParseException("No recognizable pattern rows found in PDF")

    /** One or more color letters referenced in pattern rows have no color key entry. */
    class IncompleteColorMapping(val missingLetters: List<String>) :
        PdfParseException("Color letters $missingLetters have no entry in the color table")
}
