package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Thrown by {@link PdfCompressionEngine} when the supplied bytes are not a
 * loadable PDF document (malformed, truncated, encrypted-without-password,
 * or otherwise rejected by the PDF parser). Distinguished from
 * {@link PdfCompressionException} so callers (e.g. the REST layer) can map
 * this to a 422 Unprocessable Entity response rather than a 5xx failure.
 */
public class InvalidPdfException extends RuntimeException {

    public InvalidPdfException(String message) {
        super(message);
    }

    public InvalidPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
