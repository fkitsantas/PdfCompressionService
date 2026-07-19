package com.github.fkitsantas.pdfcompressionservice.robustness;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionProperties;
import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Robustness for the image-discovery phase (which runs outside the per-image
 * pipeline): an XObject with an invalid {@code /Subtype} makes
 * {@code PDResources.getXObject(name)} throw during discovery. That failure
 * must be isolated to the offending entry, {@code compress()} must still
 * succeed, preserve the page, and optimise the valid sibling image, rather
 * than surfacing as an HTTP 500. Complements the decode-phase robustness tests
 * (undecodable image), which cover the per-image pipeline.
 */
class UnreadableXObjectRobustnessTest {

    @Test
    void unreadableXObjectIsIsolatedAndTheValidSiblingImageIsStillOptimized() throws IOException {
        byte[] source = InvoiceCorpusFactory.pdfWithUnreadableXObjectInResources();
        PdfCompressionEngine engine = new PdfCompressionEngine(new PdfCompressionProperties());

        CompressionResult[] holder = new CompressionResult[1];
        assertThatCode(() -> holder[0] = engine.compress(source, "unreadable-xobject.pdf", "req-unreadable"))
                .as("an unreadable XObject must be isolated during discovery, not fail the whole request with 500")
                .doesNotThrowAnyException();

        CompressionResult result = holder[0];
        assertThat(result.getPageCount())
                .as("the page is preserved despite the unreadable sibling XObject")
                .isEqualTo(1);
        assertThat(result.getCompressedBytes())
                .as("the valid sibling image must still have been optimised")
                .isLessThan(result.getOriginalBytes());
        try (PDDocument doc = Loader.loadPDF(result.getCompressedPdf())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }
}
