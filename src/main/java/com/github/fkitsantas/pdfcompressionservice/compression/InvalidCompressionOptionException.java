package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Thrown when a per-request {@link CompressionOptions} override is out of range
 * (e.g. a target DPI above the accepted maximum, or a JPEG quality outside
 * {@code [0.0, 1.0]}). Distinct from {@link InvalidPdfException} (the uploaded
 * file is unprocessable) and {@link PdfCompressionException} (an internal
 * failure): this signals bad client input in the request parameters, so the web
 * layer maps it to {@code 400 Bad Request}. The message is the validation
 * message from the underlying setter and is safe to return to the client.
 */
public class InvalidCompressionOptionException extends RuntimeException {

    public InvalidCompressionOptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
