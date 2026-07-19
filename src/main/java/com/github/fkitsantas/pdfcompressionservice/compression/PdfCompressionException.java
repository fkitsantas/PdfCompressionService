package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Thrown by {@link PdfCompressionEngine} when a structurally valid PDF could
 * not be processed (e.g. an I/O failure while re-encoding an image, or an
 * unexpected internal error). Distinguished from {@link InvalidPdfException}
 * so callers can map this to a 5xx response rather than a 422.
 */
public class PdfCompressionException extends RuntimeException {

    public PdfCompressionException(String message) {
        super(message);
    }

    public PdfCompressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
