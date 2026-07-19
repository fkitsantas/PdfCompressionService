package com.github.fkitsantas.pdfcompressionservice.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Concurrency contract test for {@code POST /compressPdf}: N simultaneous
 * requests, each with a different fixture (different page count), must all
 * succeed with the correct response contract and must not leak state between
 * requests (e.g. page count of response N must match request N, not some
 * other concurrently-running request).
 *
 * <p>Expected to fail against the current controller because of the same
 * {@code Content-Disposition} defect covered in
 * {@link PdfCompressionControllerContractTest#returnsPdfWithCorrectContentTypeDispositionAndLength()}
 *, every concurrent response carries the same malformed header.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PdfCompressionConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void handlesConcurrentRequestsWithoutCrossRequestContamination() throws Exception {
        // Each task uploads a document with a distinct, known page count so
        // any cross-request state leakage (e.g. a shared/reused PDDocument)
        // would surface as a page-count mismatch.
        List<Callable<TaskOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            int pageCount = 1 + (i % 4); // 1,2,3,4,1,2,3,4
            tasks.add(() -> runOne(pageCount));
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<Future<TaskOutcome>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            List<TaskOutcome> outcomes = new ArrayList<>();
            for (Future<TaskOutcome> future : futures) {
                outcomes.add(future.get());
            }

            assertThat(outcomes).hasSize(CONCURRENT_REQUESTS);
            for (TaskOutcome outcome : outcomes) {
                assertThat(outcome.status())
                        .as("request for a %d-page document must succeed", outcome.expectedPageCount())
                        .isEqualTo(200);
                assertThat(outcome.contentDisposition())
                        .as("attachment disposition must be correct under concurrent load too")
                        .isEqualTo("attachment; filename=\"optimized.pdf\"");
                assertThat(outcome.actualPageCount())
                        .as("response page count must match the request that produced it, not a concurrently running one")
                        .isEqualTo(outcome.expectedPageCount());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private TaskOutcome runOne(int pageCount) throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(pageCount);
        MockMultipartFile file = new MockMultipartFile("file", "invoice-" + pageCount + ".pdf", "application/pdf", pdf);

        MvcResult result = mockMvc.perform(multipart("/compressPdf").file(file)).andReturn();
        int status = result.getResponse().getStatus();
        String disposition = result.getResponse().getHeader("Content-Disposition");
        byte[] body = result.getResponse().getContentAsByteArray();

        int actualPageCount = -1;
        if (status == 200 && body.length > 0) {
            try (PDDocument doc = Loader.loadPDF(body)) {
                actualPageCount = doc.getNumberOfPages();
            }
        }
        return new TaskOutcome(status, disposition, pageCount, actualPageCount);
    }

    private record TaskOutcome(int status, String contentDisposition, int expectedPageCount, int actualPageCount) {
    }
}
