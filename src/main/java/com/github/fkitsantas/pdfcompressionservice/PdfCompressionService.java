package com.github.fkitsantas.pdfcompressionservice;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionOptions;
import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.InvalidPdfException;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionException;
import com.github.fkitsantas.pdfcompressionservice.metrics.CompressionMetrics;
import com.github.fkitsantas.pdfcompressionservice.web.CompressionExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * RESTful API endpoint for compressing PDF files. Image re-encoding and all
 * document-fidelity concerns live in {@link PdfCompressionEngine}; this
 * class is only responsible for the HTTP contract (multipart upload,
 * response headers, operational logging).
 *
 * <p>The upload is streamed to a temp file and the result is streamed back from
 * a temp file, so neither the whole input nor the whole output is ever held in
 * the heap - a burst of large uploads cannot exhaust memory. Both temp files are
 * always cleaned up (the output file when the response finishes writing).
 *
 * <p>Only operational metadata is logged here (request id, sizes, counts,
 * timing, success/failure), never document contents, text or bytes.
 */
@RestController
public class PdfCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(PdfCompressionService.class);

    private final PdfCompressionEngine engine;
    private final CompressionMetrics metrics;

    public PdfCompressionService(PdfCompressionEngine engine, CompressionMetrics metrics) {
        this.engine = engine;
        this.metrics = metrics;
    }

    /**
     * Compresses the uploaded PDF file and returns the optimized PDF as an
     * attachment named {@code optimized.pdf}.
     *
     * <p>All the compression tunables are optional query/form parameters; when
     * omitted the service-configured defaults apply, so the plain
     * {@code curl -F 'file=@in.pdf'} call is unchanged. An out-of-range value is
     * rejected as {@code 400 Bad Request}.
     *
     * @param file              the PDF file to be compressed (required multipart part named "file")
     * @param targetDpi         optional override for the target downsample resolution
     * @param jpegQuality       optional override for JPEG quality (0.0-1.0)
     * @param maxImageDimension optional override for the output longest-edge cap (0 = no cap)
     * @param stripMetadata     optional override for stripping XMP/Info metadata
     * @param deduplicateImages optional override for merging byte-identical images
     * @param request           current HTTP request, used only to publish the generated request id
     *                          for {@link CompressionExceptionHandler} to pick up on failure
     * @return the compressed PDF file as an {@link InputStreamResource}
     * @throws IOException if the multipart file's bytes cannot be read
     */
    @PostMapping("/compressPdf")
    public ResponseEntity<InputStreamResource> compressPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetDpi", required = false) Integer targetDpi,
            @RequestParam(value = "jpegQuality", required = false) Float jpegQuality,
            @RequestParam(value = "maxImageDimension", required = false) Integer maxImageDimension,
            @RequestParam(value = "stripMetadata", required = false) Boolean stripMetadata,
            @RequestParam(value = "deduplicateImages", required = false) Boolean deduplicateImages,
            HttpServletRequest request) throws IOException {
        String requestId = UUID.randomUUID().toString();
        // Correlation id for every log line produced while handling this request
        // (console + live /logs view); removed in the finally block below.
        MDC.put("requestId", requestId);
        Path uploadFile = null;
        Path outputFile = null;
        try {
            request.setAttribute(CompressionExceptionHandler.REQUEST_ID_ATTRIBUTE, requestId);

            CompressionOptions options = new CompressionOptions(
                    targetDpi, jpegQuality, maxImageDimension, stripMetadata, deduplicateImages);

            String originalFilename = file.getOriginalFilename();
            logger.info("requestId={} action=compress-start filename={} sizeBytes={} options={}",
                    requestId, originalFilename, file.getSize(), options);
            logger.debug("requestId={} action=upload-received contentType={} multipartField={}",
                    requestId, file.getContentType(), file.getName());

            uploadFile = Files.createTempFile("pcs-in-", ".pdf");
            outputFile = Files.createTempFile("pcs-out-", ".pdf");
            // Stream the upload to disk rather than buffering the whole file in the heap.
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, uploadFile, StandardCopyOption.REPLACE_EXISTING);
            }

            CompressionResult result;
            try (OutputStream sink = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
                result = engine.compress(uploadFile, file.getSize(), sink, originalFilename, requestId, options);
            } catch (InvalidPdfException | PdfCompressionException e) {
                logger.warn("requestId={} action=compress-failed reason={}", requestId, e.getClass().getSimpleName());
                metrics.recordFailure(e.getClass().getSimpleName());
                throw e;
            }
            metrics.recordSuccess(result);
            deleteQuietly(uploadFile); // input no longer needed
            uploadFile = null;

            logger.info("requestId={} action=compress-complete originalBytes={} compressedBytes={} savedBytes={} "
                            + "savedPercent={} pageCount={} imagesInspected={} imagesDownsampled={} "
                            + "imagesRecompressed={} imagesUnchanged={} profile={} durationMillis={} returnedOriginal={}",
                    requestId, result.getOriginalBytes(), result.getCompressedBytes(), result.getSavedBytes(),
                    result.getSavedPercent(), result.getPageCount(), result.getImagesInspected(),
                    result.getImagesDownsampled(), result.getImagesRecompressed(), result.getImagesUnchanged(),
                    result.getProfile(), result.getDurationMillis(), result.isReturnedOriginal());

            long length = result.getCompressedBytes();
            InputStreamResource resource = new InputStreamResource(deletingInputStream(outputFile));
            outputFile = null; // ownership handed to the response stream, which deletes it on close

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment().filename("optimized.pdf").build());
            // Compression stats for the browser UI to render (the body stays the PDF). Same-origin
            // fetch can read these directly; harmless to any other client that ignores them.
            headers.add("X-Original-Bytes", Long.toString(result.getOriginalBytes()));
            headers.add("X-Compressed-Bytes", Long.toString(result.getCompressedBytes()));
            headers.add("X-Saved-Bytes", Long.toString(result.getSavedBytes()));
            headers.add("X-Saved-Percent", Double.toString(result.getSavedPercent()));
            headers.add("X-Page-Count", Integer.toString(result.getPageCount()));
            headers.add("X-Images-Inspected", Integer.toString(result.getImagesInspected()));
            headers.add("X-Images-Downsampled", Integer.toString(result.getImagesDownsampled()));
            headers.add("X-Images-Recompressed", Integer.toString(result.getImagesRecompressed()));
            headers.add("X-Images-Unchanged", Integer.toString(result.getImagesUnchanged()));
            headers.add("X-Fonts-Subset", Integer.toString(result.getFontsSubset()));
            headers.add("X-Profile", result.getProfile());
            headers.add("X-Duration-Millis", Long.toString(result.getDurationMillis()));
            headers.add("X-Returned-Original", Boolean.toString(result.isReturnedOriginal()));
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(length)
                    .body(resource);
        } finally {
            // On any failure (or if ownership was not handed off) remove the temp files.
            deleteQuietly(uploadFile);
            deleteQuietly(outputFile);
            MDC.remove("requestId");
        }
    }

    /** An input stream over {@code file} that deletes the file once it is closed (after the response is written). */
    private static InputStream deletingInputStream(Path file) throws IOException {
        return new FilterInputStream(Files.newInputStream(file)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    deleteQuietly(file);
                }
            }
        };
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
