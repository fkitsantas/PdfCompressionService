package com.github.fkitsantas.pdfcompressionservice.job;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;

/**
 * JSON representation of a {@link CompressionJob} returned by the {@code /jobs}
 * API. Fields that do not apply to the current state are omitted ({@code stats}
 * only on success, {@code error} only on failure, {@code resultUrl} only once
 * the result is downloadable).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobView(String jobId, JobStatus status, String filename, long submittedBytes,
                      Instant submittedAt, Instant startedAt, Instant finishedAt,
                      String resultUrl, Stats stats, String error) {

    /** Compression statistics, present only for a SUCCEEDED job. */
    public record Stats(long originalBytes, long compressedBytes, long savedBytes, double savedPercent,
                        int pageCount, int imagesInspected, int imagesRecompressed, long durationMillis,
                        boolean returnedOriginal) {

        static Stats from(CompressionResult r) {
            return new Stats(r.getOriginalBytes(), r.getCompressedBytes(), r.getSavedBytes(), r.getSavedPercent(),
                    r.getPageCount(), r.getImagesInspected(), r.getImagesRecompressed(), r.getDurationMillis(),
                    r.isReturnedOriginal());
        }
    }

    public static JobView from(CompressionJob.Snapshot s) {
        boolean succeeded = s.status() == JobStatus.SUCCEEDED;
        return new JobView(
                s.id(),
                s.status(),
                s.originalFilename(),
                s.submittedBytes(),
                s.submittedAt(),
                s.startedAt(),
                s.finishedAt(),
                succeeded ? "/jobs/" + s.id() + "/result" : null,
                succeeded && s.result() != null ? Stats.from(s.result()) : null,
                s.status() == JobStatus.FAILED ? s.error() : null);
    }
}
