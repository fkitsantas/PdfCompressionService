package com.github.fkitsantas.pdfcompressionservice.compression;

/**
 * Selects the {@code org.apache.pdfbox.io.RandomAccessStreamCache} strategy
 * PDFBox uses while loading/writing documents inside
 * {@link PdfCompressionEngine}.
 */
public enum StreamCacheMode {

    /** Keep document scratch data entirely in heap memory. */
    MEMORY,

    /** Spill document scratch data to temporary files on disk. */
    TEMP_FILE
}
