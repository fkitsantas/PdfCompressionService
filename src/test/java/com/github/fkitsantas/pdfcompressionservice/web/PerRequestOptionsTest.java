package com.github.fkitsantas.pdfcompressionservice.web;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the optional per-request compression parameters on
 * {@code POST /compressPdf}: they override the configured defaults when present,
 * the plain call (no parameters) is unaffected, and an out-of-range value is a
 * {@code 400 Bad Request} rather than a corrupted or default-config result.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PerRequestOptionsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void stripMetadataParameterRemovesMetadataThatIsOtherwiseKept() throws Exception {
        byte[] pdf = withMetadata(InvoiceCorpusFactory.multipleLargeInvoiceImages(1));

        byte[] defaultRun = postCompress(pdf); // no parameter -> default is to keep metadata
        assertThat(hasSensitiveMetadata(defaultRun)).as("default keeps metadata").isTrue();

        byte[] strippedRun = postCompress(pdf, "stripMetadata", "true");
        assertThat(hasSensitiveMetadata(strippedRun)).as("stripMetadata=true removes it").isFalse();
    }

    @Test
    void deduplicateImagesParameterCanBeDisabledPerRequest() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.sameImageRepeatedAcrossPagesAsSeparateObjects(6);

        byte[] defaultRun = postCompress(pdf); // default dedup on
        assertThat(distinctImageObjects(defaultRun)).as("default dedups the six copies to one").isEqualTo(1);

        byte[] noDedup = postCompress(pdf, "deduplicateImages", "false");
        assertThat(distinctImageObjects(noDedup)).as("deduplicateImages=false keeps them separate").isEqualTo(6);
    }

    @Test
    void outOfRangeOptionIsRejectedAsBadRequestWithJsonError() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.grayscaleImage();
        MockMultipartFile file = new MockMultipartFile("file", "in.pdf", "application/pdf", pdf);

        mockMvc.perform(multipart("/compressPdf").file(file).param("jpegQuality", "2.0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void validTargetDpiOverrideIsAcceptedAndProducesAReadablePdf() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(2);
        byte[] out = postCompress(pdf, "targetDpi", "96");
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
    }

    private byte[] postCompress(byte[] pdf, String... params) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "in.pdf", "application/pdf", pdf);
        var builder = multipart("/compressPdf").file(file);
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsByteArray();
    }

    private static int distinctImageObjects(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xobject = resources.getXObject(name);
                    if (xobject instanceof PDImageXObject image) {
                        seen.add(image.getCOSObject());
                    }
                }
            }
            return seen.size();
        }
    }

    private static byte[] withMetadata(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Confidential Invoice");
            info.setAuthor("ACME Supplies Ltd");
            doc.setDocumentInformation(info);
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean hasSensitiveMetadata(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            return info != null && (info.getTitle() != null || info.getAuthor() != null);
        }
    }
}
