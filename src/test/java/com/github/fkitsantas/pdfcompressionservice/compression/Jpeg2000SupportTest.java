package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.IOException;

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
 * Proves JPEG2000 (JPXDecode) images are decoded and recompressed rather than
 * passed through untouched. This depends on the jai-imageio JPEG2000 ImageIO
 * plugin being on the (runtime) classpath: without it the JPX image would be
 * undecodable and the engine would leave it in place. The fixture is a genuine
 * JP2 stream, so a green run here is end-to-end evidence the codec is wired up.
 */
class Jpeg2000SupportTest {

    @Test
    void jpxImageIsDecodedRecompressedAndRewrittenAsJpeg() throws IOException {
        byte[] pdf = InvoiceCorpusFactory.jpeg2000Image();

        CompressionResult result = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(pdf, "jpx.pdf", "req-jpx");

        assertThat(result.getImagesInspected())
                .as("the JPX image must actually be decoded and inspected, not skipped as undecodable")
                .isPositive();
        assertThat(result.getImagesRecompressed())
                .as("a decoded JPX photo is re-encoded (to JPEG)")
                .isPositive();
        assertThat(result.getCompressedBytes())
                .as("recompressing the JPX image yields a smaller document")
                .isLessThan(result.getOriginalBytes());

        assertThat(imageSuffixes(result.getCompressedPdf()))
                .as("the output image is now a JPEG, no longer a JPEG2000 (jpx) stream")
                .contains("jpg")
                .doesNotContain("jpx");
    }

    private static java.util.List<String> imageSuffixes(byte[] pdf) throws IOException {
        java.util.List<String> suffixes = new java.util.ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.getXObject(name) instanceof PDImageXObject image) {
                        suffixes.add(image.getSuffix());
                    }
                }
            }
        }
        return suffixes;
    }
}
