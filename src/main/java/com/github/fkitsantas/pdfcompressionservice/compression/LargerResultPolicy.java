package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Governs what {@link PdfCompressionEngine} returns when the recompressed
 * candidate PDF is not smaller than the original by at least
 * {@link PdfCompressionProperties#getMinReductionRatio()}.
 */
public enum LargerResultPolicy {

    /**
     * If the recompressed candidate does not beat the original by at least
     * the configured minimum reduction ratio, discard the candidate and
     * return the original bytes unchanged (marks
     * {@link CompressionResult#isReturnedOriginal()} {@code true}).
     */
    KEEP_ORIGINAL,

    /**
     * Always return whichever of {original, recompressed candidate} is
     * byte-for-byte smaller, irrespective of the minimum reduction ratio.
     */
    USE_SMALLEST
}
