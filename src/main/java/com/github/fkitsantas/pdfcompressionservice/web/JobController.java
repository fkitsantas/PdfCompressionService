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

    @GetMapping("/jobs/{id}")
    public JobView status(@PathVariable String id) {
        return JobView.from(jobs.get(id).snapshot());
    }

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
