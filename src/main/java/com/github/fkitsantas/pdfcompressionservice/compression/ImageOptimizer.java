package com.github.fkitsantas.pdfcompressionservice.compression;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Decides, per unique image XObject, whether and how to re-encode it, and
 * exposes that decision as three explicit phases so {@link PdfCompressionEngine}
 * can fan the CPU-heavy middle phase out across its shared image-processing
 * executor while keeping every {@link PDDocument}-touching call on a single
 * thread:
 *
 * <ol>
 *   <li>{@link #evaluateGate}, cheap, no-decode gate check (skip gates,
 *       target dimensions, the bitonal "already sharp enough" shortcut).
 *       Safe to run for the whole document up front; touches only image
 *       metadata, never raster data.</li>
 *   <li>{@link #finishPlan}, decodes the image ({@link PDImageXObject#getImage()})
 *       and classifies its codec path (bitonal / transparent / grayscale /
 *       photographic-or-line-art). Reads from the {@link PDDocument}'s
 *       backing stream, so, like {@link #evaluateGate}, it must stay on a
 *       single thread; the engine only calls it for one in-flight batch at a
 *       time, never concurrently.</li>
 *   <li>{@link #transform}, pure CPU work (resize + encode) on an already-
 *       decoded, doc-independent {@link BufferedImage}. Touches no
 *       {@link PDDocument} state at all, so the engine is free to run this on
 *       its shared executor.</li>
 *   <li>{@link #attach}, builds the replacement {@link PDImageXObject} via
 *       the PDFBox image factories (which allocate {@link COSStream}s against
 *       the document) and applies the size guard. Must run back on the
 *       document thread, in the original discovery order, to keep object
 *       numbering, and therefore {@code compressedBytes}, independent of
 *       thread scheduling.</li>
 * </ol>
 *
 * <p>Every one of these four methods evaluates/produces exactly the same
 * decisions the original single-pass {@code process(PDDocument, PDImageXObject,
 * float[])} method used to: skip gates, {@code computeScale}, codec
 * selection and the size guard are unchanged, only the call shape changed,
 * to let the engine phase the work.
 */
final class ImageOptimizer {

    /** Downsample once the original exceeds the DPI-derived target by more than ~10%. */
    private static final double DOWNSAMPLE_TOLERANCE_SCALE = 1.0 / 1.1;

    /** Bitonal scans are left alone unless the effective DPI is more than 2x the target. */
    private static final double BITONAL_DOWNSAMPLE_SCALE = 0.5;

    private static final int MAX_DISTINCT_COLOR_SAMPLES = 20_000;
    private static final int PHOTOGRAPHIC_COLOR_THRESHOLD = 256;

    /**
     * Whether the grayscale JPEG path encodes off-document (via {@code
     * ImageIO} + {@link JPEGFactory#createFromStream}, letting it run on the
     * parallel executor) or stays on the document thread (via {@link
     * JPEGFactory#createFromImage}). Confirmed {@code true} is safe: a
     * single-component JPEG produced by the JRE's own {@code ImageIO} writer
     * and re-attached via {@code createFromStream} still round-trips as
     * {@link PDDeviceGray} (see {@code PdfCompressionEngineFidelityTest
     * #grayscaleImageStaysInDeviceGrayColorSpace}, which is green with this
     * flag on), documented here rather than silently assumed so a future
     * PDFBox upgrade regression is easy to bisect: flip to {@code false} and
     * re-run the fidelity suite if that ever stops being true.
     */
    private static final boolean ENCODE_GRAYSCALE_OFF_DOCUMENT = true;

    private final PdfCompressionProperties properties;

    ImageOptimizer(PdfCompressionProperties properties) {
        this.properties = properties;
    }

    /**
     * Outcome of evaluating one unique image.
     *
     * @param replacement the accepted replacement, or {@code null} if the original should be kept
     * @param downsampled whether pixel dimensions were reduced
     * @param recompressed whether the image was re-encoded (regardless of dimension change)
     * @param skipped whether the image never entered evaluation (a skip-gate matched)
     */
    record Outcome(PDImageXObject replacement, boolean downsampled, boolean recompressed, boolean skipped) {

        static Outcome skip() {
            return new Outcome(null, false, false, true);
        }

        static Outcome unchanged() {
            return new Outcome(null, false, false, false);
        }

        static Outcome accepted(PDImageXObject replacement, boolean downsampled) {
            return new Outcome(replacement, downsampled, true, false);
        }
    }

    /** Codec path an eligible image was classified into during {@link #finishPlan}. */
    enum Codec {
        BITONAL, TRANSPARENT, GRAYSCALE, COLOR
    }

    /** No-decode gate outcome: either the decision is already final, or the image needs full planning. */
    sealed interface GateResult permits GateResult.Decided, GateResult.Pending {
        record Decided(Outcome outcome) implements GateResult {
        }

        record Pending(GatePassed gate) implements GateResult {
        }
    }

    /** Everything {@link #finishPlan} needs, computed without decoding the image. */
    record GatePassed(PDImageXObject original, long originalLength, boolean bitonal, int targetW, int targetH,
                       boolean downsample) {
    }

    /** A fully-classified, decoded work item ready for {@link #transform}. Carries no live document state. */
    record Planned(PDImageXObject original, long originalLength, Codec codec, BufferedImage decoded, int targetW,
                    int targetH, boolean downsample) {
    }

    /**
     * Output of {@link #transform}: either encoded JPEG bytes (photographic
     * colour/grayscale paths, safe to have produced off-document) or a
     * resized {@link BufferedImage} still awaiting a document-thread factory
     * call (lossless/CCITT paths, and grayscale if {@link
     * #ENCODE_GRAYSCALE_OFF_DOCUMENT} is ever flipped off). Exactly one of
     * {@code jpegBytes}/{@code bufferedImage} is non-null.
     */
    record Transformed(Codec codec, byte[] jpegBytes, BufferedImage bufferedImage, boolean downsampled) {
        static Transformed jpeg(Codec codec, byte[] bytes, boolean downsampled) {
            return new Transformed(codec, bytes, null, downsampled);
        }

        static Transformed image(Codec codec, BufferedImage image, boolean downsampled) {
            return new Transformed(codec, null, image, downsampled);
        }
    }

    // ------------------------------------------------------------------
    // Phase A (part 1), cheap gate check, no decode
    // ------------------------------------------------------------------

    GateResult evaluateGate(PDImageXObject original, float[] usagePoints) throws IOException {
        long originalLength = encodedLength(original);
        if (shouldSkip(original, originalLength)) {
            return new GateResult.Decided(Outcome.skip());
        }

        int origW = original.getWidth();
        int origH = original.getHeight();
        boolean bitonal = original.getBitsPerComponent() == 1;

        double scale = computeScale(origW, origH, usagePoints);
        double threshold = bitonal ? BITONAL_DOWNSAMPLE_SCALE : DOWNSAMPLE_TOLERANCE_SCALE;
        boolean wantsDownsample = scale < threshold;

        int targetW = wantsDownsample ? Math.max(1, (int) Math.round(origW * scale)) : origW;
        int targetH = wantsDownsample ? Math.max(1, (int) Math.round(origH * scale)) : origH;

        if (bitonal && !wantsDownsample) {
            // Bitonal scans must stay pixel-exact unless meaningfully oversampled.
            return new GateResult.Decided(Outcome.unchanged());
        }

        return new GateResult.Pending(
                new GatePassed(original, originalLength, bitonal, targetW, targetH, wantsDownsample));
    }

    // ------------------------------------------------------------------
    // Phase A (part 2), decode + classify (document thread, one image/batch at a time)
    // ------------------------------------------------------------------

    Planned finishPlan(GatePassed gate) throws IOException {
        PDImageXObject original = gate.original();
        BufferedImage decoded = original.getImage();

        Codec codec;
        if (gate.bitonal()) {
            codec = Codec.BITONAL;
        } else if (isTransparent(original, decoded)) {
            codec = Codec.TRANSPARENT;
        } else if (isGrayscale(decoded)) {
            codec = Codec.GRAYSCALE;
        } else {
            codec = Codec.COLOR;
        }

        return new Planned(original, gate.originalLength(), codec, decoded, gate.targetW(), gate.targetH(),
                gate.downsample());
    }

    // ------------------------------------------------------------------
    // Phase B, pure CPU resize/encode, no PDDocument access
    // ------------------------------------------------------------------

    Transformed transform(Planned p) throws IOException {
        return switch (p.codec()) {
            case BITONAL -> {
                BufferedImage grayScaled = resize(p.decoded(), p.targetW(), p.targetH(), BufferedImage.TYPE_BYTE_GRAY);
                BufferedImage rethresholded = threshold(grayScaled);
                yield Transformed.image(Codec.BITONAL, rethresholded, p.downsample());
            }
            case TRANSPARENT -> {
                // PDImage#getImage() composites the soft mask into the alpha channel for us.
                BufferedImage argb = p.downsample()
                        ? resize(p.decoded(), p.targetW(), p.targetH(), BufferedImage.TYPE_INT_ARGB)
                        : toType(p.decoded(), BufferedImage.TYPE_INT_ARGB);
                yield Transformed.image(Codec.TRANSPARENT, argb, p.downsample());
            }
            case GRAYSCALE -> {
                BufferedImage gray = p.downsample()
                        ? resize(p.decoded(), p.targetW(), p.targetH(), BufferedImage.TYPE_BYTE_GRAY)
                        : toType(p.decoded(), BufferedImage.TYPE_BYTE_GRAY);
                if (ENCODE_GRAYSCALE_OFF_DOCUMENT) {
                    yield Transformed.jpeg(Codec.GRAYSCALE, encodeJpegBytes(gray, properties.getJpegQuality()),
                            p.downsample());
                }
                yield Transformed.image(Codec.GRAYSCALE, gray, p.downsample());
            }
            case COLOR -> {
                BufferedImage rgb = p.downsample()
                        ? resize(p.decoded(), p.targetW(), p.targetH(), BufferedImage.TYPE_INT_RGB)
                        : toType(p.decoded(), BufferedImage.TYPE_INT_RGB);
                if (isPhotographic(rgb)) {
                    yield Transformed.jpeg(Codec.COLOR, encodeJpegBytes(rgb, properties.getJpegQuality()),
                            p.downsample());
                }
                // Indexed / low-colour / line-art content: avoid JPEG ringing artefacts.
                yield Transformed.image(Codec.COLOR, rgb, p.downsample());
            }
        };
    }

    // ------------------------------------------------------------------
    // Phase C, attach to the document (document thread, original discovery order)
    // ------------------------------------------------------------------

    Outcome attach(PDDocument doc, Planned planned, Transformed transformed) throws IOException {
        PDImageXObject candidate = switch (transformed.codec()) {
            case BITONAL -> CCITTFactory.createFromImage(doc, transformed.bufferedImage());
            case TRANSPARENT -> LosslessFactory.createFromImage(doc, transformed.bufferedImage());
            case GRAYSCALE -> transformed.jpegBytes() != null
                    ? JPEGFactory.createFromStream(doc, new ByteArrayInputStream(transformed.jpegBytes()))
                    : JPEGFactory.createFromImage(doc, transformed.bufferedImage(), properties.getJpegQuality());
            case COLOR -> transformed.jpegBytes() != null
                    ? JPEGFactory.createFromStream(doc, new ByteArrayInputStream(transformed.jpegBytes()))
                    : LosslessFactory.createFromImage(doc, transformed.bufferedImage());
        };

        return applySizeGuard(candidate, planned.originalLength(), transformed.downsampled());
    }

    private Outcome applySizeGuard(PDImageXObject candidate, long originalLength, boolean downsampled)
            throws IOException {
        long candidateLength = encodedLength(candidate);
        boolean meetsRatio = candidateLength <= originalLength * (1.0 - properties.getMinReductionRatio());
        boolean accept;
        if (meetsRatio) {
            accept = true;
        } else if (properties.getLargerResultPolicy() == LargerResultPolicy.USE_SMALLEST) {
            accept = candidateLength < originalLength;
        } else {
            accept = false;
        }
        return accept ? Outcome.accepted(candidate, downsampled) : Outcome.unchanged();
    }

    // ------------------------------------------------------------------
    // Skip gates
    // ------------------------------------------------------------------

    private boolean shouldSkip(PDImageXObject image, long encodedLength) throws IOException {
        if (image.isStencil()) {
            return true;
        }
        if (image.getWidth() < properties.getMinDimension() || image.getHeight() < properties.getMinDimension()) {
            return true;
        }
        if (encodedLength < properties.getMinByteSize()) {
            return true;
        }
        PDColorSpace colorSpace = image.getColorSpace();
        boolean cmyk = colorSpace.getNumberOfComponents() == 4 || colorSpace instanceof PDDeviceCMYK;
        return cmyk && !properties.isRecompressCmyk();
    }

    // ------------------------------------------------------------------
    // Target size computation
    // ------------------------------------------------------------------

    /**
     * Single uniform scale factor derived from the image's effective rendered
     * DPI (the more demanding of the two axes), clamped by {@code
     * maxImageDimension} and never allowed to exceed 1.0 (no enlarging).
     * When {@code usagePoints} is {@code null} (the image was never observed
     * being drawn, e.g. annotation-only), only the {@code maxImageDimension}
     * cap applies.
     */
    private double computeScale(int origW, int origH, float[] usagePoints) {
        double scale = 1.0;
        if (usagePoints != null && usagePoints[0] > 0f && usagePoints[1] > 0f) {
            double targetDpi = properties.getTargetDpi();
            double scaleX = (usagePoints[0] / 72.0 * targetDpi) / origW;
            double scaleY = (usagePoints[1] / 72.0 * targetDpi) / origH;
            scale = Math.min(scaleX, scaleY);
        }
        double maxDimScale = Math.min(
                (double) properties.getMaxImageDimension() / origW,
                (double) properties.getMaxImageDimension() / origH);
        scale = Math.min(scale, maxDimScale);
        return Math.min(scale, 1.0);
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    private boolean isTransparent(PDImageXObject image, BufferedImage decoded) throws IOException {
        if (image.getSoftMask() != null) {
            return true;
        }
        return decoded.getColorModel().hasAlpha();
    }

    private boolean isGrayscale(BufferedImage decoded) {
        // Covers DeviceGray, CalGray and ICCBased single-component gray, PDFBox decodes
        // all of them to a TYPE_GRAY raster. Indexed-colour images decode to RGB and are
        // therefore correctly excluded, keeping their colour instead of being flattened to
        // a grayscale JPEG.
        return decoded.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY;
    }

    /** Heuristic: many distinct sampled colours -> photographic content, better suited to JPEG. */
    private boolean isPhotographic(BufferedImage rgb) {
        int w = rgb.getWidth();
        int h = rgb.getHeight();
        long totalPixels = (long) w * h;
        int step = (int) Math.max(1, Math.sqrt((double) totalPixels / MAX_DISTINCT_COLOR_SAMPLES));
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                colors.add(rgb.getRGB(x, y) & 0xFFFFFF);
                if (colors.size() > PHOTOGRAPHIC_COLOR_THRESHOLD) {
                    return true;
                }
            }
        }
        return colors.size() > PHOTOGRAPHIC_COLOR_THRESHOLD;
    }

    // ------------------------------------------------------------------
    // Off-document JPEG encoding (Phase B only, never touches a PDDocument)
    // ------------------------------------------------------------------

    /**
     * Encodes {@code image} to JPEG bytes via a freshly-obtained {@link
     * ImageWriter} instance (thread-safe to call concurrently from multiple
     * pool threads, {@link ImageIO#getImageWritersByFormatName} hands back a
     * new writer per call, never a shared one) so Phase B can run this on the
     * shared executor without touching the {@link PDDocument} at all; the
     * bytes are attached via {@link JPEGFactory#createFromStream} back on the
     * document thread in Phase C.
     */
    private static byte[] encodeJpegBytes(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Resizing helpers
    // ------------------------------------------------------------------

    private BufferedImage toType(BufferedImage src, int imageType) {
        if (src.getType() == imageType) {
            return src;
        }
        BufferedImage converted = new BufferedImage(src.getWidth(), src.getHeight(), imageType);
        Graphics2D g = converted.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return converted;
    }

    /** High-quality downsample; uses progressive halving for large ratios to keep bicubic resampling stable. */
    private BufferedImage resize(BufferedImage src, int targetW, int targetH, int imageType) {
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return toType(src, imageType);
        }
        BufferedImage current = src;
        int curW = src.getWidth();
        int curH = src.getHeight();
        while (curW > targetW * 2 && curH > targetH * 2) {
            int nextW = Math.max(targetW, curW / 2);
            int nextH = Math.max(targetH, curH / 2);
            current = scaleStep(current, nextW, nextH, imageType);
            curW = nextW;
            curH = nextH;
        }
        return scaleStep(current, targetW, targetH, imageType);
    }

    private BufferedImage scaleStep(BufferedImage src, int w, int h, int imageType) {
        BufferedImage out = new BufferedImage(w, h, imageType);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private BufferedImage threshold(BufferedImage gray) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = gray.getRGB(x, y) & 0xFF;
                bin.setRGB(x, y, v < 128 ? 0x000000 : 0xFFFFFF);
            }
        }
        return bin;
    }

    // ------------------------------------------------------------------
    // Byte-size accounting
    // ------------------------------------------------------------------

    private static long encodedLength(PDImageXObject image) {
        return ((COSStream) image.getCOSObject()).getLength();
    }
}
