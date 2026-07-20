package com.github.fkitsantas.pdfcompressionservice.web;

import java.time.Duration;
import java.time.Instant;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end contract tests for the asynchronous {@code /jobs} API: submit ->
 * poll -> download, plus the error mappings (unknown id, out-of-range option).
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submitPollAndDownloadProducesACompressedPdf() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(3);
        MockMultipartFile file = new MockMultipartFile("file", "invoices.pdf", "application/pdf", pdf);

        MvcResult accepted = mockMvc.perform(multipart("/jobs").file(file))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").exists())
                .andReturn();

        String location = accepted.getResponse().getHeader("Location");
        String jobId = location.substring(location.lastIndexOf('/') + 1);

        awaitSuccess(jobId);

        // Once SUCCEEDED the status view carries the result link and the stats block.
        mockMvc.perform(get("/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resultUrl").value("/jobs/" + jobId + "/result"))
                .andExpect(jsonPath("$.stats.originalBytes").isNumber())
                .andExpect(jsonPath("$.stats.compressedBytes").isNumber());

        MvcResult download = mockMvc.perform(get("/jobs/{id}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();
        byte[] body = download.getResponse().getContentAsByteArray();
        assertThat(body.length).as("the compressed result is smaller than the upload").isLessThan(pdf.length);
        try (PDDocument doc = Loader.loadPDF(body)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
        }
    }

    @Test
    void unknownJobIdReturns404() throws Exception {
        mockMvc.perform(get("/jobs/{id}", "does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void invalidOptionOnSubmitReturns400() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.grayscaleImage();
        MockMultipartFile file = new MockMultipartFile("file", "in.pdf", "application/pdf", pdf);
        mockMvc.perform(multipart("/jobs").file(file).param("targetDpi", "999999"))
                .andExpect(status().isBadRequest());
    }

    /** Polls the status endpoint until the job leaves QUEUED/RUNNING, asserting it SUCCEEDED. */
    private void awaitSuccess(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            String body = mockMvc.perform(get("/jobs/{id}", jobId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (body.contains("\"status\":\"SUCCEEDED\"")) {
                return;
            }
            assertThat(body).as("job must not fail").doesNotContain("\"status\":\"FAILED\"");
            Thread.sleep(50);
        }
        throw new AssertionError("Job " + jobId + " did not SUCCEED within the timeout");
    }
}
