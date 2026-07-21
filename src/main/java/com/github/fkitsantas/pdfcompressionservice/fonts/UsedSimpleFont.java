package com.github.fkitsantas.pdfcompressionservice.fonts;

import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;

/**
 * Accumulates the character codes actually drawn with one embedded simple
 * TrueType font. Unlike a composite font (which is subset by glyph id and needs
 * a rewritten {@code /CIDToGIDMap}), a simple font is subset by <b>unicode code
 * point</b> so the font's {@code cmap} - which the renderer uses to resolve
 * code to glyph - is preserved; hence only the used codes are collected here.
 */
final class UsedSimpleFont {

    final PDTrueTypeFont font;
    final Set<Integer> usedCodes = new TreeSet<>();

    UsedSimpleFont(PDTrueTypeFont font) {
        this.font = font;
    }
}
