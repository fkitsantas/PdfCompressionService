package com.github.fkitsantas.pdfcompressionservice.concurrency;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE LINCHPIN TEST for intra-request image-processing parallelism:
 * compressing the same multi-image document with {@code parallelism=1}
 * (effectively serial) must produce a result <b>functionally identical</b>
 * to compressing it with {@code parallelism=4}.
 *
 * <p>See {@link CompressionEquivalence} for exactly what "functionally
 * identical" means here and why whole-PDF byte-for-byte identity is not the
 * chosen bar (short version: PDFBox's {@code /ID} generation on save is
 * already non-deterministic run-to-run, independent of this feature, so that
 * assertion would be flaky regardless of how well parallelism is
 * implemented).
 *
 * <p><b>This test is RED by design</b> against the current skeleton:
 * {@link PdfCompressionEngine#compress} does not yet dispatch any work to
 * {@link PdfCompressionEngine#getImageProcessingExecutor()} - the per-image
 * loop is still fully sequential regardless of the configured parallelism.
 * Two things fail as a result:
 * <ol>
 *   <li>the "real parallel work happened" thread-usage check at the end of
 *       this test (zero {@code pdf-img-*} threads are ever created, since
 *       {@code ThreadPoolExecutor} creates worker threads lazily only when a
 *       task is actually submitted) - this is what proves the test isn't
 *       vacuously green just because parallelism is currently a no-op;</li>
 *   <li>once the implementer wires real parallel dispatch, any bug that
 *       makes concurrent per-image work non-deterministic (e.g. sharing a
 *       mutable {@code PDDocument}/decoder across threads, or attaching
 *       replacements in completion order instead of original discovery
 *       order) will surface here as a stats or pixel mismatch.</li>
 * </ol>
 */
class ParallelismDeterminismTest {

    /** Comfortably above the default {@code parallel-image-threshold} (2), so both engines' parallel paths, once implemented, actually engage. */
    private static final int PAGE_COUNT = 6;

    @Test
    void parallelCompressionIsFunctionallyIdenticalToSerialCompression() throws IOException {
        byte[] source = InvoiceCorpusFactory.multipleLargeInvoiceImages(PAGE_COUNT);

        PdfCompressionProperties serialProps = new PdfCompressionProperties();
        serialProps.setParallelism(1);
        PdfCompressionEngine serialEngine = new PdfCompressionEngine(serialProps);

        PdfCompressionProperties parallelProps = new PdfCompressionProperties();
        parallelProps.setParallelism(4);
        PdfCompressionEngine parallelEngine = new PdfCompressionEngine(parallelProps);

        Set<String> threadsBefore = ThreadNameProbe.snapshotNames();

        CompressionResult serialResult = serialEngine.compress(source, "invoices.pdf", "req-serial");
        CompressionResult parallelResult = parallelEngine.compress(source, "invoices.pdf", "req-parallel");

        CompressionEquivalence.assertStatsEquivalent(serialResult, parallelResult);
        CompressionEquivalence.assertPdfsFunctionallyEquivalent(
                serialResult.getCompressedPdf(), parallelResult.getCompressedPdf(), PAGE_COUNT);

        // Proves the parallel engine actually used its executor for this
        // call - i.e. that the equivalence assertions above are a genuine
        // parallel-vs-serial comparison, not two runs of the same
        // (currently unparallelized) code path that trivially agree.
        Set<String> newWorkerThreads = ThreadNameProbe.newNamesWithPrefix(
                threadsBefore, PdfCompressionEngine.IMAGE_THREAD_NAME_PREFIX);
        assertThat(newWorkerThreads)
                .as("parallelism=4 compressing %d eligible images (> the default threshold of 2) must dispatch " +
                        "work to at least 2 distinct pdf-img-* worker threads - an empty/singleton result means " +
                        "the parallel path never actually executed", PAGE_COUNT)
                .hasSizeGreaterThanOrEqualTo(2);
    }
}
