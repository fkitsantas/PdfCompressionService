package com.github.fkitsantas.pdfcompressionservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the drag-drop web UI is served at the root path, and that adding it
 * did not change the plain {@code POST /compressPdf} contract the curl flow
 * depends on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebUiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootWelcomePageForwardsToTheStaticUi() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void staticUiPageIsServedWithItsUploadForm() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PDF Compression Service")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/compressPdf")));
    }

    @Test
    void missingStaticResourceIsAClean404NotAServerError() throws Exception {
        // An unmapped path (or a missing static resource) must be a 404, never a 500
        // "compress-failed" from the global catch-all exception handler.
        mockMvc.perform(get("/this/path/does/not/exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void faviconsAreServed() throws Exception {
        // The browser's automatic /favicon.ico probe now resolves to a real icon.
        mockMvc.perform(get("/favicon.ico")).andExpect(status().isOk());
        mockMvc.perform(get("/favicon.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
    }

    @Test
    void plainCompressPdfStillReturnsAPdfUnchanged() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(1);
        MockMultipartFile file = new MockMultipartFile("file", "in.pdf", "application/pdf", pdf);
        mockMvc.perform(multipart("/compressPdf").file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"optimized.pdf\""));
    }
}
