package com.github.fkitsantas.pdfcompressionservice.compression;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;
import com.github.fkitsantas.pdfcompressionservice.quality.PdfVisualComparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Colour-space and transparency fidelity tests for {@link PdfCompressionEngine}:
 * grayscale must stay grayscale, transparency/soft-masks must survive, and
 * bitonal scans must remain bitonal and pixel-exact (lossless).
 *
 * <p><b>These tests are RED by design</b>, see {@link PdfCompressionEngineBehaviorTest}
 * for why: the engine is currently an unimplemented stub.
 */
class PdfCompressionEngineFidelityTest {

    private final PdfCompressionProperties defaults = new PdfCompressionProperties();

    @Test
    void grayscaleImageStaysInDeviceGrayColorSpace() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.grayscaleImage();

        CompressionResult result = engine.compress(original, "grayscale.pdf", "req-grayscale");

        PDImageXObject image = firstImage(result.getCompressedPdf());
        assertThat(image.getColorSpace().getName()).isEqualTo("DeviceGray");
    }

    @Test
    void transparentImageKeepsASoftMaskAndTransparentPixelsAreNotTurnedBlack() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.transparentPngImage();

        CompressionResult result = engine.compress(original, "transparent.pdf", "req-transparency");
        byte[] compressed = result.getCompressedPdf();

        // Read the soft mask while the document is still open. PDModel objects
        // lazily resolve indirect references (here the /SMask entry) against the
        // document's backing buffer, which PDDocument.close() releases; reading
        // it after close would spuriously yield null regardless of the produced
        // bytes. The end-to-end "not black" check below re-reads the same bytes.
        try (PDDocument doc = Loader.loadPDF(compressed)) {
            PDImageXObject image = firstImageXObject(doc.getPage(0));
            assertThat(image.getSoftMask())
                    .as("compressed image must retain a soft mask/alpha channel, not be flattened to opaque RGB")
                    .isNotNull();
        }

        // Render against a white page background: the near-fully-transparent
        // top strip of the source gradient (alpha ~0) must render close to
        // white, never black, the historical defect flattened transparent
        // PNG regions to solid black by drawing ARGB onto a TYPE_INT_RGB
        // buffer with no background fill.
        BufferedImage rendered = PdfVisualComparator.render(compressed, 72, 0);
        int topStripPixel = rendered.getRGB(rendered.getWidth() / 2, 5);
        int r = (topStripPixel >> 16) & 0xFF;
        int g = (topStripPixel >> 8) & 0xFF;
        int b = topStripPixel & 0xFF;
        assertThat(r + g + b)
                .as("near-transparent region rendered as RGB(%d,%d,%d), looks black, transparency was destroyed", r, g, b)
                .isGreaterThan(200);
    }

    @Test
    void bitonalScanStaysOneBitPerComponentAndIsPixelExact() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.bitonalScanImage();

        CompressionResult result = engine.compress(original, "bitonal.pdf", "req-bitonal");
        byte[] compressed = result.getCompressedPdf();

        PDImageXObject image = firstImage(compressed);
        assertThat(image.getBitsPerComponent()).isEqualTo(1);
        assertThat(image.getColorSpace().getName()).isEqualTo("DeviceGray");

        // Bitonal scans must be handled losslessly (CCITT G4 or JBIG2), not
        // re-encoded through a lossy photographic pipeline: the rendered
        // page must be pixel-for-pixel identical to the original.
        double ssim = PdfVisualComparator.ssim(original, compressed, 150);
        double diffPercent = PdfVisualComparator.maxPixelDiffPercent(original, compressed, 150);
        assertThat(ssim).isGreaterThan(0.999);
        assertThat(diffPercent).isLessThan(0.1);
    }

    @Test
    void mixedContentPageVectorArtAndTextRegionAreVisuallyUnaffected() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.mixedContentPage();

        CompressionResult result = engine.compress(original, "mixed.pdf", "req-mixed-text-region");
        byte[] compressed = result.getCompressedPdf();

        // The text/vector-art band (y ~ 100-360pt from bottom -> convert to a
        // top-left-origin pixel rectangle at 72 DPI) must render essentially
        // identically before/after, only the photographic image should change.
        java.awt.Rectangle textRegionAt72Dpi = new java.awt.Rectangle(0, 480, 350, 300);
        double regionSsim = PdfVisualComparator.regionSsim(original, compressed, 72, 0, textRegionAt72Dpi);
        assertThat(regionSsim).isGreaterThan(0.98);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static PDImageXObject firstImage(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return firstImageXObject(doc.getPage(0));
        }
    }

    private static PDImageXObject firstImageXObject(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        for (COSName name : resources.getXObjectNames()) {
            var xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image) {
                return image;
            }
        }
        throw new IllegalStateException("No image XObject found on page");
    }
}
