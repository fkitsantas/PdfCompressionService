package com.github.fkitsantas.pdfcompressionservice.analysis;

/**
 * A byte-level breakdown of what a PDF is actually made of, used to decide where
 * compression effort pays off. Every stored stream is bucketed into one of four
 * categories by its encoded (on-disk) byte size:
 *
 * <ul>
 *   <li>{@code images} - image XObjects (what this service already optimizes);</li>
 *   <li>{@code fonts} - embedded font programs (FontFile/FontFile2/FontFile3);</li>
 *   <li>{@code vectors} - page content streams, form XObjects and tiling
 *       patterns, i.e. the vector/text drawing instructions;</li>
 *   <li>{@code other} - everything else (XMP metadata, ICC profiles, embedded
 *       files, thumbnails, structure trees, ...).</li>
 * </ul>
 *
 * <p>{@code addressableBytes} = {@code fonts} + {@code vectors}: the portion a
 * hypothetical vector-level optimization pass (font subsetting, content-stream
 * rewriting) could target, as opposed to the image pass. A high image share
 * means image optimization is where the leverage is; a high addressable share
 * means a vector pass could be worth its complexity and risk on that document.
 */
public record DocumentComposition(long fileSizeBytes, int pageCount, long streamBytesTotal,
                                  Category images, Category fonts, Category vectors, Category other,
                                  long addressableBytes, double addressablePercent, String note) {

    /** One byte bucket: total encoded bytes, its share of all stream bytes, and how many streams. */
    public record Category(long bytes, double percent, int count) {
    }

    static DocumentComposition of(long fileSizeBytes, int pageCount,
                                  long imageBytes, int imageCount,
                                  long fontBytes, int fontCount,
                                  long vectorBytes, int vectorCount,
                                  long otherBytes, int otherCount) {
        long total = imageBytes + fontBytes + vectorBytes + otherBytes;
        long addressable = fontBytes + vectorBytes;
        String note = buildNote(total, imageBytes, addressable);
        return new DocumentComposition(fileSizeBytes, pageCount, total,
                new Category(imageBytes, percent(imageBytes, total), imageCount),
                new Category(fontBytes, percent(fontBytes, total), fontCount),
                new Category(vectorBytes, percent(vectorBytes, total), vectorCount),
                new Category(otherBytes, percent(otherBytes, total), otherCount),
                addressable, percent(addressable, total), note);
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 10000.0 / total) / 100.0;
    }

    private static String buildNote(long total, long imageBytes, long addressable) {
        if (total == 0) {
            return "No stored streams found.";
        }
        long imgPct = Math.round(imageBytes * 100.0 / total);
        long addrPct = Math.round(addressable * 100.0 / total);
        if (imgPct >= 80) {
            return "Images are " + imgPct + "% of stream bytes; image optimization is where the savings are, "
                    + "vector/font optimization would move only ~" + addrPct + "%.";
        }
        if (addrPct >= 40) {
            return "Fonts and vector/text content are " + addrPct + "% of stream bytes; a vector-level pass "
                    + "(font subsetting, content-stream rewriting) could be worthwhile on this document.";
        }
        return "Images " + imgPct + "%, fonts+vectors " + addrPct + "% of stream bytes.";
    }
}
