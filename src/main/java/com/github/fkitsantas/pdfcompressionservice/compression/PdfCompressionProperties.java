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

    /** Upper bound accepted for {@link #minDimension}, in pixels. */
    private static final int MAX_MIN_DIMENSION = 10_000;

    private int targetDpi = 150;
    private int maxImageDimension = 0;
    private long maxDecodePixels = 500_000_000L;
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
    private boolean deduplicateImages = true;
    private boolean stripMetadata = false;
    private boolean stripPrivateData = false;
    private boolean logComposition = true;
    private boolean subsetFonts = true;

    /**
     * Returns an independent copy carrying the same values, used to derive a
     * per-request "effective" configuration by layering {@link CompressionOptions}
     * overrides on top (see {@link PdfCompressionEngine}) without mutating this
     * shared singleton. All fields are already valid here, so they are copied
     * directly rather than re-validated through the setters.
     */
    public PdfCompressionProperties copy() {
        PdfCompressionProperties c = new PdfCompressionProperties();
        c.targetDpi = this.targetDpi;
        c.maxImageDimension = this.maxImageDimension;
        c.maxDecodePixels = this.maxDecodePixels;
        c.jpegQuality = this.jpegQuality;
        c.minDimension = this.minDimension;
        c.minByteSize = this.minByteSize;
        c.minReductionRatio = this.minReductionRatio;
        c.largerResultPolicy = this.largerResultPolicy;
        c.streamCache = this.streamCache;
        c.recompressCmyk = this.recompressCmyk;
        c.parallelism = this.parallelism;
        c.parallelImageThreshold = this.parallelImageThreshold;
        c.maxConcurrentCompressions = this.maxConcurrentCompressions;
        c.deduplicateImages = this.deduplicateImages;
        c.stripMetadata = this.stripMetadata;
        c.stripPrivateData = this.stripPrivateData;
        c.logComposition = this.logComposition;
        c.subsetFonts = this.subsetFonts;
        return c;
    }

    public int getTargetDpi() {
        return targetDpi;
    }

    public void setTargetDpi(int targetDpi) {
        Assert.isTrue(targetDpi >= 1 && targetDpi <= MAX_TARGET_DPI,
                () -> "pdf.compression.target-dpi must be between 1 and " + MAX_TARGET_DPI + " but was " + targetDpi);
        this.targetDpi = targetDpi;
    }

    /**
     * Optional hard cap, in pixels, on the longest edge of a re-encoded image.
     * This is <b>not</b> a limit on what the service accepts: images of any
     * resolution (e.g. 48-megapixel, 6000+px) are always accepted and
     * processed. It only caps the <em>output</em> resolution as a safety net
     * for the rare image whose on-page size cannot be determined; images drawn
     * on a page are already reduced by the target-DPI downsampling well below
     * any such cap.
     *
     * <p>Defaults to {@code 0}, meaning <b>no cap</b> - output resolution is
     * governed solely by {@link #getTargetDpi()}. Set a positive value to
     * additionally clamp the longest edge to that many pixels.
     */
    public int getMaxImageDimension() {
        return maxImageDimension;
    }

    public void setMaxImageDimension(int maxImageDimension) {
        Assert.isTrue(maxImageDimension >= 0,
                () -> "pdf.compression.max-image-dimension must be >= 0 (0 = no cap) but was " + maxImageDimension);
        this.maxImageDimension = maxImageDimension;
    }

    /**
     * Safety ceiling on an image's <b>declared</b> pixel count (width x height)
     * above which the engine will <b>not decode</b> it, leaving it untouched in
     * the output. This guards against a decode bomb: an image whose encoded
     * stream is tiny but whose declared dimensions are enormous (e.g. a
     * 60000x60000 Flate/JBIG2 image), which would allocate gigabytes when
     * decoded to a raster and exhaust the heap.
     *
     * <p>This is <b>not</b> an acceptance limit: the document is always accepted
     * and returned in full; only that one oversized image is passed through
     * unoptimized. The default ({@code 500_000_000}, 500 megapixels) sits far
     * above any legitimate photographic or large-format scanned image (a 48 MP
     * photo, or an A0 page at 600 DPI, is well under it) while still stopping the
     * gigapixel bombs. {@code 0} disables the guard entirely.
     *
     * <p>Deliberately not overridable per request (see {@link CompressionOptions}):
     * a client must not be able to raise the service's decode-time memory ceiling.
     */
    public long getMaxDecodePixels() {
        return maxDecodePixels;
    }

    public void setMaxDecodePixels(long maxDecodePixels) {
        Assert.isTrue(maxDecodePixels >= 0L,
                () -> "pdf.compression.max-decode-pixels must be >= 0 (0 = disabled) but was " + maxDecodePixels);
        this.maxDecodePixels = maxDecodePixels;
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
     * "auto" and is resolved at the point of use, see
     * {@link #resolveParallelism()}, to {@link Runtime#availableProcessors()}.
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
     * default) means "auto", resolved by {@link #resolveMaxConcurrentCompressions()}
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

    /**
     * Whether to collapse byte-identical images that are embedded as separate
     * objects into a single shared object (default {@code true}). Producers
     * frequently embed a repeated logo/letterhead as a distinct object on every
     * page; the engine already shares images that reference one object, and this
     * additionally merges duplicates that do not, which can be a large saving on
     * multi-page documents. Only images whose full fingerprint (dimensions,
     * colour space, filters, encoded bytes and soft mask) matches are merged, so
     * it is lossless.
     */
    public boolean isDeduplicateImages() {
        return deduplicateImages;
    }

    public void setDeduplicateImages(boolean deduplicateImages) {
        this.deduplicateImages = deduplicateImages;
    }

    /**
     * Whether to strip document metadata (the XMP {@code /Metadata} stream and
     * the {@code /Info} dictionary) from the output (default {@code false}).
     * This removes titles, authors, timestamps and producer strings, so it is
     * opt-in: it saves a little space and avoids leaking that metadata, at the
     * cost of losing it.
     */
    public boolean isStripMetadata() {
        return stripMetadata;
    }

    public void setStripMetadata(boolean stripMetadata) {
        this.stripMetadata = stripMetadata;
    }

    /**
     * Whether to strip application-private data that is not needed to render the
     * document (default {@code false}, opt-in). Specifically the {@code /PieceInfo}
     * dictionaries (private data producers like Illustrator/InDesign embed, often
     * large) on the catalog and pages, and page {@code /Thumb} thumbnail images
     * (which viewers regenerate). Removing them is lossless for the visible page
     * content but discards that editor round-trip data, hence opt-in.
     */
    public boolean isStripPrivateData() {
        return stripPrivateData;
    }

    public void setStripPrivateData(boolean stripPrivateData) {
        this.stripPrivateData = stripPrivateData;
    }

    /**
     * Whether to log a document-composition report (a byte breakdown by images /
     * fonts / vectors / other) after each PDF is processed (default {@code true}).
     * The line is emitted at INFO and so also appears on the live {@code /logs}
     * page; it shows where a document's bytes actually live, which is a
     * diagnostic aid for deciding whether vector-level optimization would be
     * worthwhile beyond the image pass. Purely observational, no effect on output.
     */
    public boolean isLogComposition() {
        return logComposition;
    }

    public void setLogComposition(boolean logComposition) {
        this.logComposition = logComposition;
    }

    /**
     * Whether to subset embedded fonts, re-embedding only the glyphs the document
     * actually uses (default {@code true}). Lossless and conservative: only
     * composite (CIDFontType2) TrueType fonts that are not already subset are
     * touched, and any font that cannot be subset provably safely is left exactly
     * as-is (see {@code TrueTypeSubsetter}). It shrinks font-heavy documents while
     * leaving text, spacing and appearance unchanged.
     */
    public boolean isSubsetFonts() {
        return subsetFonts;
    }

    public void setSubsetFonts(boolean subsetFonts) {
        this.subsetFonts = subsetFonts;
    }
}
