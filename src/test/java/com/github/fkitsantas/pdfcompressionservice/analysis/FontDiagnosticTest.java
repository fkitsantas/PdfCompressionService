package com.github.fkitsantas.pdfcompressionservice.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.analysis.DocumentComposition.FontInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the per-font diagnostic: for each embedded font program the analyzer
 * reports its name, program type and whether it is already subset, and flags
 * only embedded, not-yet-subset TrueType fonts as {@code subsettable} (the ones
 * a subsetting pass can actually shrink). Uses synthetic font descriptors, so it
 * exercises the detection logic without depending on a system font file.
 */
class FontDiagnosticTest {

    @Test
    void fullTrueTypeFontIsFlaggedSubsettable() throws IOException {
        byte[] pdf = pdfWithFont("ArialMT", COSName.FONT_FILE2);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentComposition c = PdfCompositionAnalyzer.analyze(doc, pdf.length);

            assertThat(c.embeddedFonts()).hasSize(1);
            FontInfo f = c.embeddedFonts().get(0);
            assertThat(f.name()).isEqualTo("ArialMT");
            assertThat(f.program()).isEqualTo("TrueType");
            assertThat(f.alreadySubset()).isFalse();
            assertThat(f.subsettable()).isTrue();
            assertThat(f.bytes()).isPositive();
            assertThat(c.subsettableFontCount()).isEqualTo(1);
            assertThat(c.subsettableFontBytes()).isEqualTo(f.bytes());
            assertThat(c.note()).contains("Font subsetting could target");
        }
    }

    @Test
    void alreadySubsetTrueTypeFontIsNotSubsettable() throws IOException {
        byte[] pdf = pdfWithFont("ABCDEF+ArialMT", COSName.FONT_FILE2);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentComposition c = PdfCompositionAnalyzer.analyze(doc, pdf.length);

            FontInfo f = c.embeddedFonts().get(0);
            assertThat(f.alreadySubset()).isTrue();
            assertThat(f.subsettable()).isFalse();
            assertThat(c.subsettableFontCount()).isZero();
            assertThat(c.subsettableFontBytes()).isZero();
        }
    }

    @Test
    void cffFontIsReportedButNotSubsettable() throws IOException {
        byte[] pdf = pdfWithFont("QWERTY+MinionPro", COSName.FONT_FILE3);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentComposition c = PdfCompositionAnalyzer.analyze(doc, pdf.length);

            FontInfo f = c.embeddedFonts().get(0);
            assertThat(f.program()).isEqualTo("CFF");
            assertThat(f.subsettable()).isFalse();
        }
    }

    /** Builds a one-page PDF carrying a synthetic font descriptor (name + FontFile program), reachable so it is written. */
    private static byte[] pdfWithFont(String fontName, COSName fontFileKey) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            COSStream program = doc.getDocument().createCOSStream();
            try (OutputStream os = program.createOutputStream()) {
                os.write(new byte[4096]); // stand-in font-program bytes
            }
            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.TYPE, COSName.getPDFName("FontDescriptor"));
            descriptor.setName(COSName.FONT_NAME, fontName);
            descriptor.setItem(fontFileKey, program);
            // Reference it from the catalog so PDFBox writes it into the file.
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("XTestFontDescriptor"), descriptor);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
