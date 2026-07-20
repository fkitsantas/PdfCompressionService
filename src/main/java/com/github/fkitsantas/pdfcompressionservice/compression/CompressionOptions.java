package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Optional per-request overrides for the subset of {@link PdfCompressionProperties}
 * that a caller may reasonably want to vary per upload: how aggressively images
 * are downsampled/recompressed and what metadata is kept. Any field left
 * {@code null} falls back to the service-configured default; {@link #NONE} means
 * "use the defaults for everything".
 *
 * <p>Service-level concerns (thread pools, the admission gate, the stream cache)
 * are deliberately <b>not</b> overridable per request, so a client cannot affect
 * the memory/concurrency safety of the whole service.
 *
 * <p>Overrides are applied through the same validating setters as the configured
 * properties (see {@link #applyTo(PdfCompressionProperties)}), so an out-of-range
 * value is rejected exactly as a misconfiguration would be, surfaced to the client
 * as an {@link InvalidCompressionOptionException}.
 */
public record CompressionOptions(Integer targetDpi, Float jpegQuality, Integer maxImageDimension,
                                 Boolean stripMetadata, Boolean deduplicateImages) {

    /** No overrides: every knob uses the service-configured default. */
    public static final CompressionOptions NONE = new CompressionOptions(null, null, null, null, null);

    /** Whether no override at all was supplied (so the base properties can be used as-is). */
    public boolean isEmpty() {
        return targetDpi == null && jpegQuality == null && maxImageDimension == null
                && stripMetadata == null && deduplicateImages == null;
    }

    /**
     * Applies the non-null overrides onto {@code target} (expected to be a copy of
     * the configured properties), reusing its validating setters. A rejected value
     * is rethrown as {@link InvalidCompressionOptionException} so the web layer can
     * map it to {@code 400 Bad Request}.
     */
    public void applyTo(PdfCompressionProperties target) {
        try {
            if (targetDpi != null) {
                target.setTargetDpi(targetDpi);
            }
            if (jpegQuality != null) {
                target.setJpegQuality(jpegQuality);
            }
            if (maxImageDimension != null) {
                target.setMaxImageDimension(maxImageDimension);
            }
            if (stripMetadata != null) {
                target.setStripMetadata(stripMetadata);
            }
            if (deduplicateImages != null) {
                target.setDeduplicateImages(deduplicateImages);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidCompressionOptionException(e.getMessage(), e);
        }
    }
}
