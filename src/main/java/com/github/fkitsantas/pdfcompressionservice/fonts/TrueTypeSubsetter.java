package com.github.fkitsantas.pdfcompressionservice.fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Losslessly shrinks a PDF's embedded fonts by re-subsetting them to just the
 * glyphs the document actually draws, in pure Java via FontBox's
 * {@link TTFSubsetter}, no external tools. It is deliberately conservative:
 * every step that is not provably safe leaves the font untouched, so it can only
 * make a document smaller, never change how it looks.
 *
 * <p>Scope and safety rules:
 * <ul>
 *   <li>Embedded TrueType fonts are handled, both <b>composite (CIDFontType2)</b> - subset by
 *       glyph id, with a rewritten {@code /CIDToGIDMap} - and <b>simple, non-symbolic</b> ones,
 *       subset by unicode code point so their {@code cmap} still resolves. CFF/Type1 and Type3
 *       are skipped (no FontBox subsetter / not glyf-based).</li>
 *   <li>The glyph scan must succeed for the <b>whole</b> document (pages, form
 *       XObjects, Type3, annotation appearances); if any part fails to parse, the
 *       document is left entirely unchanged rather than risk an incomplete scan.</li>
 *   <li>A font is skipped if it is already subset (a {@code ABCDEF+} tag), if its
 *       program is shared by more than one font dictionary, if any used glyph is
 *       missing from the produced subset, or if the subset is not smaller.</li>
 *   <li>Widths ({@code /W}) and text mapping ({@code /ToUnicode}) are keyed by CID,
 *       which is preserved, so text extraction and spacing are unaffected; only the
 *       glyph program and the {@code /CIDToGIDMap} (renumbered) are rewritten.</li>
 * </ul>
 */
public final class TrueTypeSubsetter {

    private static final Logger log = LoggerFactory.getLogger(TrueTypeSubsetter.class);

    /** Outcome of a subsetting pass. */
    public record Outcome(int fontsSubset, long approxBytesSaved) {
        static final Outcome NONE = new Outcome(0, 0);
    }

