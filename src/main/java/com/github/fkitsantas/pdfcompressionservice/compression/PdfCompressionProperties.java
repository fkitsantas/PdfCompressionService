package com.github.fkitsantas.pdfcompressionservice.compression;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for {@link PdfCompressionEngine}, bound from {@code pdf.compression.*}.
 *
 * <p><b>Implementation note / assumption:</b> the project's Maven build runs
 * offline and {@code pom.xml} may not be edited for this task, and neither
 * {@code jakarta.validation-api} nor a {@code Validator} implementation
 * (e.g. Hibernate Validator, normally pulled in via
 * {@code spring-boot-starter-validation}) is present anywhere in the local
 * repository. Declarative {@code jakarta.validation.constraints.*}
 * annotations therefore cannot be used. Bounds are instead enforced
 * imperatively in each setter (via {@link Assert}), which throws
 * {@link IllegalArgumentException} on out-of-range values regardless of
 * whether Spring's bean-validation machinery is on the classpath. The
 * {@link Validated} annotation is kept for forward compatibility: once
 * {@code spring-boot-starter-validation} is added to the POM, declarative
 * constraints can be layered on top without changing this contract.
 */
@Component
@ConfigurationProperties(prefix = "pdf.compression")
@Validated
public class PdfCompressionProperties {

    /** Upper bound accepted for {@link #targetDpi}; generous enough for archival-quality scans. */
    private static final int MAX_TARGET_DPI = 2400;

    /** Upper bound accepted for {@link #maxImageDimension}, in pixels. */
    private static final int MAX_IMAGE_DIMENSION_CEILING = 20_000;

    /** Upper bound accepted for {@link #minDimension}, in pixels. */
    private static final int MAX_MIN_DIMENSION = 10_000;

    private int targetDpi = 150;
    private int maxImageDimension = 3000;
    private float jpegQuality = 0.75f;
    private int minDimension = 16;
    private long minByteSize = 8192L;
    private float minReductionRatio = 0.10f;
    private LargerResultPolicy largerResultPolicy = LargerResultPolicy.KEEP_ORIGINAL;
    private StreamCacheMode streamCache = StreamCacheMode.TEMP_FILE;
    private boolean recompressCmyk = false;
    private int parallelism = 0;
    private int parallelImageThreshold = 2;
    private int maxConcurrentCompressions = 0;

    public int getTargetDpi() {
        return targetDpi;
    }

    public void setTargetDpi(int targetDpi) {
        Assert.isTrue(targetDpi >= 1 && targetDpi <= MAX_TARGET_DPI,
                () -> "pdf.compression.target-dpi must be between 1 and " + MAX_TARGET_DPI + " but was " + targetDpi);
        this.targetDpi = targetDpi;
    }

    public int getMaxImageDimension() {
        return maxImageDimension;
    }

    public void setMaxImageDimension(int maxImageDimension) {
        Assert.isTrue(maxImageDimension >= 1 && maxImageDimension <= MAX_IMAGE_DIMENSION_CEILING,
                () -> "pdf.compression.max-image-dimension must be between 1 and " + MAX_IMAGE_DIMENSION_CEILING
                        + " but was " + maxImageDimension);
        this.maxImageDimension = maxImageDimension;
    }

    public float getJpegQuality() {
        return jpegQuality;
    }

    public void setJpegQuality(float jpegQuality) {
        Assert.isTrue(jpegQuality >= 0.0f && jpegQuality <= 1.0f,
                () -> "pdf.compression.jpeg-quality must be within [0.0, 1.0] but was " + jpegQuality);
        this.jpegQuality = jpegQuality;
    }

    public int getMinDimension() {
        return minDimension;
    }

    public void setMinDimension(int minDimension) {
        Assert.isTrue(minDimension >= 1 && minDimension <= MAX_MIN_DIMENSION,
                () -> "pdf.compression.min-dimension must be between 1 and " + MAX_MIN_DIMENSION
                        + " but was " + minDimension);
        this.minDimension = minDimension;
    }

    public long getMinByteSize() {
        return minByteSize;
    }

    public void setMinByteSize(long minByteSize) {
        Assert.isTrue(minByteSize >= 0L,
                () -> "pdf.compression.min-byte-size must be >= 0 but was " + minByteSize);
        this.minByteSize = minByteSize;
    }

    public float getMinReductionRatio() {
        return minReductionRatio;
    }

    public void setMinReductionRatio(float minReductionRatio) {
        Assert.isTrue(minReductionRatio >= 0.0f && minReductionRatio <= 1.0f,
                () -> "pdf.compression.min-reduction-ratio must be within [0.0, 1.0] but was " + minReductionRatio);
        this.minReductionRatio = minReductionRatio;
    }

