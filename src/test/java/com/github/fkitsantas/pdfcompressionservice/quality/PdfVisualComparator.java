package com.github.fkitsantas.pdfcompressionservice.quality;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Pure-Java (no external imaging libs) test utility that renders two PDFs to
 * raster images at a shared DPI and compares them, so tests can assert
 * "visually indistinguishable" or "materially different" without pinning
 * down exact byte content. Used to validate that {@code PdfCompressionEngine}
 * preserves visual fidelity (text sharpness, transparency handling, bitonal
 * exactness, etc.) even though the underlying bytes necessarily change.
 */
public final class PdfVisualComparator {

    private PdfVisualComparator() {
    }

    /** Global SSIM (structural similarity, windowed 8x8, on luma) between page 0 of both PDFs at the given DPI. */
    public static double ssim(byte[] a, byte[] b, int dpi) throws IOException {
        return ssim(a, b, dpi, 0);
    }

    public static double ssim(byte[] a, byte[] b, int dpi, int pageIndex) throws IOException {
        BufferedImage imgA = render(a, dpi, pageIndex);
        BufferedImage imgB = render(b, dpi, pageIndex);
        return ssim(imgA, imgB);
    }

    /** SSIM restricted to a sub-rectangle (e.g. a text region), in pixel coordinates of the rendered image. */
    public static double regionSsim(byte[] a, byte[] b, int dpi, int pageIndex, Rectangle region) throws IOException {
        BufferedImage imgA = crop(render(a, dpi, pageIndex), region);
        BufferedImage imgB = crop(render(b, dpi, pageIndex), region);
        return ssim(imgA, imgB);
    }

    /** Percentage (0..100) of pixels whose per-channel max absolute difference exceeds a small tolerance. */
    public static double maxPixelDiffPercent(byte[] a, byte[] b, int dpi) throws IOException {
        return maxPixelDiffPercent(a, b, dpi, 0);
    }

    public static double maxPixelDiffPercent(byte[] a, byte[] b, int dpi, int pageIndex) throws IOException {
        BufferedImage imgA = render(a, dpi, pageIndex);
        BufferedImage imgB = render(b, dpi, pageIndex);
        return maxPixelDiffPercent(imgA, imgB);
    }

    public static double regionMaxPixelDiffPercent(byte[] a, byte[] b, int dpi, int pageIndex, Rectangle region) throws IOException {
        BufferedImage imgA = crop(render(a, dpi, pageIndex), region);
        BufferedImage imgB = crop(render(b, dpi, pageIndex), region);
        return maxPixelDiffPercent(imgA, imgB);
    }

    /** Renders page {@code pageIndex} of {@code pdfBytes} at {@code dpi}, RGB. Exposed for ad-hoc pixel assertions. */
    public static BufferedImage render(byte[] pdfBytes, int dpi, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        }
    }

    // ------------------------------------------------------------------
    // SSIM implementation (Wang et al., 2004), grayscale/luma, 8x8 windows.
    // ------------------------------------------------------------------

    private static final int WINDOW = 8;
    private static final double L = 255.0;
    private static final double K1 = 0.01;
    private static final double K2 = 0.03;
    private static final double C1 = (K1 * L) * (K1 * L);
    private static final double C2 = (K2 * L) * (K2 * L);

    private static double ssim(BufferedImage a, BufferedImage b) {
        requireSameSize(a, b);
        double[][] lumaA = toLuma(a);
        double[][] lumaB = toLuma(b);
        int width = a.getWidth();
        int height = a.getHeight();

        double ssimSum = 0.0;
        int windows = 0;

        for (int y = 0; y + WINDOW <= height; y += WINDOW) {
            for (int x = 0; x + WINDOW <= width; x += WINDOW) {
                ssimSum += windowSsim(lumaA, lumaB, x, y);
                windows++;
            }
        }

        if (windows == 0) {
            // Image smaller than one window: treat the whole image as a single window.
            return windowSsim(lumaA, lumaB, 0, 0, width, height);
        }
        return ssimSum / windows;
    }

    private static double windowSsim(double[][] lumaA, double[][] lumaB, int startX, int startY) {
        return windowSsim(lumaA, lumaB, startX, startY, WINDOW, WINDOW);
    }

    private static double windowSsim(double[][] lumaA, double[][] lumaB, int startX, int startY, int w, int h) {
        double meanA = 0.0;
        double meanB = 0.0;
        int n = w * h;
        for (int y = startY; y < startY + h; y++) {
            for (int x = startX; x < startX + w; x++) {
                meanA += lumaA[y][x];
                meanB += lumaB[y][x];
            }
        }
        meanA /= n;
        meanB /= n;

        double varA = 0.0;
        double varB = 0.0;
        double covAB = 0.0;
        for (int y = startY; y < startY + h; y++) {
            for (int x = startX; x < startX + w; x++) {
                double da = lumaA[y][x] - meanA;
                double db = lumaB[y][x] - meanB;
                varA += da * da;
                varB += db * db;
                covAB += da * db;
            }
        }
        varA /= (n - 1 == 0 ? 1 : n - 1);
        varB /= (n - 1 == 0 ? 1 : n - 1);
        covAB /= (n - 1 == 0 ? 1 : n - 1);

        double numerator = (2 * meanA * meanB + C1) * (2 * covAB + C2);
        double denominator = (meanA * meanA + meanB * meanB + C1) * (varA + varB + C2);
        return numerator / denominator;
    }

    private static double[][] toLuma(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        double[][] luma = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                luma[y][x] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        return luma;
    }

    private static double maxPixelDiffPercent(BufferedImage a, BufferedImage b) {
        requireSameSize(a, b);
        int width = a.getWidth();
        int height = a.getHeight();
        long differing = 0;
        long total = (long) width * height;
        // Small tolerance absorbs harmless rounding differences from re-rasterization.
        int tolerance = 4;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int dr = Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                int dg = Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                int db = Math.abs((pa & 0xFF) - (pb & 0xFF));
                if (Math.max(dr, Math.max(dg, db)) > tolerance) {
                    differing++;
                }
            }
        }
        return total == 0 ? 0.0 : (100.0 * differing) / total;
    }

    private static BufferedImage crop(BufferedImage img, Rectangle region) {
        Rectangle bounds = new Rectangle(0, 0, img.getWidth(), img.getHeight());
        Rectangle clipped = bounds.intersection(region);
        if (clipped.isEmpty()) {
            throw new IllegalArgumentException("Region " + region + " does not overlap rendered image " + bounds);
        }
        return img.getSubimage(clipped.x, clipped.y, clipped.width, clipped.height);
    }

    private static void requireSameSize(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalStateException("Rendered pages differ in pixel size: "
                    + a.getWidth() + "x" + a.getHeight() + " vs " + b.getWidth() + "x" + b.getHeight()
                    + ", page geometry was not preserved, or comparator was given mismatched pages.");
        }
    }
}
