package com.github.fkitsantas.pdfcompressionservice.fonts;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Accumulates, for one embedded CIDFontType2 (TrueType) font, the glyphs that are
 * actually drawn anywhere in the document: the set of used glyph ids (to keep in
 * the subset) and the CID-to-GID mapping for those glyphs (to rebuild the font's
 * {@code /CIDToGIDMap} after the subset renumbers glyphs).
 */
final class UsedFont {

    final PDType0Font type0;
    final PDCIDFontType2 cidFont;
    final Set<Integer> usedGids = new TreeSet<>();
    final Map<Integer, Integer> cidToGid = new HashMap<>();

    UsedFont(PDType0Font type0, PDCIDFontType2 cidFont) {
        this.type0 = type0;
        this.cidFont = cidFont;
    }

    void record(int cid, int gid) {
        usedGids.add(gid);
        cidToGid.put(cid, gid);
    }
}
