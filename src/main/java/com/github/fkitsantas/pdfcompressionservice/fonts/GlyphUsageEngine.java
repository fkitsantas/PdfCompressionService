package com.github.fkitsantas.pdfcompressionservice.fonts;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Walks a page's content exactly as the renderer would (including form XObjects,
 * Type3 glyphs and annotation appearances) and records, for every embedded
 * CIDFontType2 TrueType font, which glyphs are actually drawn. This is the
 * complete set of glyphs a subset must keep; anything not seen here is never
 * rendered and is safe to drop.
 *
 * <p>All the graphics callbacks are no-ops, we only care about {@link #showGlyph}.
 * {@code showGlyph} still delegates to {@code super} so Type3 char procs (which
 * may themselves draw with other fonts) are recursed into.
 */
final class GlyphUsageEngine extends PDFGraphicsStreamEngine {

    private final Map<COSDictionary, UsedFont> composite;
    private final Map<COSDictionary, UsedSimpleFont> simple;

    GlyphUsageEngine(PDPage page, Map<COSDictionary, UsedFont> composite,
                     Map<COSDictionary, UsedSimpleFont> simple) {
        super(page);
        this.composite = composite;
        this.simple = simple;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        record(font, code);
        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    private void record(PDFont font, int code) throws IOException {
        if (font instanceof PDType0Font type0
                && type0.getDescendantFont() instanceof PDCIDFontType2 cidFont) {
            PDFontDescriptor descriptor = cidFont.getFontDescriptor();
            if (descriptor == null || descriptor.getFontFile2() == null) {
                return; // not an embedded TrueType program
            }
            UsedFont usedFont = composite.computeIfAbsent(cidFont.getCOSObject(), k -> new UsedFont(type0, cidFont));
            usedFont.record(type0.codeToCID(code), type0.codeToGID(code));
            return;
        }
        if (font instanceof PDTrueTypeFont trueType && trueType.isEmbedded() && !trueType.isSymbolic()) {
            PDFontDescriptor descriptor = trueType.getFontDescriptor();
            if (descriptor == null || descriptor.getFontFile2() == null) {
                return; // not an embedded TrueType program
            }
            simple.computeIfAbsent(trueType.getCOSObject(), k -> new UsedSimpleFont(trueType)).usedCodes.add(code);
        }
    }

    // ------------------------------------------------------------------
    // Graphics callbacks: intentionally inert, glyph usage is all we collect.
    // ------------------------------------------------------------------

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    }

    @Override
    public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) {
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
    }

    @Override
    public void lineTo(float x, float y) {
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    }

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float(0, 0);
    }

    @Override
    public void closePath() {
    }

    @Override
    public void endPath() {
    }

    @Override
    public void strokePath() {
    }

    @Override
    public void fillPath(int windingRule) {
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }
}
