package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end check that the compression pipeline meaningfully shrinks a font-heavy
 * document (the case image compression alone cannot help) via font subsetting,
 * while keeping the text intact, and that turning the feature off is honoured.
 */
class FontSubsettingCompressionTest {

    @Test
    void fontHeavyDocumentIsShrunkWhileTextIsPreserved() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.fontHeavyDocument(20);
        assumeTrue(pdf != null, "no system TrueType font available to build the fixture");

        CompressionResult result = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(pdf, "guide.pdf", "req-font-heavy");

        assertThat(result.isReturnedOriginal()).as("subsetting should pay off on a font-heavy doc").isFalse();
        assertThat(result.getCompressedBytes())
                .as("font subsetting materially shrinks the file")
                .isLessThan((long) (result.getOriginalBytes() * 0.6));

        try (PDDocument doc = Loader.loadPDF(result.getCompressedPdf())) {
            assertThat(new PDFTextStripper().getText(doc))
                    .as("all the text survives subsetting")
                    .contains("DoctorHand guide, chapter 0")
                    .contains("record, review and sign notes");
        }
    }

    @Test
    void simpleTrueTypeFontHeavyDocumentIsShrunk() throws IOException {
        // The case reported from the field: full-embedded simple TrueType (e.g. Arial) fonts.
        byte[] pdf = InvoiceCorpusFactory.fontHeavyDocumentSimpleTrueType(20);
        assumeTrue(pdf != null, "no system TrueType font available to build the fixture");

        CompressionResult result = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(pdf, "guide.pdf", "req-simple-heavy");

        assertThat(result.isReturnedOriginal()).as("simple-font subsetting should pay off").isFalse();
        assertThat(result.getCompressedBytes())
                .as("simple TrueType subsetting materially shrinks the file")
                .isLessThan((long) (result.getOriginalBytes() * 0.6));
        try (PDDocument doc = Loader.loadPDF(result.getCompressedPdf())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("DoctorHand guide, chapter 0");
        }
    }

    @Test
    void subsettingCanBeDisabled() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.fontHeavyDocument(20);
        assumeTrue(pdf != null, "no system TrueType font available to build the fixture");

        PdfCompressionProperties noSubset = new PdfCompressionProperties();
        noSubset.setSubsetFonts(false);
        CompressionResult result = new PdfCompressionEngine(noSubset).compress(pdf, "guide.pdf", "req-no-subset");

        // With no image work and subsetting off, there is nothing to gain, so the original is kept.
        assertThat(result.isReturnedOriginal()).isTrue();
    }
}
