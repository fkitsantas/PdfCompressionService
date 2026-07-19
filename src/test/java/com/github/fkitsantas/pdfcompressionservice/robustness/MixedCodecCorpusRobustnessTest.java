package com.github.fkitsantas.pdfcompressionservice.robustness;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;
import com.github.fkitsantas.pdfcompressionservice.quality.PdfVisualComparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Mixed-codec corpus, all handled: a single document mixing photographic
 * RGB, grayscale, 1-bit CCITT/bitonal, low-colour/indexed-style content, and
 * one undecodable "poison" image (see {@link
 * InvoiceCorpusFactory#mixedCodecCorpusWithUndecodableImage()}, pages 0-4 in
 * that fixed order). {@code compress(...)} must succeed as a whole, the page
 * count and total image-XObject count must be preserved (nothing dropped),
 * every page must still render, the poison image must be untouched, and
 * every other image's encoded size must never have grown.
 *
 * <p>Kept deliberately non-flaky: no assertion here depends on exactly which
 * internal counter (inspected/unchanged/etc.) the engine buckets the poison
 * image into - only on document-level invariants (counts, size monotonicity,
 * renderability) that any correct implementation satisfies regardless of
 * bookkeeping detail.
 *
 * <p><b>RED by design</b>, same underlying cause as {@link
 * UndecodableImagePassthroughTest}: the current engine aborts the whole
 * {@code compress(...)} call the moment it hits the undecodable image, so
 * none of these document-level assertions are ever reached.
 */
class MixedCodecCorpusRobustnessTest {

    private final PdfCompressionProperties defaults = new PdfCompressionProperties();

    @Test
    void allImageKindsAreHandledSomeOptimizedSomePassedThroughNoneCorrupted() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.mixedCodecCorpusWithUndecodableImage();

        int pageCountBefore;
        int imageCountBefore;
        byte[] poisonBefore;
        long[] nonPoisonLengthsBefore;
        try (PDDocument doc = Loader.loadPDF(original)) {
            pageCountBefore = doc.getNumberOfPages();
            imageCountBefore = PoisonImageLocator.countAllImageXObjects(doc);
            poisonBefore = PoisonImageLocator.rawEncodedBytes(PoisonImageLocator.find(doc));
            nonPoisonLengthsBefore = nonPoisonEncodedLengthsByPage(doc, pageCountBefore);
        }

        assertThatCode(() -> engine.compress(original, "mixed-codecs.pdf", "req-mixed-codecs"))
                .as("no single image kind - decodable or not - may abort compressing the whole document")
                .doesNotThrowAnyException();

        CompressionResult result = engine.compress(original, "mixed-codecs.pdf", "req-mixed-codecs-2");
        byte[] compressed = result.getCompressedPdf();

        try (PDDocument doc = Loader.loadPDF(compressed)) {
            assertThat(doc.getNumberOfPages())
                    .as("page count must be fully preserved")
                    .isEqualTo(pageCountBefore);
            assertThat(PoisonImageLocator.countAllImageXObjects(doc))
                    .as("every image XObject - optimized or passed through - must still be present")
                    .isEqualTo(imageCountBefore);

            byte[] poisonAfter = PoisonImageLocator.rawEncodedBytes(PoisonImageLocator.find(doc));
            assertThat(poisonAfter)
                    .as("the undecodable image must remain byte-for-byte untouched")
                    .isEqualTo(poisonBefore);

            long[] nonPoisonLengthsAfter = nonPoisonEncodedLengthsByPage(doc, pageCountBefore);
            for (int i = 0; i < nonPoisonLengthsBefore.length; i++) {
                assertThat(nonPoisonLengthsAfter[i])
                        .as("page %d's decodable image must never have GROWN (optimized-or-left-alone, never corrupted-larger)", i)
                        .isLessThanOrEqualTo(nonPoisonLengthsBefore[i]);
            }
        }

        boolean atLeastOneImageWasOptimized = result.getImagesRecompressed() > 0 || result.getImagesDownsampled() > 0;
        assertThat(atLeastOneImageWasOptimized)
                .as("at least one of the four normal images must have been optimized")
                .isTrue();

        // Every page must still render cleanly - a corrupted/half-written
        // image XObject typically blows up PDFRenderer, not just Loader.loadPDF.
        for (int i = 0; i < pageCountBefore; i++) {
            int pageIndex = i;
            assertThatCode(() -> PdfVisualComparator.render(compressed, 72, pageIndex))
                    .as("page %d must render without error", pageIndex)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Encoded stream length of each page's image, in page order, treating
     * the poison page's slot as {@code -1} (excluded from the monotonicity
     * check - it has its own byte-identical check instead).
     */
    private static long[] nonPoisonEncodedLengthsByPage(PDDocument doc, int pageCount) throws IOException {
        long[] lengths = new long[pageCount];
        for (int i = 0; i < pageCount; i++) {
            try {
                lengths[i] = PoisonImageLocator.encodedLength(PoisonImageLocator.firstNonPoisonImage(doc.getPage(i)));
            } catch (IllegalStateException noNonPoisonImageOnThisPage) {
                lengths[i] = -1; // the poison page itself - excluded from the monotonicity check
            }
        }
        return lengths;
    }
}
