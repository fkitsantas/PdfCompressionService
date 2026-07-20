package com.github.fkitsantas.pdfcompressionservice.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionOptions;
import com.github.fkitsantas.pdfcompressionservice.job.CompressionJob;
import com.github.fkitsantas.pdfcompressionservice.job.CompressionJobService;
import com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotReadyException;
import com.github.fkitsantas.pdfcompressionservice.job.JobStatus;
import com.github.fkitsantas.pdfcompressionservice.job.JobView;

/**
 * Asynchronous compression API for large uploads, complementing the synchronous
 * {@code POST /compressPdf}: submit a file, get a job id back immediately
 * ({@code 202 Accepted}), poll {@code GET /jobs/{id}} for status, then download
 * the compressed PDF from {@code GET /jobs/{id}/result} once it has SUCCEEDED.
 * The upload is streamed to a temp file exactly like the synchronous endpoint,
 * so submitting never buffers the whole PDF in the heap.
 */
@RestController
public class JobController {

    private static final Logger logger = LoggerFactory.getLogger(JobController.class);

    private final CompressionJobService jobs;

    public JobController(CompressionJobService jobs) {
        this.jobs = jobs;
    }

    /**
     * Accepts a PDF for asynchronous compression and returns immediately. The
     * upload is streamed to a temp file (never buffered whole in the heap) and
     * handed to {@link CompressionJobService}, which processes it on a worker
     * thread. The same optional per-request overrides as {@code POST /compressPdf}
     * apply and are validated up front.
     *
     * @param file              the PDF to compress (required multipart part named "file")
     * @param targetDpi         optional override for the target downsample resolution
     * @param jpegQuality       optional override for JPEG quality (0.0-1.0)
     * @param maxImageDimension optional override for the output longest-edge cap (0 = no cap)
     * @param stripMetadata     optional override for stripping XMP/Info metadata
     * @param deduplicateImages optional override for merging byte-identical images
     * @return {@code 202 Accepted} with the job view and a {@code Location} header pointing at
     *         {@code /jobs/{id}}; {@code 400} if an override is out of range, {@code 429} if the
     *         in-flight job limit is reached
     * @throws IOException if the upload cannot be spooled to disk
     */
    @PostMapping("/jobs")
    public ResponseEntity<JobView> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetDpi", required = false) Integer targetDpi,
            @RequestParam(value = "jpegQuality", required = false) Float jpegQuality,
            @RequestParam(value = "maxImageDimension", required = false) Integer maxImageDimension,
            @RequestParam(value = "stripMetadata", required = false) Boolean stripMetadata,
            @RequestParam(value = "deduplicateImages", required = false) Boolean deduplicateImages) throws IOException {
        CompressionOptions options = new CompressionOptions(
                targetDpi, jpegQuality, maxImageDimension, stripMetadata, deduplicateImages);

        // Stream the upload to a temp file; the job service takes ownership and deletes it.
        Path source = Files.createTempFile("pcs-jobin-", ".pdf");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(source);
            throw e;
        }

        CompressionJob job;
        try {
            job = jobs.submit(source, file.getOriginalFilename(), file.getSize(), options);
        } catch (RuntimeException e) {
            Files.deleteIfExists(source); // submission rejected before taking ownership
            throw e;
        }
        JobView view = JobView.from(job.snapshot());
        logger.info("requestId={} action=job-accepted filename={} sizeBytes={}",
                job.id(), file.getOriginalFilename(), file.getSize());
        return ResponseEntity.accepted().location(URI.create("/jobs/" + job.id())).body(view);
    }

    /**
     * Returns the current status view of a job (state, timestamps, and, once it
     * has SUCCEEDED, the result link and compression stats).
     *
     * @param id the job id returned by {@link #submit}
     * @return the job's {@link JobView}
     * @throws com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotFoundException
     *         ({@code 404}) if no such job exists or it has been evicted
     */
    @GetMapping("/jobs/{id}")
    public JobView status(@PathVariable String id) {
        return JobView.from(jobs.get(id).snapshot());
    }

    /**
     * Streams the compressed PDF for a completed job as an {@code optimized.pdf}
     * attachment. The result file is retained (not deleted on read) until the
     * retention window evicts the job, so it may be downloaded more than once.
     *
     * @param id the job id
     * @return {@code 200 OK} with the compressed PDF
     * @throws com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotFoundException
     *         ({@code 404}) if no such job exists
     * @throws com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotReadyException
     *         ({@code 409}) if the job has not SUCCEEDED yet
     * @throws IOException if the result file cannot be opened
     */
    @GetMapping("/jobs/{id}/result")
    public ResponseEntity<InputStreamResource> result(@PathVariable String id) throws IOException {
        CompressionJob job = jobs.get(id);
        CompressionJob.Snapshot snapshot = job.snapshot();
        if (snapshot.status() != JobStatus.SUCCEEDED) {
            throw new JobNotReadyException(id, snapshot.status());
        }
        Path resultFile = job.resultFile();
        if (resultFile == null || !Files.exists(resultFile)) {
            throw new JobNotReadyException(id, snapshot.status());
        }
        long length = Files.size(resultFile);
        // Read-only stream over the retained result; the file is NOT deleted on close
        // (it stays fetchable until the retention window evicts the job).
        InputStreamResource resource = new InputStreamResource(Files.newInputStream(resultFile));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename("optimized.pdf").build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(length)
                .body(resource);
    }
}
