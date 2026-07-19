package com.github.fkitsantas.pdfcompressionservice.concurrency;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.quality.PdfVisualComparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared "functional equivalence" assertions used by the parallelism
 * determinism test (Test 1) and the shared-engine concurrent-load test
 * (Test 2).
 *
 * <h2>Why functional equivalence, not whole-file byte-for-byte identity</h2>
 * <p>The task brief's own fallback clause anticipates this: PDFBox's
 * {@code COSWriter} generates a fresh, effectively random {@code /ID} entry
 * on every {@code doc.save(...)} call (derived from a timestamp/UUID-ish
 * source), independent of parallelism entirely, so two separately-run
 * compressions of the *same* input, even both fully serial, are already not
 * guaranteed byte-identical today. Asserting whole-PDF byte equality would
 * therefore be flaky by construction, not because of anything the
 * parallelism feature does wrong.
 *
 * <p>What parallelism must <b>not</b> change is document byte length (a
 * function of object *count* and structure, not of the specific {@code /ID}
 * bytes or of which OS thread computed which image), the recorded
 * compression stats, and, most importantly, the actual pixel content and
 * encoded size of every image. So this helper asserts, per corresponding
 * page: identical encoded image-stream length (the resize+encode step is a
 * pure function of the source image + config; thread scheduling cannot
 * change its output) and identical rendered pixels (0% max-pixel-diff at a
 * fixed DPI, the strongest non-flaky visual check available, stronger than
 * an SSIM threshold). Combined with whole-result stats equality (sizes,
 * counts, policy outcome), this is strictly more informative than a raw
 * byte-diff would be for tracking down a real parallelism bug: a byte diff
 * only says "different"; this says "which image differs, and how".
 */
final class CompressionEquivalence {

    /** Deliberately modest so page-render cost stays low across many assertions/tests. */
    private static final int RENDER_DPI = 100;

    private CompressionEquivalence() {
    }

    /**
     * All {@link CompressionResult} fields that must be scheduling-invariant.
     * {@code requestId} (caller-supplied/echoed correlation id) and
     * {@code durationMillis} (wall-clock timing) are intentionally excluded -
     * neither is a property of the compression *outcome*.
     */
    static void assertStatsEquivalent(CompressionResult expected, CompressionResult actual) {
        assertThat(actual.getOriginalBytes())
                .as("originalBytes must be identical, both runs compress the same source bytes")
                .isEqualTo(expected.getOriginalBytes());
        assertThat(actual.getCompressedBytes())
                .as("compressedBytes (a length, not the /ID-bearing content) must be identical regardless of parallelism")
                .isEqualTo(expected.getCompressedBytes());
        assertThat(actual.getSavedBytes()).isEqualTo(expected.getSavedBytes());
        assertThat(actual.getSavedPercent()).isEqualTo(expected.getSavedPercent());
        assertThat(actual.getPageCount()).isEqualTo(expected.getPageCount());
        assertThat(actual.getImagesInspected())
                .as("per-image inspect/skip decisions must not depend on which thread evaluated them")
                .isEqualTo(expected.getImagesInspected());
        assertThat(actual.getImagesDownsampled()).isEqualTo(expected.getImagesDownsampled());
        assertThat(actual.getImagesRecompressed()).isEqualTo(expected.getImagesRecompressed());
        assertThat(actual.getImagesUnchanged()).isEqualTo(expected.getImagesUnchanged());
        assertThat(actual.getProfile()).isEqualTo(expected.getProfile());
        assertThat(actual.isReturnedOriginal()).isEqualTo(expected.isReturnedOriginal());
    }

    /**
     * Structural + pixel-level equivalence of two produced PDFs, for the
     * single-image-per-page shape of {@code InvoiceCorpusFactory.multipleLargeInvoiceImages}
     * (the fixture the parallelism tests are required to use, parallelism
     * only matters once there is more than one image to fan out).
     */
    static void assertPdfsFunctionallyEquivalent(byte[] pdfA, byte[] pdfB, int expectedPageCount) throws IOException {
        try (PDDocument docA = Loader.loadPDF(pdfA); PDDocument docB = Loader.loadPDF(pdfB)) {
            assertThat(docA.getNumberOfPages())
                    .as("first PDF's page count must match the fixture")
                    .isEqualTo(expectedPageCount);
            assertThat(docB.getNumberOfPages())
                    .as("second PDF's page count must match the fixture")
                    .isEqualTo(expectedPageCount);

            for (int i = 0; i < expectedPageCount; i++) {
                PDImageXObject imageA = firstImage(docA.getPage(i));
                PDImageXObject imageB = firstImage(docB.getPage(i));
                assertThat(encodedLength(imageB))
                        .as("page %d: encoded image-stream length must match between the two runs " +
                                "(same source image, same config -> resize+encode is a pure function of its input)", i)
                        .isEqualTo(encodedLength(imageA));
                assertThat(imageB.getWidth())
                        .as("page %d: image width must match", i)
                        .isEqualTo(imageA.getWidth());
                assertThat(imageB.getHeight())
                        .as("page %d: image height must match", i)
                        .isEqualTo(imageA.getHeight());
            }
        }

        for (int i = 0; i < expectedPageCount; i++) {
            double diffPercent = PdfVisualComparator.maxPixelDiffPercent(pdfA, pdfB, RENDER_DPI, i);
            assertThat(diffPercent)
                    .as("page %d: rendered pixels must be pixel-for-pixel identical between the two runs, " +
                            "resize/encode is a deterministic per-image function, so thread scheduling must not " +
                            "change a single output pixel", i)
                    .isEqualTo(0.0);
        }
    }

    /** Distinct unique image XObject count reachable from page resources, in page order. Forms/annotations are
     * deliberately not walked here, the parallelism fixtures never use them (see InvoiceCorpusFactory). */
    static int countImageXObjects(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int count = 0;
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xobject = resources.getXObject(name);
                    if (xobject instanceof PDImageXObject) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    private static PDImageXObject firstImage(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image) {
                return image;
            }
        }
        throw new IllegalStateException("No image XObject found on page, fixture shape assumption violated");
    }

    private static long encodedLength(PDImageXObject image) {
        return ((COSStream) image.getCOSObject()).getLength();
    }
}
