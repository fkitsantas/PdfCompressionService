package com.github.fkitsantas.pdfcompressionservice.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;

/**
 * Contributes a {@code compression} entry to {@code /actuator/health}. Because
 * every request now streams the upload and the result through temp files, the
 * service is only healthy if the temp directory is actually writable; this
 * indicator probes that (create + delete a marker file) and reports it DOWN
 * otherwise, so a full or read-only disk surfaces on the health endpoint rather
 * than as a flood of failed compressions. It also publishes the admission-gate
 * usage (in-flight vs. maximum) as details.
 */
@Component
public class CompressionHealthIndicator implements HealthIndicator {

    private final PdfCompressionEngine engine;

    public CompressionHealthIndicator(PdfCompressionEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        int max = engine.getMaxConcurrentCompressions();
        int inFlight = engine.getInFlightCompressions();
        Health.Builder builder;
        Path probe = null;
        try {
            probe = Files.createTempFile("pcs-health-", ".tmp");
            builder = Health.up();
        } catch (IOException e) {
            return Health.down(e)
                    .withDetail("tempDirWritable", false)
                    .withDetail("maxConcurrentCompressions", max)
                    .withDetail("inFlightCompressions", inFlight)
                    .build();
        } finally {
            deleteQuietly(probe);
        }
        return builder
                .withDetail("tempDirWritable", true)
                .withDetail("maxConcurrentCompressions", max)
                .withDetail("inFlightCompressions", inFlight)
                .build();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort cleanup of the health-probe marker
        }
    }
}
