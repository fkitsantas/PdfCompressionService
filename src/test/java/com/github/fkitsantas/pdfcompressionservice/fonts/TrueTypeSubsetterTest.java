package com.github.fkitsantas.pdfcompressionservice.fonts;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.analysis.DocumentComposition;
import com.github.fkitsantas.pdfcompressionservice.analysis.PdfCompositionAnalyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fidelity round-trip for the TrueType subsetter using a real system font: after
 * subsetting, the font program must be smaller, but the extracted text and,
 * crucially, the <b>rendered pixels</b> must be unchanged. Rendering equivalence
 * is the strong check, a wrong glyph mapping would still extract the same text
 * (that comes from ToUnicode) yet render different glyphs.
 */
class TrueTypeSubsetterTest {

    private static final String TEXT =
            "The DoctorHand Guide. Record, review and sign patient notes. "
                    + "Shortcuts, permissions and audit trails (see 1-9).";

    @Test
    void subsettingShrinksFontsWhilePreservingTextAndRendering() throws IOException {
        Path font = firstExistingFont();
        assumeTrue(font != null, "no system TrueType font available to build the fixture");

        byte[] before = buildDocument(font);
        long fontsBefore = PdfCompositionAnalyzer.analyze(Loader.loadPDF(before), before.length).fonts().bytes();

        byte[] after;
        BufferedImage[] rendersBefore;
        String textBefore;
        try (PDDocument doc = Loader.loadPDF(before)) {
            rendersBefore = render(doc);
            textBefore = new PDFTextStripper().getText(doc);
            TrueTypeSubsetter.Outcome outcome = new TrueTypeSubsetter().subsetFonts(doc, "req-test");
            assertThat(outcome.fontsSubset()).as("the embedded full font should be subset").isPositive();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            after = out.toByteArray();
        }

        try (PDDocument doc = Loader.loadPDF(after)) {
            // 1. Fonts got smaller.
            DocumentComposition composition = PdfCompositionAnalyzer.analyze(doc, after.length);
            assertThat(composition.fonts().bytes()).as("font bytes must shrink").isLessThan(fontsBefore);
            assertThat(composition.embeddedFonts().get(0).alreadySubset())
                    .as("the font is now marked subset").isTrue();

            // 2. Text is byte-for-byte identical.
            assertThat(new PDFTextStripper().getText(doc)).isEqualTo(textBefore);

            // 3. Rendered pixels are (essentially) identical - proves the glyph mapping is correct.
            BufferedImage[] rendersAfter = render(doc);
            assertThat(rendersAfter).hasSameSizeAs(rendersBefore);
            for (int i = 0; i < rendersAfter.length; i++) {
                assertThat(pixelDifferenceRatio(rendersBefore[i], rendersAfter[i]))
                        .as("page %d must render the same after subsetting", i)
                        .isLessThan(0.001);
            }
        }
    }

    private static byte[] buildDocument(Path fontFile) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font;
            try (var in = Files.newInputStream(fontFile)) {
                font = PDType0Font.load(doc, in, false); // full embed (not subset) -> our fixture
            }
            for (int p = 0; p < 3; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.setLeading(16);
                    cs.newLineAtOffset(50, 780);
                    for (String line : TEXT.split("(?<=\\G.{60})")) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage[] render(PDDocument doc) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        BufferedImage[] images = new BufferedImage[doc.getNumberOfPages()];
        for (int i = 0; i < images.length; i++) {
            images[i] = renderer.renderImageWithDPI(i, 100);
        }
        return images;
    }

    private static double pixelDifferenceRatio(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return 1.0;
        }
        long differing = 0;
        long total = (long) a.getWidth() * a.getHeight();
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return (double) differing / total;
    }

    private static Path firstExistingFont() {
        String[] candidates = {
                "/System/Library/Fonts/Supplemental/Arial.ttf",
                "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
                "/System/Library/Fonts/Supplemental/Verdana.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans.ttf",
                "/Library/Fonts/Arial.ttf"
        };
        for (String path : candidates) {
            Path p = Path.of(path);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
