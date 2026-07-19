package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Immutable outcome of a single {@link PdfCompressionEngine#compress} call.
 *
 * <p>Carries both the resulting PDF bytes and the metrics/telemetry the REST
 * layer and logs surface to callers (sizes, per-image counters, timing, and
 * whether the original bytes were returned verbatim because recompression
 * did not yield a sufficiently smaller result).
 *
 * <p><b>Constructor parameter order (fixed contract):</b>
 * {@code requestId, originalBytes, compressedBytes, savedBytes, savedPercent,
 * pageCount, imagesInspected, imagesDownsampled, imagesRecompressed,
 * imagesUnchanged, profile, durationMillis, returnedOriginal, compressedPdf}.
 */
public final class CompressionResult {

    private final String requestId;
    private final long originalBytes;
    private final long compressedBytes;
    private final long savedBytes;
    private final double savedPercent;
    private final int pageCount;
    private final int imagesInspected;
    private final int imagesDownsampled;
    private final int imagesRecompressed;
    private final int imagesUnchanged;
    private final String profile;
    private final long durationMillis;
    private final boolean returnedOriginal;
    private final byte[] compressedPdf;

    public CompressionResult(String requestId,
                              long originalBytes,
                              long compressedBytes,
                              long savedBytes,
                              double savedPercent,
                              int pageCount,
                              int imagesInspected,
                              int imagesDownsampled,
                              int imagesRecompressed,
                              int imagesUnchanged,
                              String profile,
                              long durationMillis,
                              boolean returnedOriginal,
                              byte[] compressedPdf) {
        this.requestId = requestId;
        this.originalBytes = originalBytes;
        this.compressedBytes = compressedBytes;
        this.savedBytes = savedBytes;
        this.savedPercent = savedPercent;
        this.pageCount = pageCount;
        this.imagesInspected = imagesInspected;
        this.imagesDownsampled = imagesDownsampled;
        this.imagesRecompressed = imagesRecompressed;
        this.imagesUnchanged = imagesUnchanged;
        this.profile = profile;
        this.durationMillis = durationMillis;
        this.returnedOriginal = returnedOriginal;
        this.compressedPdf = compressedPdf == null ? null : compressedPdf.clone();
    }

    public String getRequestId() {
        return requestId;
    }

    public long getOriginalBytes() {
        return originalBytes;
    }

    public long getCompressedBytes() {
        return compressedBytes;
    }

    public long getSavedBytes() {
        return savedBytes;
    }

    public double getSavedPercent() {
        return savedPercent;
    }

    public int getPageCount() {
        return pageCount;
    }

    public int getImagesInspected() {
        return imagesInspected;
    }

    public int getImagesDownsampled() {
        return imagesDownsampled;
    }

    public int getImagesRecompressed() {
        return imagesRecompressed;
    }

    public int getImagesUnchanged() {
        return imagesUnchanged;
    }

    public String getProfile() {
        return profile;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public boolean isReturnedOriginal() {
        return returnedOriginal;
    }

    /** Defensive copy: callers cannot mutate the engine's internal buffer. */
    public byte[] getCompressedPdf() {
        return compressedPdf == null ? null : compressedPdf.clone();
    }

    @Override
    public String toString() {
        return "CompressionResult{" +
                "requestId='" + requestId + '\'' +
                ", originalBytes=" + originalBytes +
                ", compressedBytes=" + compressedBytes +
                ", savedBytes=" + savedBytes +
                ", savedPercent=" + savedPercent +
                ", pageCount=" + pageCount +
                ", imagesInspected=" + imagesInspected +
                ", imagesDownsampled=" + imagesDownsampled +
                ", imagesRecompressed=" + imagesRecompressed +
                ", imagesUnchanged=" + imagesUnchanged +
                ", profile='" + profile + '\'' +
                ", durationMillis=" + durationMillis +
                ", returnedOriginal=" + returnedOriginal +
                ", compressedPdf.length=" + (compressedPdf == null ? -1 : compressedPdf.length) +
                '}';
    }
}
