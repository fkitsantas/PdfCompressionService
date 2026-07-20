package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the contract of the streaming {@code compress(Path, long, OutputStream, ...)}
 * entry point the HTTP layer uses: the chosen output is written to the sink (never
 * held in the returned result), the statistics match the in-heap {@code byte[]} path,
 * and the same fidelity/error guarantees hold. The temp file the engine allocates for
 * its compression candidate must not leak.
 */
class StreamingCompressionTest {

    @Test
    void writesCompressedOutputToSinkAndReturnsMatchingStatsWithoutBufferingBytes(@TempDir Path tmp)
            throws IOException {
        byte[] source = InvoiceCorpusFactory.multipleLargeInvoiceImages(3);
        Path sourceFile = tmp.resolve("source.pdf");
        Files.write(sourceFile, source);

        CompressionResult heap = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(source, "invoices.pdf", "req-heap");

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        long tempFilesBefore = countEngineTempFiles();
        CompressionResult streamed = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(sourceFile, source.length, sink, "invoices.pdf", "req-stream");

        assertThat(streamed.getCompressedPdf())
                .as("streaming result carries no in-heap payload; the bytes went to the sink")
                .isEmpty();
        assertThat(sink.size())
                .as("Content-Length the controller reports must equal the bytes written to the sink")
                .isEqualTo((int) streamed.getCompressedBytes());
        assertThat(streamed.getOriginalBytes()).isEqualTo(source.length);
        assertThat(streamed.getCompressedBytes())
                .as("the streaming and in-heap paths compress to the same size")
                .isEqualTo(heap.getCompressedBytes());
        assertThat(countEngineTempFiles())
                .as("the engine's compression-candidate temp file must be cleaned up")
                .isEqualTo(tempFilesBefore);

        try (PDDocument doc = Loader.loadPDF(sink.toByteArray())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
        }
    }

    @Test
    void returnsOriginalUnchangedThroughTheSinkWhenCompressionDoesNotPayOff(@TempDir Path tmp)
            throws IOException {
        byte[] source = InvoiceCorpusFactory.alreadyOptimalPdf();
        Path sourceFile = tmp.resolve("optimal.pdf");
        Files.write(sourceFile, source);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CompressionResult streamed = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(sourceFile, source.length, sink, "optimal.pdf", "req-original");

        assertThat(streamed.isReturnedOriginal()).isTrue();
        assertThat(sink.toByteArray())
                .as("when the original is kept, the exact source bytes are streamed back")
                .isEqualTo(source);
    }

    @Test
    void malformedSourceThrowsInvalidPdfException(@TempDir Path tmp) throws IOException {
        Path sourceFile = tmp.resolve("corrupt.pdf");
        byte[] garbage = InvoiceCorpusFactory.corruptBytes();
        Files.write(sourceFile, garbage);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertThatThrownBy(() -> new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(sourceFile, garbage.length, sink, "corrupt.pdf", "req-bad"))
                .isInstanceOf(InvalidPdfException.class);
    }

    /** Counts the engine's candidate temp files still present in the temp dir (leak detector). */
    private static long countEngineTempFiles() throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("pcs-candidate-")).count();
        }
    }
}
