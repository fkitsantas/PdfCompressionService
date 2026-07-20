package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the decode-bomb guard: an image whose declared pixel count exceeds
 * {@code max-decode-pixels} is never decoded (so its raster is never allocated)
 * and is passed through untouched, while the document as a whole is still
 * returned. Rather than craft a genuine gigapixel bomb, the ceiling is set below
 * a normal fixture image's size to force the same no-decode path.
 */
class DecodeBombGuardTest {

    @Test
    void imageExceedingThePixelCeilingIsPassedThroughWithoutBeingDecoded() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(1);

        PdfCompressionProperties guarded = new PdfCompressionProperties();
        guarded.setMaxDecodePixels(1_000L); // any real image has far more than 1000 pixels
        CompressionResult result = new PdfCompressionEngine(guarded)
                .compress(pdf, "big.pdf", "req-bomb");

        assertThat(result.getImagesInspected())
                .as("the oversized image is gated out before decode, so it is never inspected")
                .isZero();
        assertThat(result.getImagesRecompressed()).isZero();
        assertThat(result.getImagesDownsampled()).isZero();
        assertThat(result.isReturnedOriginal())
                .as("nothing was re-encoded, so the original is returned unchanged")
                .isTrue();
    }

    @Test
    void withTheDefaultCeilingTheSameImageIsProcessedNormally() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.multipleLargeInvoiceImages(1);

        CompressionResult result = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(pdf, "big.pdf", "req-normal");

        assertThat(result.getImagesInspected())
                .as("the default 500 MP ceiling is well above a normal image, which is processed")
                .isPositive();
        assertThat(result.getImagesRecompressed()).isPositive();
    }
}
