package com.github.fkitsantas.pdfcompressionservice.analysis;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the composition analyzer buckets a PDF's bytes correctly: an image-
 * heavy document is dominated by the {@code images} category, the buckets sum to
 * the stream total, and {@code addressableBytes} is exactly fonts + vectors.
 */
class PdfCompositionAnalyzerTest {

    @Test
    void imageHeavyDocumentIsDominatedByImageBytes() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(3);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentComposition c = PdfCompositionAnalyzer.analyze(doc, pdf.length);

            assertThat(c.pageCount()).isEqualTo(3);
            assertThat(c.images().bytes()).as("the invoice images are the bulk of the file").isPositive();
            assertThat(c.images().bytes())
                    .as("images outweigh all the fonts + vector/text content combined")
                    .isGreaterThan(c.addressableBytes());
            assertThat(c.images().percent()).isGreaterThan(80.0);
        }
    }

    @Test
    void bucketsSumToTheStreamTotalAndAddressableIsFontsPlusVectors() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(2);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentComposition c = PdfCompositionAnalyzer.analyze(doc, pdf.length);

            long sum = c.images().bytes() + c.fonts().bytes() + c.vectors().bytes() + c.other().bytes();
            assertThat(c.streamBytesTotal()).isEqualTo(sum);
            assertThat(c.addressableBytes()).isEqualTo(c.fonts().bytes() + c.vectors().bytes());
            // Every page has a content stream, so there is always some vector/text content.
            assertThat(c.vectors().bytes()).isPositive();
        }
    }

    @Test
    void vectorFormDocumentReportsVectorContent() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.pdfWithFormXObject();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentComposition c = PdfCompositionAnalyzer.analyze(doc, pdf.length);
            assertThat(c.vectors().bytes()).as("a form-XObject document has vector content").isPositive();
        }
    }
}
