package com.github.fkitsantas.pdfcompressionservice.job;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionOptions;
import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;
import com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotFoundException;
import com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.TooManyActiveJobsException;
import com.github.fkitsantas.pdfcompressionservice.metrics.CompressionMetrics;

import jakarta.annotation.PreDestroy;

/**
 * Runs PDF compression asynchronously for the {@code /jobs} API: the upload is
 * accepted and streamed to a temp file, a {@link CompressionJob} is registered,
 * and a worker compresses it off the request thread while the client polls.
 *
 * <p>Reuses the same streaming engine path as the synchronous endpoint (upload
 * temp file in, result temp file out, never the whole PDF in the heap) and the
 * same {@link CompressionMetrics}, so async compressions are counted identically.
 * The number of in-flight jobs is bounded ({@link AsyncJobProperties#getMaxActiveJobs()})
 * so accepted-but-unprocessed uploads cannot pile up on disk without limit, and
 * finished jobs (with their result files) are evicted after a retention window.
 */
@Service
public class CompressionJobService {

    private static final Logger logger = LoggerFactory.getLogger(CompressionJobService.class);

    private final PdfCompressionEngine engine;
    private final CompressionMetrics metrics;
    private final AsyncJobProperties properties;

    private final ConcurrentMap<String, CompressionJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger activeJobs = new AtomicInteger();
    // Virtual threads: each job blocks on PDF load/save I/O and on the engine's
    // admission gate, which is what actually bounds concurrent CPU/heap use.
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public CompressionJobService(PdfCompressionEngine engine, CompressionMetrics metrics,
                                 AsyncJobProperties properties) {
        this.engine = engine;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * Registers and asynchronously starts a compression job for an already-
     * spooled upload temp file. Ownership of {@code source} passes to the
     * service, which deletes it once the job finishes.
     *
     * @throws TooManyActiveJobsException if the in-flight job limit is reached
     */
    public CompressionJob submit(Path source, String originalFilename, long submittedBytes,
                                 CompressionOptions options) {
        // Fail fast on a bad override, before accepting the job, so the client gets a
        // 400 now instead of the job merely failing later on a worker thread.
        engine.validateOptions(options);
        // Reserve a slot up front; release it again if we do not actually start.
        if (activeJobs.incrementAndGet() > properties.getMaxActiveJobs()) {
            activeJobs.decrementAndGet();
            throw new TooManyActiveJobsException(properties.getMaxActiveJobs());
        }
        String id = UUID.randomUUID().toString();
        CompressionJob job = new CompressionJob(id, originalFilename, submittedBytes, source);
        jobs.put(id, job);
        logger.info("requestId={} action=job-submitted filename={} sizeBytes={} activeJobs={}",
                id, originalFilename, submittedBytes, activeJobs.get());
        try {
            workers.execute(() -> process(job, options));
        } catch (RuntimeException e) {
            // Could not even schedule the worker: undo the registration/slot.
            jobs.remove(id);
            activeJobs.decrementAndGet();
            deleteQuietly(source);
            throw e;
        }
        return job;
    }

    private void process(CompressionJob job, CompressionOptions options) {
        String id = job.id();
        MDC.put("requestId", id);
        Path resultFile = null;
        try {
            job.markRunning();
            resultFile = Files.createTempFile("pcs-job-", ".pdf");
            CompressionResult result;
            try (OutputStream sink = new BufferedOutputStream(Files.newOutputStream(resultFile))) {
                result = engine.compress(job.source(), job.snapshot().submittedBytes(), sink,
                        job.snapshot().originalFilename(), id, options);
            }
            job.markSucceeded(result, resultFile);
            metrics.recordSuccess(result);
            logger.info("requestId={} action=job-succeeded originalBytes={} compressedBytes={} durationMillis={}",
                    id, result.getOriginalBytes(), result.getCompressedBytes(), result.getDurationMillis());
        } catch (IOException | RuntimeException e) {
            deleteQuietly(resultFile);
            job.markFailed(e.getClass().getSimpleName());
            metrics.recordFailure(e.getClass().getSimpleName());
            logger.warn("requestId={} action=job-failed reason={}", id, e.getClass().getSimpleName());
        } finally {
            deleteQuietly(job.source()); // upload no longer needed once processed
            activeJobs.decrementAndGet();
            MDC.remove("requestId");
        }
    }

    /** Looks up a job, or throws {@link JobNotFoundException} if unknown/evicted. */
    public CompressionJob get(String id) {
        CompressionJob job = jobs.get(id);
        if (job == null) {
            throw new JobNotFoundException(id);
        }
        return job;
    }

    /** Number of jobs currently submitted but not yet finished. */
    public int activeJobCount() {
        return activeJobs.get();
    }

    /** Evicts finished jobs past the retention window and deletes their result files. */
    @Scheduled(fixedDelayString = "PT1M")
    public void evictExpiredJobs() {
        Instant cutoff = Instant.now().minus(properties.getRetention());
        jobs.values().removeIf(job -> {
            Instant finishedAt = job.finishedAt();
            if (finishedAt != null && finishedAt.isBefore(cutoff)) {
                deleteQuietly(job.resultFile());
                logger.debug("requestId={} action=job-evicted", job.id());
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        jobs.values().forEach(job -> {
            deleteQuietly(job.source());
            deleteQuietly(job.resultFile());
        });
        jobs.clear();
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
