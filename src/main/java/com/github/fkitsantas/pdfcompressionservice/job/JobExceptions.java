package com.github.fkitsantas.pdfcompressionservice.job;

/**
 * Exceptions the async job API raises, mapped to HTTP status codes by the web
 * layer. Grouped here to keep the small job-control exceptions in one place.
 */
public final class JobExceptions {

    private JobExceptions() {
    }

    /** No job exists (or it exists no longer) for the requested id. Maps to {@code 404 Not Found}. */
    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String jobId) {
            super("No compression job found for id " + jobId);
        }
    }

    /** The job exists but has not SUCCEEDED, so there is no result to download yet. Maps to {@code 409 Conflict}. */
    public static class JobNotReadyException extends RuntimeException {
        public JobNotReadyException(String jobId, JobStatus status) {
            super("Compression job " + jobId + " is not ready for download (status " + status + ")");
        }
    }

    /** Too many jobs are already in flight. Maps to {@code 429 Too Many Requests}. */
    public static class TooManyActiveJobsException extends RuntimeException {
        public TooManyActiveJobsException(int maxActiveJobs) {
            super("Too many active compression jobs (limit " + maxActiveJobs + "); retry later");
        }
    }
}
