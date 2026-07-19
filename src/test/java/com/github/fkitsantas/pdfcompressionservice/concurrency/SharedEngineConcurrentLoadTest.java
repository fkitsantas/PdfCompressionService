package com.github.fkitsantas.pdfcompressionservice.concurrency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Correctness under shared-engine concurrent load (engine level).
 *
 * <p>Builds ONE {@link PdfCompressionEngine} (parallelism=auto, i.e.
 * {@code 0}) and invokes {@code compress(...)} concurrently from many
 * threads over several distinct multi-image fixtures with different page
 * counts, maximizing simultaneity with a {@link CountDownLatch} gate. Every
 * result must: be a valid, loadable PDF; have a page count matching its own
 * input (not some other concurrently-running request's); and be
 * functionally equivalent (see {@link CompressionEquivalence}) to a serial
 * baseline computed independently for that same input. This is what proves
 * the shared bounded executor introduces no cross-request corruption or
 * races, a stricter, request-isolation-focused sibling of
 * {@link ParallelismDeterminismTest}, which only exercises a single request.
 *
 * <p><b>RED by design</b>, for the same reason as {@link ParallelismDeterminismTest}:
 * the skeleton engine never dispatches work to
 * {@link PdfCompressionEngine#getImageProcessingExecutor()}, so the
 * thread-usage assertion at the end of this test, proof that the SHARED
 * executor was actually exercised under concurrent load, fails.
 */
class SharedEngineConcurrentLoadTest {

    private static final int[] PAGE_COUNTS = {3, 4, 5, 6};

    /** 4 fixtures x 4 concurrent callers each = 16 simultaneous compress() calls on one shared engine. */
    private static final int CALLERS_PER_FIXTURE = 4;

    @Test
    void concurrentRequestsOnASharedEngineAreCorrectAndMatchAnIndependentSerialBaseline() throws Exception {
        PdfCompressionProperties sharedProps = new PdfCompressionProperties();
        sharedProps.setParallelism(0); // auto
        PdfCompressionEngine sharedEngine = new PdfCompressionEngine(sharedProps);

        PdfCompressionProperties serialProps = new PdfCompressionProperties();
        serialProps.setParallelism(1);
        PdfCompressionEngine serialBaselineEngine = new PdfCompressionEngine(serialProps);

        Map<Integer, byte[]> fixturesByPageCount = new LinkedHashMap<>();
        Map<Integer, CompressionResult> baselinesByPageCount = new LinkedHashMap<>();
        for (int pageCount : PAGE_COUNTS) {
            byte[] source = InvoiceCorpusFactory.multipleLargeInvoiceImages(pageCount);
            fixturesByPageCount.put(pageCount, source);
            baselinesByPageCount.put(pageCount,
                    serialBaselineEngine.compress(source, "baseline-" + pageCount + ".pdf", "baseline-" + pageCount));
        }

        int totalTasks = PAGE_COUNTS.length * CALLERS_PER_FIXTURE;
        CountDownLatch ready = new CountDownLatch(totalTasks);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService callerPool = Executors.newFixedThreadPool(totalTasks);
        List<Future<Outcome>> futures = new ArrayList<>();
        Set<String> threadsBefore = ThreadNameProbe.snapshotNames();
        try {
            int taskIndex = 0;
            for (int pageCount : PAGE_COUNTS) {
                for (int r = 0; r < CALLERS_PER_FIXTURE; r++) {
                    int idx = taskIndex++;
                    int expectedPageCount = pageCount;
                    futures.add(callerPool.submit(() -> {
                        ready.countDown();
                        go.await();
                        byte[] source = fixturesByPageCount.get(expectedPageCount);
                        CompressionResult result = sharedEngine.compress(
                                source, "concurrent-" + expectedPageCount + "-" + idx + ".pdf", "req-" + idx);
                        return new Outcome(expectedPageCount, result);
                    }));
                }
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all %d caller threads must reach the starting gate", totalTasks)
                    .isTrue();
            go.countDown();

            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get(60, TimeUnit.SECONDS);
                CompressionResult baseline = baselinesByPageCount.get(outcome.expectedPageCount());
                CompressionResult result = outcome.result();

                assertThat(result.getPageCount())
                        .as("result page count must match the request that produced it, not a concurrent one")
                        .isEqualTo(outcome.expectedPageCount());
                try (PDDocument doc = Loader.loadPDF(result.getCompressedPdf())) {
                    assertThat(doc.getNumberOfPages()).isEqualTo(outcome.expectedPageCount());
                }

                CompressionEquivalence.assertStatsEquivalent(baseline, result);
                CompressionEquivalence.assertPdfsFunctionallyEquivalent(
                        baseline.getCompressedPdf(), result.getCompressedPdf(), outcome.expectedPageCount());
            }
        } finally {
            callerPool.shutdownNow();
        }

        Set<String> newWorkerThreads = ThreadNameProbe.newNamesWithPrefix(
                threadsBefore, PdfCompressionEngine.IMAGE_THREAD_NAME_PREFIX);
        assertThat(newWorkerThreads)
                .as("the shared engine's executor must actually have processed images under this concurrent " +
                        "load, an empty result means compress() never dispatched to pdf-img-* workers")
                .isNotEmpty();
    }

    private record Outcome(int expectedPageCount, CompressionResult result) {
    }
}
