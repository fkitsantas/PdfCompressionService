package com.github.fkitsantas.pdfcompressionservice.job;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the asynchronous job API ({@code /jobs}), bound from
 * {@code pdf.compression.async.*}. The synchronous {@code POST /compressPdf}
 * endpoint is unaffected by these.
 */
@Component
@ConfigurationProperties(prefix = "pdf.compression.async")
@Validated
public class AsyncJobProperties {

    /**
     * Maximum number of jobs that may be in flight (submitted but not yet
     * finished) at once. A submission past this limit is rejected with
     * {@code 429 Too Many Requests} rather than queued unbounded, bounding how
     * much upload data can accumulate on disk. Must be {@code >= 1}.
     */
    private int maxActiveJobs = 100;

    /**
     * How long a finished job (and its compressed result on disk) is retained
     * and pollable/fetchable after completion, before it is evicted and its temp
     * files deleted. Must be positive.
     */
    private Duration retention = Duration.ofHours(1);

    public int getMaxActiveJobs() {
        return maxActiveJobs;
    }

    public void setMaxActiveJobs(int maxActiveJobs) {
        Assert.isTrue(maxActiveJobs >= 1,
                () -> "pdf.compression.async.max-active-jobs must be >= 1 but was " + maxActiveJobs);
        this.maxActiveJobs = maxActiveJobs;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        Assert.notNull(retention, "pdf.compression.async.retention must not be null");
        Assert.isTrue(!retention.isNegative() && !retention.isZero(),
                () -> "pdf.compression.async.retention must be positive but was " + retention);
        this.retention = retention;
    }
}
