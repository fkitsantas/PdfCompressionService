package com.github.fkitsantas.pdfcompressionservice.web;

import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.text.PDFTextStripper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST contract tests for {@code POST /compressPdf}, asserting the
 * CORRECT post-fix behaviour of the endpoint. The controller under test
 * ({@code com.github.fkitsantas.pdfcompressionservice.PdfCompressionService})
 * is currently defective:
 * <ul>
 *   <li>it sets an invalid {@code Content-Disposition} header
 *       ({@code HttpHeaders.setContentDispositionFormData("optimized.pdf",
 *       "attachment; filename=optimized.pdf")} produces a
 *       {@code form-data; name="optimized.pdf"; filename="attachment;
 *       filename=optimized.pdf"} header, not an {@code attachment} at all);</li>
 *   <li>it blindly re-encodes every image as a JPEG, destroying transparency
 *       and corrupting non-RGB colour spaces;</li>
 *   <li>it mangles Form XObject content streams with a bogus run-length
 *       "compression" of their raw bytes;</li>
 *   <li>it returns a bare {@code 500} with an empty body on any I/O failure
 *       (including a malformed/truncated upload), never a structured error.</li>
 * </ul>
 * These tests therefore MUST fail against the current controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PdfCompressionControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsPdfWithCorrectContentTypeDispositionAndLength() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.mixedContentPage();
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdf);

        MvcResult result = mockMvc.perform(multipart("/compressPdf").file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"optimized.pdf\""))
                .andExpect(header().exists("Content-Length"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(Integer.parseInt(result.getResponse().getHeader("Content-Length"))).isEqualTo(body.length);
    }

    @Test
    void outputReopensSuccessfullyWithPdfBox() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.grayscaleImage();
        byte[] compressed = postCompress(pdf);

        try (PDDocument doc = Loader.loadPDF(compressed)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
        }
    }

    @Test
    void preservesPageCountAndPageSizes() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(3);
        byte[] compressed = postCompress(pdf);

        try (PDDocument original = Loader.loadPDF(pdf);
             PDDocument result = Loader.loadPDF(compressed)) {
            assertThat(result.getNumberOfPages()).isEqualTo(original.getNumberOfPages());
            for (int i = 0; i < original.getNumberOfPages(); i++) {
                assertThat(result.getPage(i).getMediaBox().getWidth())
                        .isEqualTo(original.getPage(i).getMediaBox().getWidth());
                assertThat(result.getPage(i).getMediaBox().getHeight())
                        .isEqualTo(original.getPage(i).getMediaBox().getHeight());
            }
        }
    }

    @Test
    void preservesPageRotation() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.rotatedTransformedImage();
        byte[] compressed = postCompress(pdf);

        try (PDDocument result = Loader.loadPDF(compressed)) {
            assertThat(result.getPage(0).getRotation()).isEqualTo(90);
        }
    }

    @Test
    void preservesExtractableText() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.mixedContentPage();
        byte[] compressed = postCompress(pdf);

        try (PDDocument result = Loader.loadPDF(compressed)) {
            String text = new PDFTextStripper().getText(result);
            String normalized = text.replaceAll("\\s+", " ").trim();
            assertThat(normalized).contains("Invoice No. INV-2026-0042");
        }
    }

    @Test
    void preservesBookmarks() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.pdfWithBookmarks();
        byte[] compressed = postCompress(pdf);

        try (PDDocument result = Loader.loadPDF(compressed)) {
            PDDocumentOutline outline = result.getDocumentCatalog().getDocumentOutline();
            assertThat(outline).as("document outline must survive compression").isNotNull();
            PDOutlineItem first = outline.getFirstChild();
            assertThat(first).isNotNull();
            assertThat(first.getTitle()).isEqualTo(InvoiceCorpusFactory.BOOKMARK_TITLE);
        }
    }

    @Test
    void preservesAnnotationsWithAppearance() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.pdfWithAnnotationAppearance();
        byte[] compressed = postCompress(pdf);

        try (PDDocument result = Loader.loadPDF(compressed)) {
            List<PDAnnotation> annotations = result.getPage(0).getAnnotations();
            assertThat(annotations).hasSize(1);
            PDAnnotation annotation = annotations.get(0);
            assertThat(annotation.getSubtype()).isEqualTo("Square");
            assertThat(annotation.getAppearance()).isNotNull();
            assertThat(annotation.getAppearance().getNormalAppearance()).isNotNull();
        }
    }

    @Test
    void preservesAcroFormFields() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.pdfWithAcroForm();
        byte[] compressed = postCompress(pdf);

        try (PDDocument result = Loader.loadPDF(compressed)) {
            PDAcroForm acroForm = result.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).as("AcroForm must survive compression").isNotNull();
            List<PDField> fields = acroForm.getFields();
            assertThat(fields).hasSize(1);
            assertThat(fields.get(0).getFullyQualifiedName()).isEqualTo(InvoiceCorpusFactory.ACROFORM_FIELD_NAME);
            assertThat(fields.get(0).getValueAsString()).isEqualTo(InvoiceCorpusFactory.ACROFORM_FIELD_VALUE);
        }
    }

    @Test
    void producesMaterialSizeReductionForLargeImageCorpus() throws Exception {
        byte[] pdf = InvoiceCorpusFactory.singleExtremelyLargePhotographicImage();
        byte[] compressed = postCompress(pdf);

        assertThat(compressed.length)
                .as("compressed output (%d bytes) should be materially smaller than the original (%d bytes)",
                        compressed.length, pdf.length)
                .isLessThan((int) (pdf.length * 0.7));
    }

    @Test
    void malformedPdfReturns422WithJsonErrorBodyContainingRequestId() throws Exception {
        byte[] garbage = InvoiceCorpusFactory.corruptBytes();
        MockMultipartFile file = new MockMultipartFile("file", "corrupt.pdf", "application/pdf", garbage);

        mockMvc.perform(multipart("/compressPdf").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void truncatedPdfReturns422WithJsonErrorBodyContainingRequestId() throws Exception {
        byte[] truncated = InvoiceCorpusFactory.truncatedPdfBytes();
        MockMultipartFile file = new MockMultipartFile("file", "truncated.pdf", "application/pdf", truncated);

        mockMvc.perform(multipart("/compressPdf").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void missingFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/compressPdf"))
                .andExpect(status().isBadRequest());
    }

    private byte[] postCompress(byte[] pdf) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdf);
        MvcResult result = mockMvc.perform(multipart("/compressPdf").file(file))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }
}
