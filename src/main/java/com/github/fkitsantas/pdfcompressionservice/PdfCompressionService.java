package com.github.fkitsantas.pdfcompressionservice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.InvalidPdfException;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionException;
import com.github.fkitsantas.pdfcompressionservice.web.CompressionExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * RESTful API endpoint for compressing PDF files. Image re-encoding and all
 * document-fidelity concerns live in {@link PdfCompressionEngine}; this
 * class is only responsible for the HTTP contract (multipart upload,
 * response headers, operational logging).
 *
 * <p>Only operational metadata is logged here (request id, sizes, counts,
 * timing, success/failure), never document contents, text or bytes.
 */
@RestController
public class PdfCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(PdfCompressionService.class);

    private final PdfCompressionEngine engine;

    public PdfCompressionService(PdfCompressionEngine engine) {
        this.engine = engine;
    }

    /**
     * Compresses the uploaded PDF file and returns the optimized PDF as an
     * attachment named {@code optimized.pdf}.
     *
     * @param file    the PDF file to be compressed (required multipart part named "file")
     * @param request current HTTP request, used only to publish the generated request id
     *                for {@link CompressionExceptionHandler} to pick up on failure
     * @return the compressed PDF file as an {@link InputStreamResource}
     * @throws IOException if the multipart file's bytes cannot be read
     */
    @PostMapping("/compressPdf")
    public ResponseEntity<InputStreamResource> compressPdf(@RequestParam("file") MultipartFile file,
                                                             HttpServletRequest request) throws IOException {
        String requestId = UUID.randomUUID().toString();
        // Correlation id for every log line produced while handling this request
        // (console + live /logs view); removed in the finally block below.
        MDC.put("requestId", requestId);
        try {
            request.setAttribute(CompressionExceptionHandler.REQUEST_ID_ATTRIBUTE, requestId);

            String originalFilename = file.getOriginalFilename();
            logger.info("requestId={} action=compress-start filename={} sizeBytes={}",
                    requestId, originalFilename, file.getSize());
            logger.debug("requestId={} action=upload-received contentType={} multipartField={}",
                    requestId, file.getContentType(), file.getName());

            CompressionResult result;
            try {
                byte[] bytes = file.getBytes();
                result = engine.compress(bytes, originalFilename, requestId);
            } catch (InvalidPdfException | PdfCompressionException e) {
                logger.warn("requestId={} action=compress-failed reason={}", requestId, e.getClass().getSimpleName());
                throw e;
            }

            logger.info("requestId={} action=compress-complete originalBytes={} compressedBytes={} savedBytes={} "
                            + "savedPercent={} pageCount={} imagesInspected={} imagesDownsampled={} "
                            + "imagesRecompressed={} imagesUnchanged={} profile={} durationMillis={} returnedOriginal={}",
                    requestId, result.getOriginalBytes(), result.getCompressedBytes(), result.getSavedBytes(),
                    result.getSavedPercent(), result.getPageCount(), result.getImagesInspected(),
                    result.getImagesDownsampled(), result.getImagesRecompressed(), result.getImagesUnchanged(),
                    result.getProfile(), result.getDurationMillis(), result.isReturnedOriginal());

            byte[] compressedPdf = result.getCompressedPdf();
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(compressedPdf));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment().filename("optimized.pdf").build());

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(compressedPdf.length)
                    .body(resource);
        } finally {
            MDC.remove("requestId");
        }
    }
}
