package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the lossless document-level optimizations: collapsing byte-identical
 * images embedded as separate objects into one shared object, and the opt-in
 * stripping of XMP/Info metadata.
 */
class ImageDeduplicationTest {

    @Test
    void mergesIdenticalImagesEmbeddedAsSeparateObjects() throws IOException {
        byte[] source = InvoiceCorpusFactory.sameImageRepeatedAcrossPagesAsSeparateObjects(6);

        PdfCompressionProperties dedupOff = new PdfCompressionProperties();
        dedupOff.setDeduplicateImages(false);

        CompressionResult on = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(source, "logos.pdf", "req-dedup-on");
        CompressionResult off = new PdfCompressionEngine(dedupOff)
                .compress(source, "logos.pdf", "req-dedup-off");

        assertThat(distinctImageObjects(off.getCompressedPdf()))
                .as("without dedup the six per-page copies stay separate")
                .isEqualTo(6);
        assertThat(distinctImageObjects(on.getCompressedPdf()))
                .as("dedup collapses the six identical copies into one shared object")
                .isEqualTo(1);
        assertThat(on.getCompressedBytes())
                .as("dedup output is smaller than keeping every copy")
                .isLessThan(off.getCompressedBytes());

        try (PDDocument doc = Loader.loadPDF(on.getCompressedPdf())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(6); // no page lost
        }
    }

    @Test
    void stripMetadataRemovesXmpAndInfoWhenEnabledButKeepsThemByDefault() throws IOException {
        byte[] withMetadata = withMetadata(InvoiceCorpusFactory.multipleLargeInvoiceImages(1));
        assertThat(hasSensitiveMetadata(withMetadata)).as("fixture must actually carry metadata").isTrue();

        PdfCompressionProperties strip = new PdfCompressionProperties();
        strip.setStripMetadata(true);
        CompressionResult stripped = new PdfCompressionEngine(strip)
                .compress(withMetadata, "meta.pdf", "req-strip");
        assertThat(hasSensitiveMetadata(stripped.getCompressedPdf()))
                .as("strip-metadata removes the title/author and the XMP stream")
                .isFalse();

        CompressionResult kept = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(withMetadata, "meta.pdf", "req-keep");
        assertThat(hasSensitiveMetadata(kept.getCompressedPdf()))
                .as("metadata is preserved by default")
                .isTrue();
    }

    private static int distinctImageObjects(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xobject = resources.getXObject(name);
                    if (xobject instanceof PDImageXObject image) {
                        seen.add(image.getCOSObject());
                    }
                }
            }
            return seen.size();
        }
    }

    private static byte[] withMetadata(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Confidential Invoice");
            info.setAuthor("ACME Supplies Ltd");
            doc.setDocumentInformation(info);
            String xmp = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                    + "<x:xmpmeta xmlns:x='adobe:ns:meta/'></x:xmpmeta><?xpacket end='w'?>";
            doc.getDocumentCatalog().setMetadata(
                    new PDMetadata(doc, new ByteArrayInputStream(xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean hasSensitiveMetadata(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            boolean infoFields = info != null && (info.getTitle() != null || info.getAuthor() != null);
            boolean xmp = doc.getDocumentCatalog().getCOSObject().getItem(COSName.METADATA) != null;
            return infoFields || xmp;
        }
    }
}
