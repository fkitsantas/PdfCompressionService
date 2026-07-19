package com.github.fkitsantas.pdfcompressionservice.regression;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.InvalidPdfException;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;
import com.github.fkitsantas.pdfcompressionservice.quality.PdfVisualComparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Resource-leak and historical-defect regression tests for
 * {@link PdfCompressionEngine}.
 *
 * <p><b>These tests are RED by design</b>, same as the other engine unit
 * tests: the engine is currently an unimplemented stub, so every call to
 * {@code compress(...)} throws {@link UnsupportedOperationException} and the
 * tests fail before reaching their real assertions. That failure mode (an
 * unimplemented method, not a wrong exception type or a leaked resource) is
 * the expected "red" state for this suite.
 *
 * <p>The three historical defects encoded here, all present in the current
 * {@code com.github.fkitsantas.pdfcompressionservice.PdfCompressionService}
 * controller, must never recur in {@link PdfCompressionEngine}:
 * <ol>
 *   <li>transparent PNG regions rendered black (ARGB flattened onto an
 *       opaque RGB buffer with no alpha handling);</li>
 *   <li>Form XObject content streams corrupted by treating their raw
 *       (often binary/FlateDecode-compressed) bytes as UTF-8 text and
 *       "compressing" them with a bogus run-length scheme;</li>
 *   <li>insufficient/negative reduction: blindly re-encoding every image at
 *       a fixed JPEG quality with no comparison against the original size,
 *       which can make already-optimal inputs *larger*.</li>
 * </ol>
 */
class PdfCompressionRegressionTest {

    private final PdfCompressionProperties defaults = new PdfCompressionProperties();

    // ------------------------------------------------------------------
    // Temp-file leak checks
    // ------------------------------------------------------------------

    @Test
    void successfulCompressionLeavesNoTempFilesBehind() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(2);

        long before = countTempFiles();
        CompressionResult result = engine.compress(pdf, "invoices.pdf", "req-leak-success");
        long after = countTempFiles();

        assertThat(result.getCompressedPdf()).isNotEmpty();
        assertThat(after)
                .as("no scratch/temp files should remain in java.io.tmpdir after a successful compression")
                .isEqualTo(before);
    }

    @Test
    void failedCompressionOfAnInvalidPdfLeavesNoTempFilesBehindEither() {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] corrupt = InvoiceCorpusFactory.corruptBytes();

        long before = countTempFiles();
        Throwable thrown = catchThrowable(() -> engine.compress(corrupt, "corrupt.pdf", "req-leak-failure"));
        long after = countTempFiles();

        assertThat(thrown)
                .as("a malformed PDF must be rejected as InvalidPdfException, not silently succeed or throw something else")
                .isInstanceOf(InvalidPdfException.class);
        assertThat(after)
                .as("no scratch/temp files should remain in java.io.tmpdir after a rejected/failed compression")
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // Historical defect 1: transparency flattened to black
    // ------------------------------------------------------------------

    @Test
    void regressionTransparentPixelsMustNeverRenderAsBlack() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.transparentPngImage();

        CompressionResult result = engine.compress(original, "transparent.pdf", "req-regression-transparency");

        BufferedImage rendered = PdfVisualComparator.render(result.getCompressedPdf(), 72, 0);
        // Sample near the top of the page, where the source gradient's alpha
        // is close to zero (fully transparent). The historically defective
        // controller drew the ARGB image onto a TYPE_INT_RGB BufferedImage
        // with no background fill, which paints uninitialised (black)
        // pixels wherever the source was transparent.
        int x = rendered.getWidth() / 2;
        int y = 5;
        int pixel = rendered.getRGB(x, y);
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        boolean looksBlack = r < 10 && g < 10 && b < 10;
        assertThat(looksBlack)
                .as("pixel (%d,%d) rendered as RGB(%d,%d,%d), transparency was flattened to black", x, y, r, g, b)
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Historical defect 2: Form XObject content stream corruption
    // ------------------------------------------------------------------

    @Test
    void regressionFormXObjectContentStreamIsNeverMangled() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.pdfWithFormXObject();

        CompressionResult result = engine.compress(original, "form-xobject.pdf", "req-regression-form-xobject");
        byte[] compressed = result.getCompressedPdf();

        // The document must still open, and the page's Form XObject must
        // still be a well-formed PDFormXObject whose content stream parses
        // and whose vector drawing survives visually. The historically
        // defective controller ran a bogus run-length "compression" over the
        // form's raw (possibly FlateDecode-compressed, possibly binary)
        // content-stream bytes as if they were UTF-8 text, corrupting them.
        try (PDDocument doc = Loader.loadPDF(compressed)) {
            PDPage page = doc.getPage(0);
            PDFormXObject form = firstFormXObject(page);
            assertThat(form).as("Form XObject resource must survive compression").isNotNull();
            // Reading the (decoded) content stream bytes must not throw -
            // i.e. the stream's filters/data are still internally consistent.
            byte[] contentBytes = form.getContents().readAllBytes();
            assertThat(contentBytes).isNotEmpty();
        }

        // Visually, the form's green rectangle + diagonal stroke must still
        // be present and in the same place (the whole page, since it has no
        // photographic image to change, should be near pixel-identical).
        double ssim = PdfVisualComparator.ssim(original, compressed, 72);
        assertThat(ssim).isGreaterThan(0.98);
    }

    // ------------------------------------------------------------------
    // Historical defect 3: insufficient / negative reduction
    // ------------------------------------------------------------------

    @Test
    void regressionNeverProducesAnOutputLargerThanTheOriginal() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.alreadyOptimalPdf();

        CompressionResult result = engine.compress(original, "already-optimal.pdf", "req-regression-no-growth");

        assertThat(result.getCompressedBytes())
                .as("default KEEP_ORIGINAL policy must never let the output grow past the input")
                .isLessThanOrEqualTo(result.getOriginalBytes());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static PDFormXObject firstFormXObject(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDFormXObject form) {
                return form;
            }
        }
        return null;
    }

    /** Counts entries directly under {@code java.io.tmpdir}, as a coarse leak signal. */
    private static long countTempFiles() {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        File[] entries = tmpDir.listFiles();
        return entries == null ? 0 : entries.length;
    }
}
