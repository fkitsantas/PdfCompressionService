package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies recompressed photographic images are written as <b>progressive</b>
 * JPEG (a smaller file at the same quality), by finding the SOF2 marker in the
 * output image's stream.
 */
class ProgressiveJpegTest {

    @Test
    void recompressedJpegIsProgressive() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.singleExtremelyLargePhotographicImage();
        CompressionResult result = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(pdf, "photo.pdf", "req-progressive");

        byte[] jpeg = firstDctImage(result.getCompressedPdf());
        assertThat(jpeg).as("the output should contain a recompressed JPEG").isNotNull();
        assertThat(isProgressive(jpeg)).as("the JPEG must be progressive (SOF2)").isTrue();
    }

    private static byte[] firstDctImage(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.getXObject(name) instanceof PDImageXObject image && "jpg".equals(image.getSuffix())) {
                        try (InputStream in = image.getCOSObject().createRawInputStream()) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Scans the JPEG's marker segments (up to the first scan) for SOF2 (0xFFC2 = progressive). */
    private static boolean isProgressive(byte[] jpeg) {
        for (int i = 2; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF) {
                int marker = jpeg[i + 1] & 0xFF;
                if (marker == 0xDA) {
                    return false; // start of scan reached, no progressive frame header seen
                }
                if (marker == 0xC2) {
                    return true; // SOF2
                }
            }
        }
        return false;
    }
}
