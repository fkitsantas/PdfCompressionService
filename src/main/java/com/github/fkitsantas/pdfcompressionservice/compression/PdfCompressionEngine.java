package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stateless, thread-safe engine that re-encodes the images embedded in a PDF
 * to reduce payload size while preserving document fidelity (page geometry,
 * rotation, text, colour spaces, transparency, form XObjects, bookmarks,
 * annotations and AcroForm fields).
 *
 * <p>Only image XObjects are ever touched. Content streams (page, form or
 * annotation appearance) are never rewritten by this engine, the {@code cm}
 * placement matrices are left exactly as they are, which is what keeps page
 * geometry, rotation and aspect ratio inherently intact.
 *
 * <p>Processing outline:
 * <ol>
 *   <li>Load the document, honouring {@link PdfCompressionProperties#getStreamCache()}.</li>
 *   <li>Analyze every page's content stream (recursing into nested Form
 *       XObjects automatically) to learn the maximum size, in PDF points,
 *       each image is ever rendered at, its "usage".</li>
 *   <li>Recursively discover every unique image XObject reachable from page
 *       resources, nested form resources and annotation appearance streams,
 *       de-duplicated by underlying COS object identity so a shared image is
 *       processed exactly once.</li>
 *   <li>Ask {@link ImageOptimizer} to decide, per unique image, whether/how
 *       to re-encode it, then patch the accepted replacement back into every
 *       resources dictionary that referenced the original.</li>
 *   <li>Save the candidate document and compare it against the original
 *       bytes per {@link PdfCompressionProperties#getLargerResultPolicy()},
 *       falling back to the original bytes verbatim when recompression does
 *       not pay off.</li>
 * </ol>
 */
@Service
public class PdfCompressionEngine {

    private static final Logger log = LoggerFactory.getLogger(PdfCompressionEngine.class);

    /**
     * Stable prefix for every worker thread backing {@link #imageProcessingExecutor}.
     * Pinned so tests can identify "real parallel image work happened" purely by
     * observing thread names, with no reliance on wall-clock timing. Implementers
     * MUST route every per-image resize/encode task through this executor and MUST
     * NOT rename or bypass this thread factory, or the correctness/bounded-pool
     * tests in the {@code concurrency} test package lose their signal.
     */
    public static final String IMAGE_THREAD_NAME_PREFIX = "pdf-img-";

    /**
     * Shared across every {@code ThreadPoolExecutor} this class creates (i.e.
     * across every {@link PdfCompressionEngine} instance in the JVM), so worker
     * thread names are globally unique and safe to use as {@code Set<String>}
     * membership keys in tests without risking same-name collisions between
     * independently constructed engines.
     */
    private static final AtomicInteger IMAGE_THREAD_COUNTER = new AtomicInteger();

    private final PdfCompressionProperties properties;

    /**
     * Single, bounded, daemon-threaded executor owned by this engine instance
     * and shared across every {@link #compress} call made on it (i.e. across
     * concurrent requests when this engine is the Spring-managed singleton
     * bean), never a per-request pool. Sized from
     * {@link PdfCompressionProperties#resolveParallelism()} at construction
     * time.
     *
     * <p>{@link #compress} dispatches the CPU-heavy resize/encode step
     * ({@link ImageOptimizer#transform}) of eligible images onto this
     * executor once an eligible-image count exceeds {@link
     * PdfCompressionProperties#getParallelImageThreshold()} and {@link
     * PdfCompressionProperties#resolveParallelism()} is more than {@code 1}
     *, see {@link #processImages}. Decoding ({@link ImageOptimizer#finishPlan})
     * and attaching ({@link ImageOptimizer#attach}) always stay on the
     * calling thread, since both touch the shared {@link PDDocument}, which
     * PDFBox does not support concurrent access to.
     */
    private final ExecutorService imageProcessingExecutor;

    /**
     * Admission gate bounding how many documents are processed concurrently, so
     * peak heap stays bounded even though virtual-thread request handling lets
     * an unlimited number of requests arrive at once (each in-flight
     * compression holds a whole {@link PDDocument} plus decoded images in
     * memory). Sized from {@link PdfCompressionProperties#resolveMaxConcurrentCompressions()};
     * fair so requests are admitted in arrival order. Excess requests block on
     * {@link #compress} until a permit frees up, they are never rejected.
     */
    private final Semaphore compressionPermits;

    public PdfCompressionEngine(PdfCompressionProperties properties) {
        this.properties = properties;
        this.imageProcessingExecutor = createImageProcessingExecutor(properties);
        this.compressionPermits = new Semaphore(properties.resolveMaxConcurrentCompressions(), true);
    }

    /**
     * How long an idle {@code pdf-img-*} worker thread is kept alive before it
     * terminates (paired with {@code allowCoreThreadTimeOut(true)} below). This
     * pool bursts: a request's image batch fans out and finishes quickly, then
     * the pool goes quiet, often for a long time. Rather than pin
     * {@code resolveParallelism()} OS threads on an idle service forever, idle
     * workers expire after this window and respawn on the next burst, at the
     * cost of a little thread-start latency. This is a pure resource-hygiene
     * value, correctness and the concurrency tests do not depend on it (tests
     * prove dispatch via the executor's task count and via fresh-engine thread
     * observation, never by waiting for threads to be reaped), so it is chosen
     * comfortably long enough to never expire mid-request.
     */
    private static final long WORKER_KEEP_ALIVE_SECONDS = 60L;

    private static ExecutorService createImageProcessingExecutor(PdfCompressionProperties properties) {
        int size = properties.resolveParallelism();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, IMAGE_THREAD_NAME_PREFIX + IMAGE_THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // corePoolSize == maximumPoolSize == size: with an unbounded queue, a
        // ThreadPoolExecutor only ever creates threads up to corePoolSize
        // (beyond that it just enqueues, maximumPoolSize is never consulted
        // once the queue accepts offers unconditionally), so this pair is
        // what actually bounds/guarantees worker creation up to `size`.
        // allowCoreThreadTimeOut(true) is what then lets those threads expire
        // (see WORKER_KEEP_ALIVE_SECONDS) instead of camping forever; the
        // next burst of work simply respawns fresh core threads on demand,
        // exactly as if none had ever existed, the pool identity, bound and
        // daemon/pdf-img-* naming are unaffected either way.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), threadFactory);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Exposes the engine's single shared image-processing executor, for
     * Spring wiring ({@code PdfCompressionExecutorConfig}) and for tests to
     * assert boundedness/sizing. Not intended for production callers outside
     * this package.
     */
    public ExecutorService getImageProcessingExecutor() {
        return imageProcessingExecutor;
    }

    /** Shuts the shared executor down when this singleton bean is destroyed. */
    @PreDestroy
    public void shutdownImageProcessingExecutor() {
        imageProcessingExecutor.shutdown();
    }

    /**
     * Compresses the given PDF bytes.
     *
     * @param pdfBytes         raw bytes of the source PDF
     * @param originalFilename the uploaded filename, for logging/diagnostics only (may be {@code null})
     * @param requestId        caller-supplied or generated correlation id, echoed back on
     *                         {@link CompressionResult#getRequestId()} and in any error payload
     * @return the compression outcome, including the resulting PDF bytes
     * @throws InvalidPdfException      if {@code pdfBytes} is not a loadable PDF (malformed/truncated)
     * @throws PdfCompressionException  if a structurally valid PDF could not be processed
     */
    public CompressionResult compress(byte[] pdfBytes, String originalFilename, String requestId)
            throws InvalidPdfException, PdfCompressionException {
        if (pdfBytes == null) {
            throw new InvalidPdfException("PDF bytes must not be null");
        }
        long startNanos = System.nanoTime();

        // Admission gate (see #compressionPermits): bounds how many full documents are resident at once.
        boolean permitAcquired = false;
        try {
            compressionPermits.acquire();
            permitAcquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfCompressionException("Interrupted while awaiting a compression permit for request " + requestId, e);
        }

        try (PDDocument doc = loadDocument(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();

            Map<COSBase, float[]> usage = analyzeImageUsage(doc, requestId);

            Map<COSBase, PDImageXObject> uniqueImages = new LinkedHashMap<>();
            Map<COSBase, List<ImageRef>> referencesByImage = new IdentityHashMap<>();
            Set<COSBase> visitedResources = Collections.newSetFromMap(new IdentityHashMap<>());
            int discoveryPageIndex = 0;
            for (PDPage page : doc.getPages()) {
                // Isolate per page: a broken page resources dict or annotation must not
                // abort discovery for the rest of the document.
                try {
                    collectImages(page.getResources(), uniqueImages, referencesByImage, visitedResources);
                    collectAnnotationImages(page, uniqueImages, referencesByImage, visitedResources);
                } catch (Exception e) {
                    log.warn("Image discovery failed for request {} page #{} - skipping its images "
                            + "(exception: {})", requestId, discoveryPageIndex, e.getClass().getName());
                }
                discoveryPageIndex++;
            }

            ImageOptimizer optimizer = new ImageOptimizer(properties);
            ImageProcessingStats stats = processImages(doc, uniqueImages, referencesByImage, usage, optimizer,
                    requestId);
            int inspected = stats.inspected();
            int downsampled = stats.downsampled();
            int recompressed = stats.recompressed();
            int unchanged = stats.unchanged();

            byte[] candidateBytes = save(doc);

            long originalLen = pdfBytes.length;
            long candidateLen = candidateBytes.length;
            boolean useOriginal;
            if (properties.getLargerResultPolicy() == LargerResultPolicy.KEEP_ORIGINAL) {
                boolean meetsRatio = candidateLen <= originalLen * (1.0 - properties.getMinReductionRatio());
                useOriginal = !meetsRatio;
            } else {
                useOriginal = candidateLen >= originalLen;
            }

            byte[] finalBytes = useOriginal ? pdfBytes : candidateBytes;
            long compressedLen = finalBytes.length;
            long savedBytes = originalLen - compressedLen;
            double savedPercent = originalLen == 0 ? 0.0 : (100.0 * savedBytes) / originalLen;
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            String profile = "dpi=" + properties.getTargetDpi() + ",q=" + properties.getJpegQuality();

            CompressionResult result = new CompressionResult(
                    requestId,
                    originalLen,
                    compressedLen,
                    savedBytes,
                    savedPercent,
                    pageCount,
                    inspected,
                    downsampled,
                    recompressed,
                    unchanged,
                    profile,
                    durationMillis,
                    useOriginal,
                    finalBytes);

            log.debug("Compressed request {} ({}): {} -> {} bytes ({} pages, inspected={}, downsampled={}, "
                            + "recompressed={}, unchanged={}, returnedOriginal={})",
                    requestId, originalFilename, originalLen, compressedLen, pageCount, inspected, downsampled,
                    recompressed, unchanged, useOriginal);
            return result;
        } catch (InvalidPdfException | PdfCompressionException e) {
            throw e;
        } catch (IOException e) {
            throw new PdfCompressionException("Failed to process PDF for request " + requestId, e);
        } finally {
            if (permitAcquired) {
                compressionPermits.release();
            }
        }
    }

    // ------------------------------------------------------------------
    // Phased, batched, parallel image pipeline
    // ------------------------------------------------------------------

    private record ImageProcessingStats(int inspected, int downsampled, int recompressed, int unchanged) {
    }

    /**
     * Per-work-item outcome of the Phase B transform step. Capturing this
     * instead of letting a pool task's exception propagate is what keeps one
     * poisoned image from aborting {@code invokeAll} (and therefore every
     * sibling task in the same batch).
     */
    private record TransformAttempt(int index, ImageOptimizer.Transformed transformed, Exception failure) {
    }

    /**
     * Runs every unique image through the three-phase pipeline described on
     * {@link ImageOptimizer}'s class Javadoc, batched so peak memory stays
     * bounded to roughly one batch of decoded/encoded images, and dispatches
     * the CPU-heavy Phase B transform step to {@link #imageProcessingExecutor}
     * once there are more eligible images than {@link
     * PdfCompressionProperties#getParallelImageThreshold()} (and parallelism
     * is actually usable, i.e. {@code resolveParallelism() != 1}).
     *
     * <p>Every per-image failure, during the gate check, decode/classify,
     * transform or attach, is caught here and turned into {@link
     * ImageOptimizer.Outcome#unchanged()} (original XObject left in place,
     * counted as unchanged) rather than allowed to propagate: a single
     * undecodable/corrupt/exotic image must never abort the whole request or
     * take a sibling image down with it, on either the serial or the
     * parallel path.
     *
     * <p>Phase C (attach) always runs back on the calling thread, strictly in
     * original discovery order, regardless of which pool thread finished its
     * Phase B work first or in what order, this is what keeps {@code
     * COSWriter} object numbering, and therefore {@code compressedBytes},
     * identical between a serial and a parallel run of the same input.
     */
    private ImageProcessingStats processImages(PDDocument doc,
                                                Map<COSBase, PDImageXObject> uniqueImages,
                                                Map<COSBase, List<ImageRef>> referencesByImage,
                                                Map<COSBase, float[]> usage,
                                                ImageOptimizer optimizer,
                                                String requestId) throws PdfCompressionException {
        List<Map.Entry<COSBase, PDImageXObject>> entries = new ArrayList<>(uniqueImages.entrySet());
        int total = entries.size();
        ImageOptimizer.Outcome[] outcomes = new ImageOptimizer.Outcome[total];

        // Phase A part 1: cheap, no-decode gate check for every image, serially. Metadata-only (no raster data),
        // so holding every pending gate result for the whole document costs nothing worth batching.
        Map<Integer, ImageOptimizer.GatePassed> pendingGates = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            PDImageXObject original = entries.get(i).getValue();
            float[] usagePoints = usage.get(entries.get(i).getKey());
            try {
                ImageOptimizer.GateResult gateResult = optimizer.evaluateGate(original, usagePoints);
                if (gateResult instanceof ImageOptimizer.GateResult.Decided decided) {
                    outcomes[i] = decided.outcome();
                } else {
                    pendingGates.put(i, ((ImageOptimizer.GateResult.Pending) gateResult).gate());
                }
            } catch (Exception e) {
                // Never even gate-evaluated -> count as skipped (not inspected), original left in place.
                logImageFailure(requestId, i, "gate-check", e);
                outcomes[i] = ImageOptimizer.Outcome.skip();
            }
        }

        List<Integer> pendingIndices = new ArrayList<>(pendingGates.keySet());
        int parallelism = properties.resolveParallelism();
        boolean useParallel = pendingIndices.size() > properties.getParallelImageThreshold() && parallelism != 1;
        int batchSize = Math.max(1, parallelism);

        for (int start = 0; start < pendingIndices.size(); start += batchSize) {
            List<Integer> batch = pendingIndices.subList(start, Math.min(start + batchSize, pendingIndices.size()));

            // Phase A part 2: decode + classify THIS BATCH ONLY, serially (PDFBox stream access is not
            // thread-safe), so peak memory stays bounded to ~one batch of decoded images at a time.
            Map<Integer, ImageOptimizer.Planned> plannedBatch = new LinkedHashMap<>();
            for (int idx : batch) {
                try {
                    plannedBatch.put(idx, optimizer.finishPlan(pendingGates.get(idx)));
                } catch (Exception e) {
                    // Could not be decoded, so never actually inspected -> skipped (not inspected).
                    logImageFailure(requestId, idx, "decode", e);
                    outcomes[idx] = ImageOptimizer.Outcome.skip();
                }
            }
            List<Integer> readyIndices = new ArrayList<>(plannedBatch.keySet());

            // Phase B: CPU-heavy resize/encode, off the PDDocument entirely, safe to fan out across the pool.
            List<TransformAttempt> attempts = useParallel
                    ? transformBatchInParallel(readyIndices, plannedBatch, optimizer, requestId)
                    : transformBatchSerially(readyIndices, plannedBatch, optimizer);

            // Phase C: attach back to the document, serially, in the batch's (== original discovery) order.
            for (TransformAttempt attempt : attempts) {
                int idx = attempt.index();
                if (attempt.failure() != null) {
                    logImageFailure(requestId, idx, "transform", attempt.failure());
                    outcomes[idx] = ImageOptimizer.Outcome.unchanged();
                    continue;
                }
                try {
                    outcomes[idx] = optimizer.attach(doc, plannedBatch.get(idx), attempt.transformed());
                } catch (Exception e) {
                    logImageFailure(requestId, idx, "attach", e);
                    outcomes[idx] = ImageOptimizer.Outcome.unchanged();
                }
            }
            // plannedBatch/attempts fall out of scope here, their decoded BufferedImages/encoded byte[]s become
            // eligible for GC before the next batch starts, bounding peak memory to ~one batch's worth.
        }

        int inspected = 0;
        int downsampled = 0;
        int recompressed = 0;
        int unchanged = 0;
        for (int i = 0; i < total; i++) {
            ImageOptimizer.Outcome outcome = outcomes[i];
            if (outcome.skipped()) {
                unchanged++;
                continue;
            }
            inspected++;
            if (outcome.replacement() == null) {
                unchanged++;
                continue;
            }
            if (outcome.downsampled()) {
                downsampled++;
            }
            if (outcome.recompressed()) {
                recompressed++;
            }
            for (ImageRef ref : referencesByImage.getOrDefault(entries.get(i).getKey(), List.of())) {
                ref.resources().put(ref.name(), outcome.replacement());
            }
        }

        return new ImageProcessingStats(inspected, downsampled, recompressed, unchanged);
    }

    private List<TransformAttempt> transformBatchSerially(List<Integer> indices,
                                                            Map<Integer, ImageOptimizer.Planned> plannedBatch,
                                                            ImageOptimizer optimizer) {
        List<TransformAttempt> results = new ArrayList<>(indices.size());
        for (int idx : indices) {
            try {
                results.add(new TransformAttempt(idx, optimizer.transform(plannedBatch.get(idx)), null));
            } catch (Exception e) {
                results.add(new TransformAttempt(idx, null, e));
            }
        }
        return results;
    }

    /**
     * Dispatches this batch's Phase B work to {@link #imageProcessingExecutor}
     * via {@link ExecutorService#invokeAll}, which submits every task then
     * blocks until all complete, each task catches its own exception
     * internally and returns a failed {@link TransformAttempt} rather than
     * letting it propagate, so one poisoned image's {@code Future} can never
     * abort the others in the same {@code invokeAll} call.
     */
    private List<TransformAttempt> transformBatchInParallel(List<Integer> indices,
                                                              Map<Integer, ImageOptimizer.Planned> plannedBatch,
                                                              ImageOptimizer optimizer,
                                                              String requestId) throws PdfCompressionException {
        List<Callable<TransformAttempt>> tasks = new ArrayList<>(indices.size());
        for (int idx : indices) {
            ImageOptimizer.Planned planned = plannedBatch.get(idx);
            tasks.add(() -> {
                try {
                    return new TransformAttempt(idx, optimizer.transform(planned), null);
                } catch (Exception e) {
                    return new TransformAttempt(idx, null, e);
                }
            });
        }

        try {
            List<Future<TransformAttempt>> futures = imageProcessingExecutor.invokeAll(tasks);
            List<TransformAttempt> results = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                int idx = indices.get(i);
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    // Defensive only: the callables above always catch their own exceptions internally, so this
                    // should be unreachable in practice.
                    results.add(new TransformAttempt(idx, null, e));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfCompressionException(
                    "Interrupted while processing images in parallel for request " + requestId, e);
        }
    }

    /**
     * Operational-only WARN log: request id, image index/phase and exception
     * class, deliberately never the exception message or any document
     * content, since a decode failure's message can echo raw stream/filter
     * details.
     */
    private void logImageFailure(String requestId, int imageIndex, String phase, Throwable failure) {
        log.warn("Per-image {} failure for request {} image #{} - leaving original untouched (exception: {})",
                phase, requestId, imageIndex, failure.getClass().getName());
    }

    // ------------------------------------------------------------------
    // Load / save
    // ------------------------------------------------------------------

    private PDDocument loadDocument(byte[] pdfBytes) {
        try {
            if (properties.getStreamCache() == StreamCacheMode.TEMP_FILE) {
                RandomAccessReadBuffer source = new RandomAccessReadBuffer(pdfBytes);
                try {
                    return Loader.loadPDF(source, IOUtils.createTempFileOnlyStreamCache());
                } catch (IOException e) {
                    // On success the returned PDDocument owns and closes `source`; on
                    // failure it never takes ownership, so release it here.
                    IOUtils.closeQuietly(source);
                    throw e;
                }
            }
            return Loader.loadPDF(pdfBytes);
        } catch (IOException e) {
            throw new InvalidPdfException("The supplied bytes could not be loaded as a PDF document", e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Usage analysis
    // ------------------------------------------------------------------

    private Map<COSBase, float[]> analyzeImageUsage(PDDocument doc, String requestId) {
        Map<COSBase, float[]> usage = new IdentityHashMap<>();
        int pageIndex = 0;
        for (PDPage page : doc.getPages()) {
            // Isolate per page: a single malformed content stream must not abort usage
            // analysis for the whole document. Images on a failed page simply get no
            // usage data and fall back to the conservative max-dimension cap only.
            try {
                new ImageUsageAnalyzer(page, usage).processPage(page);
            } catch (Exception e) {
                log.warn("Usage analysis failed for request {} page #{} - its images fall back to the "
                        + "dimension cap only (exception: {})", requestId, pageIndex, e.getClass().getName());
            }
            pageIndex++;
        }
        return usage;
    }

    // ------------------------------------------------------------------
    // Image discovery (recursive, deduplicated)
    // ------------------------------------------------------------------

    private record ImageRef(PDResources resources, COSName name) {
    }

    private void collectImages(PDResources resources,
                                Map<COSBase, PDImageXObject> uniqueImages,
                                Map<COSBase, List<ImageRef>> referencesByImage,
                                Set<COSBase> visitedResources) throws IOException {
        if (resources == null) {
            return;
        }
        COSBase resourcesKey = resources.getCOSObject();
        if (!visitedResources.add(resourcesKey)) {
            // Already walked (cycle guard / shared-form de-duplication).
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            // Isolate per XObject: one unreadable entry (corrupt stream, unsupported
            // form) must not lose the other images in the same resources dict.
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDImageXObject image) {
                    COSBase key = image.getCOSObject();
                    uniqueImages.putIfAbsent(key, image);
                    referencesByImage.computeIfAbsent(key, k -> new ArrayList<>()).add(new ImageRef(resources, name));
                } else if (xobject instanceof PDFormXObject form) {
                    collectImages(form.getResources(), uniqueImages, referencesByImage, visitedResources);
                }
            } catch (Exception e) {
                log.warn("Skipping unreadable XObject '{}' during discovery (exception: {})",
                        name.getName(), e.getClass().getName());
            }
        }
    }

    private void collectAnnotationImages(PDPage page,
                                          Map<COSBase, PDImageXObject> uniqueImages,
                                          Map<COSBase, List<ImageRef>> referencesByImage,
                                          Set<COSBase> visitedResources) throws IOException {
        for (PDAnnotation annotation : page.getAnnotations()) {
            PDAppearanceDictionary appearance = annotation.getAppearance();
            if (appearance == null) {
                continue;
            }
            collectAppearanceEntry(appearance.getNormalAppearance(), uniqueImages, referencesByImage, visitedResources);
            collectAppearanceEntry(appearance.getRolloverAppearance(), uniqueImages, referencesByImage, visitedResources);
            collectAppearanceEntry(appearance.getDownAppearance(), uniqueImages, referencesByImage, visitedResources);
        }
    }

    private void collectAppearanceEntry(PDAppearanceEntry entry,
                                         Map<COSBase, PDImageXObject> uniqueImages,
                                         Map<COSBase, List<ImageRef>> referencesByImage,
                                         Set<COSBase> visitedResources) throws IOException {
        if (entry == null) {
            return;
        }
        if (entry.isStream()) {
            PDAppearanceStream stream = entry.getAppearanceStream();
            if (stream != null) {
                collectImages(stream.getResources(), uniqueImages, referencesByImage, visitedResources);
            }
        } else if (entry.isSubDictionary()) {
            for (PDAppearanceStream stream : entry.getSubDictionary().values()) {
                if (stream != null) {
                    collectImages(stream.getResources(), uniqueImages, referencesByImage, visitedResources);
                }
            }
        }
    }

    protected PdfCompressionProperties getProperties() {
        return properties;
    }
}
