package com.github.fkitsantas.pdfcompressionservice.robustness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Robustness holds on the PARALLEL path: ties the per-image robustness
 * contract ({@link UndecodableImagePassthroughTest}) to the concurrency work
 * (see the {@code concurrency} test package). Runs {@link
 * InvoiceCorpusFactory#mixedCorpusWithUndecodableImage()} through ONE shared
 * engine, configured so the parallel dispatch path is guaranteed to engage
 * ({@code parallelism=4}, {@code parallelImageThreshold=0}, "always prefer
 * parallel once there is at least one eligible image", so this holds even
 * though the fixture only has 2 images total), concurrently invoked from
 * several caller threads with a {@link CountDownLatch} gate to maximize
 * simultaneity.
 *
 * <p>A per-image decode failure landing on a shared executor's pool thread
 * must not: corrupt the shared {@code PDDocument} for sibling images in the
 * SAME request, leak an exception out of {@code compress(...)} to the
 * caller, or affect any of the other, independently-running SIBLING
 * REQUESTS on the same shared engine. Every one of the {@link
 * #CONCURRENT_REQUESTS} results is checked against the same invariants as
 * the single-threaded passthrough test: page count preserved, poison image
 * byte-identical, normal image still optimized.
 *
 * <p><b>RED by design</b>: today {@code compress(...)} throws for this
 * fixture regardless of parallelism (the defect is in {@code
 * ImageOptimizer#process} having no per-image try/catch at all, not
 * specifically in how the parallel path was going to be wired), so every
 * concurrent call fails identically.
 */
class ParallelPathRobustnessTest {

    private static final int CONCURRENT_REQUESTS = 6;

    @Test
    void perImageFailureOnAPoolThreadNeverCorruptsSiblingRequestsUnderParallelLoad() throws Exception {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setParallelism(4);
        props.setParallelImageThreshold(0);
        PdfCompressionEngine engine = new PdfCompressionEngine(props);

        byte[] source = InvoiceCorpusFactory.mixedCorpusWithUndecodableImage();
        byte[] poisonBefore;
        int pageCountBefore;
        try (PDDocument doc = Loader.loadPDF(source)) {
            pageCountBefore = doc.getNumberOfPages();
            poisonBefore = PoisonImageLocator.rawEncodedBytes(PoisonImageLocator.find(doc));
        }

        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService callerPool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Future<CompressionResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                int idx = i;
                Callable<CompressionResult> task = () -> {
                    ready.countDown();
                    go.await();
                    return engine.compress(source, "req-" + idx + ".pdf", "req-" + idx);
                };
                futures.add(callerPool.submit(task));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all %d caller threads must reach the starting gate", CONCURRENT_REQUESTS)
                    .isTrue();
            go.countDown();

            for (Future<CompressionResult> future : futures) {
                assertThatCode(() -> future.get(60, TimeUnit.SECONDS))
                        .as("no concurrent request may fail because a sibling task's poison image, or its own, " +
                                "corrupted shared executor/document state")
                        .doesNotThrowAnyException();

                CompressionResult result = future.get(60, TimeUnit.SECONDS);
                byte[] compressed = result.getCompressedPdf();

                try (PDDocument doc = Loader.loadPDF(compressed)) {
                    assertThat(doc.getNumberOfPages())
                            .as("page count must be preserved under concurrent parallel-path load")
                            .isEqualTo(pageCountBefore);

                    byte[] poisonAfter = PoisonImageLocator.rawEncodedBytes(PoisonImageLocator.find(doc));
                    assertThat(poisonAfter)
                            .as("the poison image must stay byte-for-byte untouched even when processed on a pool thread")
                            .isEqualTo(poisonBefore);
                }

                boolean normalImageWasOptimized = result.getImagesRecompressed() > 0
                        || result.getImagesDownsampled() > 0
                        || result.getCompressedBytes() < result.getOriginalBytes();
                assertThat(normalImageWasOptimized)
                        .as("the normal image must still have been optimized in every concurrent result")
                        .isTrue();
            }
        } finally {
            callerPool.shutdownNow();
        }
    }
}