    /**
     * Subsets every eligible embedded TrueType font in {@code doc} in place.
     * Returns how many fonts were subset and an estimate of the raw font bytes
     * saved (the real file saving is realized when the document is written).
     */
    public Outcome subsetFonts(PDDocument doc, String requestId) {
        Map<COSDictionary, UsedFont> composite = new IdentityHashMap<>();
        Map<COSDictionary, UsedSimpleFont> simple = new IdentityHashMap<>();
        try {
            collectUsage(doc, composite, simple);
        } catch (Exception e) {
            // Could not fully walk the document, do nothing rather than risk dropping a used glyph.
            log.debug("requestId={} action=subset-skipped reason=incomplete-scan detail={}",
                    requestId, e.getClass().getSimpleName());
            return Outcome.NONE;
        }
        if (composite.isEmpty() && simple.isEmpty()) {
            return Outcome.NONE;
        }

        Map<COSBase, Integer> fontFileReferences = countFontFile2References(doc);
        int fontsSubset = 0;
        long saved = 0;
        int index = 0;
        for (UsedFont usedFont : composite.values()) {
            try {
                long delta = subsetOne(doc, usedFont, fontFileReferences, tag(index++));
                if (delta > 0) {
                    fontsSubset++;
                    saved += delta;
                }
            } catch (Exception e) {
                log.debug("requestId={} action=subset-font-skipped scope=composite reason={} detail={}",
                        requestId, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        for (UsedSimpleFont usedFont : simple.values()) {
            try {
                long delta = subsetSimpleOne(doc, usedFont, fontFileReferences, tag(index++));
                if (delta > 0) {
                    fontsSubset++;
                    saved += delta;
                }
            } catch (Exception e) {
                log.debug("requestId={} action=subset-font-skipped scope=simple reason={} detail={}",
                        requestId, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        if (fontsSubset > 0) {
            log.info("requestId={} action=fonts-subset count={} approxBytesSaved={}", requestId, fontsSubset, saved);
        }
        return new Outcome(fontsSubset, saved);
    }

    private void collectUsage(PDDocument doc, Map<COSDictionary, UsedFont> composite,
                              Map<COSDictionary, UsedSimpleFont> simple) throws IOException {
        for (PDPage page : doc.getPages()) {
            GlyphUsageEngine engine = new GlyphUsageEngine(page, composite, simple);
            engine.processPage(page);
            for (PDAnnotation annotation : page.getAnnotations()) {
                engine.showAnnotation(annotation);
            }
        }
    }

    /** Counts, across the whole document, how many font descriptors reference each FontFile2 stream. */
    private Map<COSBase, Integer> countFontFile2References(PDDocument doc) {
        Map<COSBase, Integer> counts = new IdentityHashMap<>();
        for (COSObjectKey key : doc.getDocument().getXrefTable().keySet()) {
            COSBase base;
            try {
                base = doc.getDocument().getObjectFromPool(key).getObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (base instanceof COSDictionary dict
                    && dict.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream stream) {
                counts.merge(stream, 1, Integer::sum);
            }
        }
        return counts;
    }

    private long subsetOne(PDDocument doc, UsedFont usedFont, Map<COSBase, Integer> fontFileReferences, String tag)
            throws IOException {
        PDCIDFontType2 cidFont = usedFont.cidFont;
        PDFontDescriptor descriptor = cidFont.getFontDescriptor();
        PDStream fontFile2 = descriptor.getFontFile2();
        COSStream fontFileCos = fontFile2.getCOSObject();

        if (fontFileReferences.getOrDefault(fontFileCos, 0) != 1) {
            return 0; // shared program: another font dict relies on it, do not touch
        }
        String baseName = usedFont.type0.getName();
        if (baseName == null) {
            return 0; // no name to tag the subset with
        }
        if (baseName.matches("[A-Z]{6}\\+.+")) {
            // Already carries a subset tag, but re-subset anyway (max compression): a producer's
            // subset can still include glyphs this document does not use. Drop the old tag so we
            // can re-tag; the "only commit if smaller" guard below skips it if there is no gain.
            baseName = baseName.substring(7);
        }
        Set<Integer> gids = new TreeSet<>(usedFont.usedGids);
        gids.add(0); // always keep .notdef
        if (gids.size() <= 1) {
            return 0; // no glyphs actually used
        }

        long originalProgramLength = fontFileCos.getInt(COSName.LENGTH1, -1);
        TrueTypeFont ttf = cidFont.getTrueTypeFont();

        TTFSubsetter subsetter = new TTFSubsetter(ttf);
        subsetter.setPrefix(tag);
        subsetter.addGlyphIds(gids);

        // Write first: this is what builds the subset and its glyph renumbering, which
        // getGIDMap() then reports (old glyph id -> new glyph id).
        ByteArrayOutputStream fontBytes = new ByteArrayOutputStream();
        subsetter.writeToStream(fontBytes);
        byte[] subset = fontBytes.toByteArray();
        // TTFSubsetter.getGIDMap() is keyed new->old; invert it to old->new for the CIDToGIDMap.
        // A value-equality HashMap (not IdentityHashMap) is required: glyph ids box to distinct
        // Integer objects above 127, so identity lookups would silently miss.
        Map<Integer, Integer> oldToNew = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : subsetter.getGIDMap().entrySet()) {
            oldToNew.put(entry.getValue(), entry.getKey());
        }

        byte[] newCidToGid = buildCidToGidMap(usedFont, oldToNew);
        if (newCidToGid == null) {
            return 0; // a used glyph did not survive the subset, abort (safety)
        }

        long compareAgainst = originalProgramLength > 0 ? originalProgramLength : fontFileCos.getLength();
        if (subset.length >= compareAgainst) {
            return 0; // not actually smaller
        }

        // ---- commit: rewrite the font program, the CIDToGIDMap, and the subset-tagged names ----
        try (OutputStream out = fontFileCos.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(subset);
        }
        fontFileCos.setInt(COSName.LENGTH1, subset.length);

        COSStream cidToGidStream = doc.getDocument().createCOSStream();
        try (OutputStream out = cidToGidStream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(newCidToGid);
        }
        cidFont.getCOSObject().setItem(COSName.CID_TO_GID_MAP, cidToGidStream);

        String tagged = tag + "+" + baseName;
        usedFont.type0.getCOSObject().setName(COSName.BASE_FONT, tagged);
        cidFont.getCOSObject().setName(COSName.BASE_FONT, tagged);
        descriptor.getCOSObject().setName(COSName.FONT_NAME, tagged);

        return compareAgainst - subset.length;
    }

    /**
     * Subsets one embedded <b>simple</b> TrueType font by <b>unicode code point</b> (not glyph id),
     * which keeps the font's {@code cmap} intact so the renderer still resolves each code to the
     * same glyph. Simple fonts have no {@code /CIDToGIDMap}; the code-keyed {@code /Widths},
     * {@code /Encoding} etc. are untouched.
     *
     * <p>Safety: skipped unless the font's own unicode {@code cmap} resolves every used code's
     * unicode back to exactly the glyph the renderer draws for that code
     * ({@link PDTrueTypeFont#codeToGID}). If they disagree (custom {@code /Differences}, symbolic
     * mapping, ...) subsetting by unicode could keep the wrong glyph, so the font is left as-is.
     */
    private long subsetSimpleOne(PDDocument doc, UsedSimpleFont usedFont,
                                 Map<COSBase, Integer> fontFileReferences, String tag) throws IOException {
        PDTrueTypeFont font = usedFont.font;
        PDFontDescriptor descriptor = font.getFontDescriptor();
        COSStream fontFileCos = descriptor.getFontFile2().getCOSObject();
        if (fontFileReferences.getOrDefault(fontFileCos, 0) != 1) {
            return 0; // shared program, do not touch
        }
        String baseName = font.getName();
        if (baseName == null) {
            return 0;
        }
        if (baseName.matches("[A-Z]{6}\\+.+")) {
            baseName = baseName.substring(7); // re-subset even an already-tagged font if it shrinks
        }

        TrueTypeFont ttf = font.getTrueTypeFont();
        CmapLookup unicodeCmap = ttf.getUnicodeCmapLookup();
        if (unicodeCmap == null) {
            return 0;
        }
        Set<Integer> codePoints = new TreeSet<>();
        for (int code : usedFont.usedCodes) {
            String unicode = font.toUnicode(code);
            if (unicode == null || unicode.isEmpty()) {
                return 0; // no reliable unicode for a used code, cannot subset by unicode safely
            }
            int codePoint = unicode.codePointAt(0);
            if (unicodeCmap.getGlyphId(codePoint) != font.codeToGID(code)) {
                return 0; // unicode path and render path disagree, abort (safety)
            }
            codePoints.add(codePoint);
        }
        if (codePoints.isEmpty()) {
            return 0;
        }

        long compareAgainst = fontFileCos.getInt(COSName.LENGTH1, -1);
        if (compareAgainst <= 0) {
            compareAgainst = fontFileCos.getLength();
        }

        TTFSubsetter subsetter = new TTFSubsetter(ttf);
        subsetter.setPrefix(tag);
        for (int codePoint : codePoints) {
            subsetter.add(codePoint);
        }
        ByteArrayOutputStream fontBytes = new ByteArrayOutputStream();
        subsetter.writeToStream(fontBytes);
        byte[] subset = fontBytes.toByteArray();
        if (subset.length >= compareAgainst) {
            return 0; // not smaller
        }

        try (OutputStream out = fontFileCos.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(subset);
        }
        fontFileCos.setInt(COSName.LENGTH1, subset.length);
        String tagged = tag + "+" + baseName;
        font.getCOSObject().setName(COSName.BASE_FONT, tagged);
        descriptor.getCOSObject().setName(COSName.FONT_NAME, tagged);
        return compareAgainst - subset.length;
    }

    /**
     * Builds the new {@code /CIDToGIDMap} (two big-endian bytes per CID) mapping each used CID
     * to its <b>renumbered</b> glyph id in the subset. Returns {@code null} if any used glyph is
     * absent from the subset, which must abort the font rather than map it to {@code .notdef}.
     */
    private byte[] buildCidToGidMap(UsedFont usedFont, Map<Integer, Integer> gidMap) {
        int maxCid = 0;
        for (int cid : usedFont.cidToGid.keySet()) {
            maxCid = Math.max(maxCid, cid);
        }
        byte[] map = new byte[(maxCid + 1) * 2];
        for (Map.Entry<Integer, Integer> entry : usedFont.cidToGid.entrySet()) {
            Integer newGid = gidMap.get(entry.getValue());
            if (newGid == null) {
                return null;
            }
            int cid = entry.getKey();
            map[cid * 2] = (byte) (newGid >>> 8);
            map[cid * 2 + 1] = (byte) (newGid & 0xFF);
        }
        return map;
    }

    /** A deterministic 6-uppercase-letter subset tag for the n-th font subset in a document. */
    private static String tag(int n) {
        char[] letters = new char[6];
        for (int i = 5; i >= 0; i--) {
            letters[i] = (char) ('A' + (n % 26));
            n /= 26;
        }
        return new String(letters);
    }
}
