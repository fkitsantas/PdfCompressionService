package com.github.fkitsantas.pdfcompressionservice.job;

/** Lifecycle state of an asynchronous compression job. */
public enum JobStatus {

    /** Accepted, waiting for a worker to pick it up. */
    QUEUED,

    /** A worker is actively compressing the upload. */
    RUNNING,

    /** Compression finished; the result is available for download. */
    SUCCEEDED,

    /** Compression failed; {@code error} carries the reason. */
    FAILED;

    /** Whether the job has reached a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
