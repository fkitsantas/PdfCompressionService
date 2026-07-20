package com.github.fkitsantas.pdfcompressionservice.job;

import java.nio.file.Path;
import java.time.Instant;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;

/**
 * Mutable, thread-safe record of a single asynchronous compression job. State
 * transitions ({@link #markRunning()}, {@link #markSucceeded}, {@link #markFailed})
 * and every read go through {@code synchronized} methods, so the worker thread
 * that processes the job and the request threads that poll it never see a torn
 * view. The {@code source}/{@code result} temp-file paths are managed by
 * {@link CompressionJobService}, which owns their deletion.
 */
public class CompressionJob {

    private final String id;
    private final String originalFilename;
    private final long submittedBytes;
    private final Instant submittedAt;
    private final Path source;

    private JobStatus status = JobStatus.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private CompressionResult result;
    private Path resultFile;
    private String error;

    public CompressionJob(String id, String originalFilename, long submittedBytes, Path source) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.submittedBytes = submittedBytes;
        this.source = source;
        this.submittedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public Path source() {
        return source;
    }

    public synchronized void markRunning() {
        this.status = JobStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public synchronized void markSucceeded(CompressionResult result, Path resultFile) {
        this.status = JobStatus.SUCCEEDED;
        this.result = result;
        this.resultFile = resultFile;
        this.finishedAt = Instant.now();
    }

    public synchronized void markFailed(String error) {
        this.status = JobStatus.FAILED;
        this.error = error;
        this.finishedAt = Instant.now();
    }

    public synchronized JobStatus status() {
        return status;
    }

    public synchronized Instant finishedAt() {
        return finishedAt;
    }

    /** The compressed result file, present only once the job has SUCCEEDED. */
    public synchronized Path resultFile() {
        return resultFile;
    }

    /** An immutable snapshot for building an API view without holding the lock afterwards. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(id, originalFilename, submittedBytes, status, submittedAt, startedAt, finishedAt,
                result, error);
    }

    /** Immutable point-in-time view of a job's fields. */
    public record Snapshot(String id, String originalFilename, long submittedBytes, JobStatus status,
                           Instant submittedAt, Instant startedAt, Instant finishedAt,
                           CompressionResult result, String error) {
    }
}
