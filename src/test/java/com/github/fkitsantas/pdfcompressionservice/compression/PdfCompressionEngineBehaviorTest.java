package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural unit tests that pin down {@link PdfCompressionEngine}'s public
 * contract: downsampling decisions, no-enlarge rule, min-dimension /
 * min-byte-size thresholds, keep-original vs. use-smallest policy, and
 * XObject deduplication.
 *
 * <p><b>These tests are RED by design.</b> {@link PdfCompressionEngine#compress}
 * is currently a stub that throws {@link UnsupportedOperationException}, so
 * every test below fails with that exception until the engine is
 * implemented. Do not "fix" these tests by catching or expecting that
 * exception, that would defeat the purpose of the failing spec.
 */
class PdfCompressionEngineBehaviorTest {

    private final PdfCompressionProperties defaults = new PdfCompressionProperties();

    // ------------------------------------------------------------------
    // Downsampling decision by effective DPI
    // ------------------------------------------------------------------

    @Test
    void downsamplesImageWhoseEffectiveDpiExceedsTarget() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.singleExtremelyLargePhotographicImage();

        CompressionResult result = engine.compress(original, "large.pdf", "req-downsample-1");

        assertThat(result.getImagesDownsampled()).isEqualTo(1);
        assertThat(result.getCompressedBytes()).isLessThan(result.getOriginalBytes());

        PDImageXObject downsampled = firstImage(result.getCompressedPdf());
        // Original image is 4000x3000 on an A4 page; at 150 target DPI the
        // expected width is roughly page-width-inches * 150 =~ 1240px. Assert
        // it shrank well below the original 4000px, with slack for the exact
        // rounding/resampling strategy the implementation picks.
        assertThat(downsampled.getWidth()).isLessThan(2000);
        assertThat(downsampled.getWidth()).isGreaterThan(0);
    }

    // ------------------------------------------------------------------
    // No-enlarge rule
    // ------------------------------------------------------------------

    @Test
    void neverUpscalesAnImageWhoseEffectiveDpiIsAlreadyBelowTarget() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.lowEffectiveDpiImage();
        PDImageXObject originalImage = firstImage(original);
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        CompressionResult result = engine.compress(original, "low-dpi.pdf", "req-no-enlarge-1");

        PDImageXObject afterImage = firstImage(result.getCompressedPdf());
        assertThat(afterImage.getWidth()).isLessThanOrEqualTo(originalWidth);
        assertThat(afterImage.getHeight()).isLessThanOrEqualTo(originalHeight);
    }

    // ------------------------------------------------------------------
    // Threshold boundaries: minDimension
    // ------------------------------------------------------------------

    @Test
    void imageBelowMinDimensionIsLeftUnchanged() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.squareNoiseImageAtDimension(defaults.getMinDimension() - 1);

        CompressionResult result = engine.compress(original, "tiny-15.pdf", "req-below-min-dim");

        assertThat(result.getImagesUnchanged()).isEqualTo(1);
        assertThat(result.getImagesDownsampled()).isZero();
        assertThat(result.getImagesRecompressed()).isZero();
    }

    @Test
    void imageAtExactlyMinDimensionIsStillTreatedAsSubThreshold() throws IOException {
        // At exactly minDimension pixels the image's encoded byte size can
        // never realistically clear minByteSize (8192 bytes) either, so the
        // combined gate should still leave it unchanged.
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.squareNoiseImageAtDimension(defaults.getMinDimension());

        CompressionResult result = engine.compress(original, "tiny-16.pdf", "req-at-min-dim");

        assertThat(result.getImagesUnchanged()).isEqualTo(1);
    }

    @Test
    void imageAboveMinDimensionAndMinByteSizeIsProcessed() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        // 60x60 high-entropy noise clears both minDimension (16px) and
        // minByteSize (8192 bytes).
        byte[] original = InvoiceCorpusFactory.squareNoiseImageAtDimension(60);

        CompressionResult result = engine.compress(original, "above-threshold.pdf", "req-above-min-dim");

        assertThat(result.getImagesInspected()).isEqualTo(1);
        assertThat(result.getImagesUnchanged()).isZero();
    }

    // ------------------------------------------------------------------
    // Threshold boundaries: minByteSize (isolated from minDimension)
    // ------------------------------------------------------------------

    @Test
    void largeDimensionImageBelowMinByteSizeIsLeftUnchanged() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        // 500x500 pixels (>> minDimension) but a flat colour that JPEG-compresses
        // to well under minByteSize (8192 bytes): isolates the byte-size gate.
        byte[] original = InvoiceCorpusFactory.largeDimensionBelowMinByteSize();

        CompressionResult result = engine.compress(original, "flat.pdf", "req-below-min-bytes");

        assertThat(result.getImagesUnchanged()).isEqualTo(1);
        assertThat(result.getImagesDownsampled()).isZero();
    }

    @Test
    void imageAboveMinByteSizeButAlreadyEfficientlyEncodedIsInspected() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.alreadyEfficientSmallJpeg();

        CompressionResult result = engine.compress(original, "efficient.pdf", "req-already-efficient");

        // It clears both thresholds, so the engine must inspect it, but
        // because it's already efficiently encoded, the candidate should not
        // grow the PDF (guarded by the larger-result policy).
        assertThat(result.getImagesInspected()).isEqualTo(1);
        assertThat(result.getCompressedBytes()).isLessThanOrEqualTo(result.getOriginalBytes());
    }

    // ------------------------------------------------------------------
    // Keep-original-when-not-smaller / larger-result policy
    // ------------------------------------------------------------------

    @Test
    void keepOriginalPolicyReturnsOriginalBytesWhenRecompressionDoesNotShrink() throws IOException {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setLargerResultPolicy(LargerResultPolicy.KEEP_ORIGINAL);
        PdfCompressionEngine engine = new PdfCompressionEngine(props);
        byte[] original = InvoiceCorpusFactory.alreadyOptimalPdf();

        CompressionResult result = engine.compress(original, "already-optimal.pdf", "req-keep-original");

        assertThat(result.isReturnedOriginal()).isTrue();
        assertThat(result.getCompressedBytes()).isEqualTo(result.getOriginalBytes());
        assertThat(result.getCompressedPdf()).isEqualTo(original);
    }

    @Test
    void useSmallestPolicyAlwaysReturnsTheSmallerCandidateEvenBelowReductionRatio() throws IOException {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setLargerResultPolicy(LargerResultPolicy.USE_SMALLEST);
        PdfCompressionEngine engine = new PdfCompressionEngine(props);
        byte[] original = InvoiceCorpusFactory.alreadyOptimalPdf();

        CompressionResult result = engine.compress(original, "already-optimal.pdf", "req-use-smallest");

        assertThat(result.getCompressedBytes()).isLessThanOrEqualTo(result.getOriginalBytes());
    }

    // ------------------------------------------------------------------
    // Deduplication of a reused XObject
    // ------------------------------------------------------------------

    @Test
    void reusedImageXObjectIsReplacedByASingleSharedObject() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.sharedImageAcrossPages(3);

        CompressionResult result = engine.compress(original, "shared.pdf", "req-dedup");

        // Even though the image is drawn on 3 pages, the engine must have
        // inspected/replaced it exactly once (not once per page reference).
        assertThat(result.getImagesInspected()).isEqualTo(1);

        try (PDDocument doc = Loader.loadPDF(result.getCompressedPdf())) {
            COSBase image0 = firstImageCosObject(doc.getPage(0));
            COSBase image1 = firstImageCosObject(doc.getPage(1));
            COSBase image2 = firstImageCosObject(doc.getPage(2));
            assertThat(image0).isSameAs(image1);
            assertThat(image1).isSameAs(image2);
        }
    }

    // ------------------------------------------------------------------
    // Aspect ratio / orientation preserved
    // ------------------------------------------------------------------

    @Test
    void downsamplingPreservesAspectRatio() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.singleExtremelyLargePhotographicImage();
        double originalRatio = 4000.0 / 3000.0;

        CompressionResult result = engine.compress(original, "large.pdf", "req-aspect-ratio");

        PDImageXObject afterImage = firstImage(result.getCompressedPdf());
        double newRatio = (double) afterImage.getWidth() / afterImage.getHeight();
        assertThat(newRatio).isCloseTo(originalRatio, org.assertj.core.data.Offset.offset(0.02));
    }

    // ------------------------------------------------------------------
    // Repeated-compression stability
    // ------------------------------------------------------------------

    @Test
    void secondCompressionPassIsStableAndDoesNotFurtherDegrade() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.multipleLargeInvoiceImages(2);

        CompressionResult firstPass = engine.compress(original, "invoices.pdf", "req-stability-1");
        CompressionResult secondPass = engine.compress(firstPass.getCompressedPdf(), "invoices.pdf", "req-stability-2");

        // Second pass should not meaningfully shrink it further (nothing left
        // to gain) and must not grow it either.
        long firstSize = firstPass.getCompressedBytes();
        long secondSize = secondPass.getCompressedBytes();
        double growth = (secondSize - firstSize) / (double) firstSize;
        assertThat(growth).isLessThan(0.02);
        assertThat(secondPass.getImagesDownsampled()).isZero();
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

    private static COSBase firstImageCosObject(PDPage page) throws IOException {
        return firstImageXObject(page).getCOSObject();
    }
}
