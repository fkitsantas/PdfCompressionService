package com.github.fkitsantas.pdfcompressionservice.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared, bounded executor: no per-request pool, threads bounded by
 * configured parallelism.
 *
 * <p>Deliberately avoids wall-clock timing. Boundedness is proven two ways:
 * <ol>
 *   <li>a structural check, the exposed {@link PdfCompressionEngine#getImageProcessingExecutor()}
 *       is (when it is a {@link ThreadPoolExecutor}, which the reference
 *       skeleton's is) sized exactly to the configured parallelism;</li>
 *   <li>a behavioural check, after firing {@link #CONCURRENT_REQUESTS} truly
 *       simultaneous {@code compress(...)} calls (gated by a
 *       {@link CountDownLatch}) at the ONE shared engine, the number of
 *       distinct {@code pdf-img-*} worker threads observed is non-zero (real
 *       work happened) and never exceeds the configured parallelism, i.e.
 *       it does not grow with the number of concurrent requests, which is
 *       what would happen if the implementation created a fresh executor (or
 *       raw threads) per request instead of reusing the one shared pool.</li>
 * </ol>
 *
 * <p><b>RED by design</b>: the skeleton engine never submits any task to its
 * executor, so no {@code pdf-img-*} thread is ever created and the
 * non-empty check in step 2 fails. The pool-size check in step 1 may
 * incidentally already pass (the executor is correctly sized even though
 * unused), that alone must not be read as "parallelism is implemented".
 */
class BoundedImageProcessingExecutorTest {

    private static final int PARALLELISM = 3;
    private static final int CONCURRENT_REQUESTS = 8;

    /** Comfortably above the default parallel-image-threshold (2) so every request has real fan-out work. */
    private static final int PAGES_PER_REQUEST = 5;

    @Test
    void executorIsSharedAndBoundedRegardlessOfConcurrentRequestCount() throws Exception {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setParallelism(PARALLELISM);
        PdfCompressionEngine engine = new PdfCompressionEngine(props);

        ExecutorService imageExecutor = engine.getImageProcessingExecutor();
        assertThat(imageExecutor)
                .as("engine must expose its own single executor instance")
                .isNotNull();
        if (imageExecutor instanceof ThreadPoolExecutor tpe) {
            assertThat(tpe.getMaximumPoolSize())
                    .as("executor must be sized from pdf.compression.parallelism (%d)", PARALLELISM)
                    .isEqualTo(PARALLELISM);
        }

        byte[] source = InvoiceCorpusFactory.multipleLargeInvoiceImages(PAGES_PER_REQUEST);
        Set<String> threadsBefore = ThreadNameProbe.snapshotNames();

        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService callerPool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Future<CompressionResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                int idx = i;
                futures.add(callerPool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return engine.compress(source, "req-" + idx + ".pdf", "req-" + idx);
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<CompressionResult> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            callerPool.shutdownNow();
        }

        Set<String> newWorkerThreads = ThreadNameProbe.newNamesWithPrefix(
                threadsBefore, PdfCompressionEngine.IMAGE_THREAD_NAME_PREFIX);

        assertThat(newWorkerThreads)
                .as("image-processing work must actually have run on the pdf-img-* pool during this load")
                .isNotEmpty();
        assertThat(newWorkerThreads.size())
                .as("%d simultaneous requests on ONE shared engine must not grow the worker-thread count past " +
                        "the configured parallelism (%d) - a per-request pool would fail this as request count " +
                        "increases, a correctly shared bounded pool never will",
                        CONCURRENT_REQUESTS, PARALLELISM)
                .isLessThanOrEqualTo(PARALLELISM);
    }
}
