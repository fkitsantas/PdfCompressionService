package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers opt-in stripping of application-private data ({@code /PieceInfo} on the
 * catalog and pages, and page {@code /Thumb} thumbnails): removed when enabled,
 * preserved by default.
 */
class PrivateDataStripTest {

    private static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");
    private static final COSName THUMB = COSName.getPDFName("Thumb");

    @Test
    void stripsPieceInfoAndThumbnailsWhenEnabled() throws IOException {
        byte[] pdf = pdfWithPrivateData();

        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setStripPrivateData(true);
        props.setMinReductionRatio(0.0f); // keep any smaller result so the strip is observable
        byte[] out = new PdfCompressionEngine(props).compress(pdf, "d.pdf", "req-strip").getCompressedPdf();

        try (PDDocument doc = Loader.loadPDF(out)) {
            assertThat(doc.getDocumentCatalog().getCOSObject().getItem(PIECE_INFO)).isNull();
            assertThat(doc.getPage(0).getCOSObject().getItem(PIECE_INFO)).isNull();
            assertThat(doc.getPage(0).getCOSObject().getItem(THUMB)).isNull();
        }
    }

    @Test
    void keepsPrivateDataByDefault() throws IOException {
        byte[] pdf = pdfWithPrivateData();

        byte[] out = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(pdf, "d.pdf", "req-keep").getCompressedPdf();

        try (PDDocument doc = Loader.loadPDF(out)) {
            assertThat(doc.getDocumentCatalog().getCOSObject().getItem(PIECE_INFO)).isNotNull();
            assertThat(doc.getPage(0).getCOSObject().getItem(PIECE_INFO)).isNotNull();
        }
    }

    private static byte[] pdfWithPrivateData() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            doc.getDocumentCatalog().getCOSObject().setItem(PIECE_INFO, largeDataDict(doc));
            page.getCOSObject().setItem(PIECE_INFO, largeDataDict(doc));
            page.getCOSObject().setItem(THUMB, largeStream(doc));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static COSDictionary largeDataDict(PDDocument doc) throws IOException {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.getPDFName("Private"), largeStream(doc));
        return dict;
    }

    private static COSStream largeStream(PDDocument doc) throws IOException {
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream()) {
            out.write(new byte[20000]);
        }
        return stream;
    }
}
