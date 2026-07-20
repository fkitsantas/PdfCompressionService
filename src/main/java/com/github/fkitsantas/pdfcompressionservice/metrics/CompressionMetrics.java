package com.github.fkitsantas.pdfcompressionservice.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.fkitsantas.pdfcompressionservice.compression.CompressionResult;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Records Micrometer metrics for every compression request, surfaced at
 * {@code /actuator/prometheus} (and {@code /actuator/metrics}). Kept separate
 * from {@link PdfCompressionEngine} so the engine stays a plain, directly
 * testable object with no telemetry dependency; the engine only exposes the two
 * gauge accessors this class binds.
 *
 * <p>Meter names are namespaced under {@code pcs.} (pdf-compression-service):
 * <ul>
 *   <li>{@code pcs.compression.duration} - timer of engine processing time,
 *       tagged {@code outcome}=compressed|original;</li>
 *   <li>{@code pcs.compression.requests} - counter of completed compressions,
 *       tagged {@code outcome};</li>
 *   <li>{@code pcs.compression.failures} - counter of failed compressions,
 *       tagged {@code reason} (the exception's simple name);</li>
 *   <li>{@code pcs.compression.bytes.in|out|saved} - cumulative byte totals;</li>
 *   <li>{@code pcs.compression.saved.percent} - distribution of the per-request
 *       saved percentage;</li>
 *   <li>{@code pcs.images} - counter of images handled, tagged {@code action}=
 *       inspected|downsampled|recompressed|unchanged;</li>
 *   <li>{@code pcs.compression.slots.max|inflight} - admission-gate gauges.</li>
 * </ul>
 */
@Component
public class CompressionMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary savedPercent;

    public CompressionMetrics(MeterRegistry registry, PdfCompressionEngine engine) {
        this.registry = registry;
        this.savedPercent = DistributionSummary.builder("pcs.compression.saved.percent")
                .description("Per-request size reduction, as a percentage of the original")
                .baseUnit("percent")
                .register(registry);
        registry.gauge("pcs.compression.slots.max", engine, PdfCompressionEngine::getMaxConcurrentCompressions);
        registry.gauge("pcs.compression.slots.inflight", engine, PdfCompressionEngine::getInFlightCompressions);
    }

    /** Records a successful compression from its result. */
    public void recordSuccess(CompressionResult result) {
        String outcome = result.isReturnedOriginal() ? "original" : "compressed";
        Timer.builder("pcs.compression.duration")
                .description("Time spent compressing a PDF in the engine")
                .tag("outcome", outcome)
                .register(registry)
                .record(Duration.ofMillis(result.getDurationMillis()));
        registry.counter("pcs.compression.requests", "outcome", outcome).increment();

        registry.counter("pcs.compression.bytes.in").increment(result.getOriginalBytes());
        registry.counter("pcs.compression.bytes.out").increment(result.getCompressedBytes());
        registry.counter("pcs.compression.bytes.saved").increment(Math.max(0, result.getSavedBytes()));
        savedPercent.record(Math.max(0.0, result.getSavedPercent()));

        registry.counter("pcs.images", "action", "inspected").increment(result.getImagesInspected());
        registry.counter("pcs.images", "action", "downsampled").increment(result.getImagesDownsampled());
        registry.counter("pcs.images", "action", "recompressed").increment(result.getImagesRecompressed());
        registry.counter("pcs.images", "action", "unchanged").increment(result.getImagesUnchanged());
    }

    /** Records a failed compression, tagged with the failure's simple class name. */
    public void recordFailure(String reason) {
        registry.counter("pcs.compression.failures", "reason", reason).increment();
    }
}
