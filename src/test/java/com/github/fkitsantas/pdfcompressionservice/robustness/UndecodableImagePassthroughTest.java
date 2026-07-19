package com.github.fkitsantas.pdfcompressionservice.robustness;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * "We don't know what image types the PDF contains, handle anything and
 * everything": an image XObject that cannot be decoded (unsupported codec,
 * corrupt stream, exotic colour space PDFBox can't rasterize in this
 * runtime) must never take down the whole compression. It must be left
 * byte-for-byte untouched, and its presence must not stop sibling images
 * from being optimized normally.
 *
 * <p>The fixture ({@link InvoiceCorpusFactory#mixedCorpusWithUndecodableImage()})
 * pairs one normal, large, optimizable photographic image with one
 * deliberately undecodable "poison" image XObject, see {@link
 * InvoiceCorpusFactory#embedRawImageXObject} for how it's built and why
 * decode failure is deterministic (tagged {@code /JPXDecode}; this project
 * has no JPEG2000 codec on its classpath).
 *
 * <p><b>RED by design against the current engine.</b> {@code ImageOptimizer#process}
 * has no per-image try/catch: a decode failure inside {@code hasTransparency()}
 * (which unconditionally calls {@code PDImageXObject#getImage()} once an
 * image clears the skip-gates) propagates as an {@code IOException}, which
 * {@code PdfCompressionEngine#compress} wraps and rethrows as {@code
 * PdfCompressionException}, which the web layer's {@code
 * CompressionExceptionHandler} maps straight to HTTP 500. That aborts the
 * ENTIRE request over a single bad image. A correct implementation catches
 * per-image decode/optimize failures and falls back to the original XObject,
 * untouched, for that image only.
 */
class UndecodableImagePassthroughTest {

    private final PdfCompressionProperties defaults = new PdfCompressionProperties();

    @Test
    void compressNeverThrowsForADocumentContainingAnUndecodableImage() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.mixedCorpusWithUndecodableImage();

        assertThatCode(() -> engine.compress(original, "mixed-poison.pdf", "req-poison-throws"))
                .as("a single undecodable image must never abort the whole compression, no exception, no 500")
                .doesNotThrowAnyException();
    }

    @Test
    void undecodableImagePassesThroughByteForByteWhileNormalImageIsStillOptimizedAndNoPageIsDropped() throws IOException {
        PdfCompressionEngine engine = new PdfCompressionEngine(defaults);
        byte[] original = InvoiceCorpusFactory.mixedCorpusWithUndecodableImage();

        byte[] poisonBefore;
        int pageCountBefore;
        try (PDDocument doc = Loader.loadPDF(original)) {
            pageCountBefore = doc.getNumberOfPages();
            poisonBefore = PoisonImageLocator.rawEncodedBytes(PoisonImageLocator.find(doc));
        }

        // Fails as a clean AssertionError (not an uncaught exception bubbling
        // out of the test method) if compress() throws, consistent with the
        // rest of this suite's RED-by-design failure style.
        assertThatCode(() -> engine.compress(original, "mixed-poison.pdf", "req-poison-passthrough"))
                .doesNotThrowAnyException();

        CompressionResult result = engine.compress(original, "mixed-poison.pdf", "req-poison-passthrough");
        byte[] compressed = result.getCompressedPdf();

        try (PDDocument doc = Loader.loadPDF(compressed)) {
            assertThat(doc.getNumberOfPages())
                    .as("no page may be dropped because one of its images was undecodable")
                    .isEqualTo(pageCountBefore);

            PDImageXObject poisonAfterImage = PoisonImageLocator.find(doc);
            byte[] poisonAfter = PoisonImageLocator.rawEncodedBytes(poisonAfterImage);
            assertThat(poisonAfter)
                    .as("the undecodable image's encoded stream bytes must be left byte-for-byte unchanged")
                    .isEqualTo(poisonBefore);
        }

        boolean normalImageWasOptimized = result.getImagesRecompressed() > 0
                || result.getImagesDownsampled() > 0
                || result.getCompressedBytes() < result.getOriginalBytes();
        assertThat(normalImageWasOptimized)
                .as("the normal, decodable image on the other page must still have been optimized " +
                        "despite the poison image's presence elsewhere in the document")
                .isTrue();
    }
}
