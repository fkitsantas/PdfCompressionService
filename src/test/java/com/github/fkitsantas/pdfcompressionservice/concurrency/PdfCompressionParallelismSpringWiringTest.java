package com.github.fkitsantas.pdfcompressionservice.concurrency;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionExecutorConfig;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Spring wiring smoke test for the parallelism feature: the application
 * context must start with the new shared image-processing executor bean
 * present, and {@code POST /compressPdf} must still return 200 with the
 * correct attachment disposition for a multi-image upload that exercises
 * the parallel path.
 *
 * <p>Kept minimal by design, the full HTTP contract (headers, status codes
 * for every error case, etc.) is already covered by
 * {@code PdfCompressionControllerContractTest} and
 * {@code PdfCompressionConcurrencyTest}; this class only adds what's new:
 * the executor bean's presence/identity/sizing, and proof that a real
 * request routes through it.
 *
 * <p><b>RED by design</b>: the bean-wiring and HTTP-contract assertions here
 * are expected to pass immediately (they only require the
 * {@link PdfCompressionExecutorConfig} plumbing, which is fully
 * implemented, see that class). What fails is the final assertion, which
 * proves the parallel path was actually *exercised* through the web layer:
 * the skeleton engine's {@code compress(...)} never dispatches to the
 * executor, so no {@code pdf-img-*} worker thread is ever created.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PdfCompressionParallelismSpringWiringTest {

    /** Comfortably above the default parallel-image-threshold (2). */
    private static final int PAGE_COUNT = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PdfCompressionEngine engine;

    @Autowired
    @Qualifier(PdfCompressionExecutorConfig.BEAN_NAME)
    private ExecutorService imageProcessingExecutor;

    @Test
    void contextStartsWithTheSharedImageProcessingExecutorBeanPresentAndCorrectlyWired() {
        assertThat(imageProcessingExecutor)
                .as("pdfImageProcessingExecutor bean must be present in the context")
                .isNotNull();
        assertThat(imageProcessingExecutor)
                .as("the published bean must be the SAME instance the engine singleton owns, not a second pool")
                .isSameAs(engine.getImageProcessingExecutor());
        if (imageProcessingExecutor instanceof ThreadPoolExecutor tpe) {
            assertThat(tpe.getMaximumPoolSize()).isGreaterThan(0);
        }
    }

    @Test
    void postCompressPdfSucceedsForAMultiImageUploadThatExercisesTheParallelPath() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(PAGE_COUNT);
        MockMultipartFile file = new MockMultipartFile("file", "invoices.pdf", "application/pdf", pdf);

        // Assert dispatch via the shared pool's task-count delta rather than by observing new pdf-img-* thread
        // NAMES: this Spring context (and its singleton engine + pool) is shared with other @SpringBootTest classes
        // in the same JVM fork, so a prior test may have left warm worker threads that this request reuses, making
        // a before/after thread-name diff racy. getTaskCount() increments on every submission regardless of whether
        // a fresh thread was spawned or an existing one reused, so it is a direct, timing-independent proof that the
        // parallel path actually ran through the web layer.
        ThreadPoolExecutor pool = (ThreadPoolExecutor) engine.getImageProcessingExecutor();
        long tasksBefore = pool.getTaskCount();

        MvcResult result = mockMvc.perform(multipart("/compressPdf").file(file)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"optimized.pdf\"");

        assertThat(pool.getTaskCount())
                .as("a %d-image upload (> the default parallel-image-threshold of 2) routed through the real " +
                        "web layer must dispatch per-image work to the shared pdf-img-* executor", PAGE_COUNT)
                .isGreaterThan(tasksBefore);
    }
}
