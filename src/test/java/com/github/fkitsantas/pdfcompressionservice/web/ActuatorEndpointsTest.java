package com.github.fkitsantas.pdfcompressionservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the operational surface added for observability: the Actuator health
 * endpoint (including the custom {@code compression} indicator and the
 * liveness/readiness probe groups) and the Prometheus scrape endpoint carrying
 * the service's own {@code pcs.*} compression metrics after a request.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsUpAndReportsTheCustomCompressionIndicator() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.compression.status").value("UP"))
                .andExpect(jsonPath("$.components.compression.details.tempDirWritable").value(true))
                .andExpect(jsonPath("$.components.compression.details.maxConcurrentCompressions").isNumber());
    }

    @Test
    void livenessAndReadinessProbeGroupsAreExposed() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void prometheusEndpointExposesCompressionMetricsAfterARequest() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(2);
        MockMultipartFile file = new MockMultipartFile("file", "invoices.pdf", "application/pdf", pdf);
        mockMvc.perform(multipart("/compressPdf").file(file)).andExpect(status().isOk());

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(scrape)
                .as("our own compression counters and gauges must be scrapeable")
                .contains("pcs_compression_requests_total")
                .contains("pcs_compression_bytes_saved_total")
                .contains("pcs_compression_slots_max")
                .contains("pcs_images_total");
    }
}
