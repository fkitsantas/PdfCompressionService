package com.github.fkitsantas.pdfcompressionservice.quality;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compression-ratio quality gate for image-bearing invoices - the documents
 * that actually matter for size (e.g. Claris FileMaker Pro exports where an
 * embedded scan/photo/container image is the bulk of the payload). Text-only
 * pages are intentionally out of scope here: vector text is never rasterised,
 * so there is nothing to compress and a low whole-document ratio there is
 * correct, not a regression.
 *
 * <p>The floor is set at <b>50%</b> to lock in the requirement that image
 * invoices shrink by at least half. Measured savings with the default profile
 * are well above it (a multi-image invoice ~75%, a single high-resolution scan
 * ~98%), so the margin absorbs codec/JDK variation without flaking; the gate
 * exists to catch a real regression that stops images from being downsampled
 * or recompressed.
 */
class ImageCompressionQualityTest {

    private static final double MIN_SAVINGS_PERCENT = 50.0;

    private final PdfCompressionEngine engine = new PdfCompressionEngine(new PdfCompressionProperties());

    @Test
    void multiImageInvoiceCompressesByAtLeastHalf() throws IOException {
        byte[] source = InvoiceCorpusFactory.multipleLargeInvoiceImages(4);
        CompressionResult result = engine.compress(source, "multi-image-invoice.pdf", "req-quality-multi");

        assertThat(result.getImagesRecompressed())
                .as("every embedded invoice image should be re-encoded")
                .isGreaterThan(0);
        assertThat(result.getSavedPercent())
                .as("an image-bearing invoice must compress by at least %.0f%% (was %.1f%%)",
                        MIN_SAVINGS_PERCENT, result.getSavedPercent())
                .isGreaterThanOrEqualTo(MIN_SAVINGS_PERCENT);
    }

    @Test
    void highResolutionScanCompressesByAtLeastHalf() throws IOException {
        byte[] source = InvoiceCorpusFactory.singleExtremelyLargePhotographicImage();
        CompressionResult result = engine.compress(source, "high-res-scan.pdf", "req-quality-scan");

        assertThat(result.getImagesDownsampled())
                .as("an over-sampled full-page scan should be downsampled")
                .isGreaterThan(0);
        assertThat(result.getSavedPercent())
                .as("a high-resolution scanned page must compress by at least %.0f%% (was %.1f%%)",
                        MIN_SAVINGS_PERCENT, result.getSavedPercent())
                .isGreaterThanOrEqualTo(MIN_SAVINGS_PERCENT);
    }
}