    public LargerResultPolicy getLargerResultPolicy() {
        return largerResultPolicy;
    }

    public void setLargerResultPolicy(LargerResultPolicy largerResultPolicy) {
        Assert.notNull(largerResultPolicy, "pdf.compression.larger-result-policy must not be null");
        this.largerResultPolicy = largerResultPolicy;
    }

    public StreamCacheMode getStreamCache() {
        return streamCache;
    }

    public void setStreamCache(StreamCacheMode streamCache) {
        Assert.notNull(streamCache, "pdf.compression.stream-cache must not be null");
        this.streamCache = streamCache;
    }

    public boolean isRecompressCmyk() {
        return recompressCmyk;
    }

    public void setRecompressCmyk(boolean recompressCmyk) {
        this.recompressCmyk = recompressCmyk;
    }

    /**
     * Number of worker threads {@link PdfCompressionEngine} dedicates to
     * per-image resize/encode work (decode and attach stay on the calling
     * thread; see the engine's class Javadoc). {@code 0} (the default) means
     * "auto" and is resolved at the point of use - see
     * {@link #resolveParallelism()} - to {@link Runtime#availableProcessors()}.
     * Negative values are rejected outright; there is no "disable
     * parallelism" magic number, use {@code 1} for that.
     */
    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        Assert.isTrue(parallelism >= 0,
                () -> "pdf.compression.parallelism must be >= 0 (0 = auto) but was " + parallelism);
        this.parallelism = parallelism;
    }

    /**
     * Resolves {@link #getParallelism()} to a concrete, usable worker count:
     * {@code 0} becomes {@link Runtime#availableProcessors()}; any explicit
     * positive value is returned unchanged. Callers (the engine's executor
     * construction) must call this rather than reading {@link #getParallelism()}
     * directly, so "auto" is resolved against the runtime it actually executes on.
     */
    public int resolveParallelism() {
        return parallelism == 0 ? Runtime.getRuntime().availableProcessors() : parallelism;
    }

    /**
     * Minimum number of eligible (post-skip-gate) images a document must
     * contain before the engine bothers dispatching per-image work to the
     * parallel executor; at or below this count the sequential path is used
     * instead, since parallel dispatch overhead would not pay off. Defaults
     * to {@code 2}. Must be {@code >= 0} ({@code 0} means "always prefer the
     * parallel path once there is at least one eligible image").
     */
    public int getParallelImageThreshold() {
        return parallelImageThreshold;
    }

    public void setParallelImageThreshold(int parallelImageThreshold) {
        Assert.isTrue(parallelImageThreshold >= 0,
                () -> "pdf.compression.parallel-image-threshold must be >= 0 but was " + parallelImageThreshold);
        this.parallelImageThreshold = parallelImageThreshold;
    }

    /**
     * Maximum number of documents {@link PdfCompressionEngine} will process
     * concurrently. This is an admission gate that bounds peak heap: each
     * in-flight compression holds a whole {@link org.apache.pdfbox.pdmodel.PDDocument}
     * plus decoded images in memory, and with virtual-thread request handling
     * the servlet container no longer caps how many requests run at once, so
     * without this a burst of large uploads could exhaust the heap. Excess
     * requests block (they do not fail) until a permit frees up. {@code 0} (the
     * default) means "auto" - resolved by {@link #resolveMaxConcurrentCompressions()}
     * to a generous multiple of the CPU count. Must be {@code >= 0}.
     */
    public int getMaxConcurrentCompressions() {
        return maxConcurrentCompressions;
    }

    public void setMaxConcurrentCompressions(int maxConcurrentCompressions) {
        Assert.isTrue(maxConcurrentCompressions >= 0,
                () -> "pdf.compression.max-concurrent-compressions must be >= 0 (0 = auto) but was "
                        + maxConcurrentCompressions);
        this.maxConcurrentCompressions = maxConcurrentCompressions;
    }

    /**
     * Resolves {@link #getMaxConcurrentCompressions()} to a concrete permit
     * count: {@code 0} becomes {@code availableProcessors() * 4} (generous
     * enough not to throttle normal throughput, while still bounding how many
     * full documents can be resident at once); any explicit positive value is
     * returned unchanged.
     */
    public int resolveMaxConcurrentCompressions() {
        return maxConcurrentCompressions == 0
                ? Math.max(1, Runtime.getRuntime().availableProcessors() * 4)
                : maxConcurrentCompressions;
    }
}
