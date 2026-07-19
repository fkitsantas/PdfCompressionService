package com.github.fkitsantas.pdfcompressionservice.compression;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * Walks a page's content stream - including any nested Form XObjects, which
 * {@link org.apache.pdfbox.contentstream.PDFStreamEngine} recurses into
 * automatically whenever it encounters a {@code Do} operator invoking a
 * form - to record, for every distinct image XObject actually drawn, the
 * maximum size (in PDF points) it is ever rendered at across the page.
 *
 * <p>This is read-only analysis: no content stream is modified. The
 * geometry-related abstract methods of {@link PDFGraphicsStreamEngine} are
 * implemented as no-ops (mirroring PDFBox's own {@code PrintImageLocations}
 * example) since only image placement matters here.
 */
final class ImageUsageAnalyzer extends PDFGraphicsStreamEngine {

    private final Map<COSBase, float[]> maxRenderedPoints;

    ImageUsageAnalyzer(PDPage page, Map<COSBase, float[]> maxRenderedPoints) {
        super(page);
        this.maxRenderedPoints = maxRenderedPoints;
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        if (!(pdImage instanceof PDImageXObject image)) {
            // Inline images (BI/ID/EI) are not XObjects and are not optimized.
            return;
        }
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        float wPt = (float) Math.hypot(ctm.getScaleX(), ctm.getShearY());
        float hPt = (float) Math.hypot(ctm.getShearX(), ctm.getScaleY());

        COSBase key = image.getCOSObject();
        float[] existing = maxRenderedPoints.get(key);
        if (existing == null) {
            maxRenderedPoints.put(key, new float[]{wPt, hPt});
        } else {
            existing[0] = Math.max(existing[0], wPt);
            existing[1] = Math.max(existing[1], hPt);
        }
    }

    // ------------------------------------------------------------------
    // Geometry no-ops: this engine only tracks image placements, it never
    // renders or paints anything.
    // ------------------------------------------------------------------

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        // no-op
    }

    @Override
    public void clip(int windingRule) {
        // no-op
    }

    @Override
    public void moveTo(float x, float y) {
        // no-op
    }

    @Override
    public void lineTo(float x, float y) {
        // no-op
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        // no-op
    }

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float(0, 0);
    }

    @Override
    public void closePath() {
        // no-op
    }

    @Override
    public void endPath() {
        // no-op
    }

    @Override
    public void strokePath() {
        // no-op
    }

    @Override
    public void fillPath(int windingRule) {
        // no-op
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        // no-op
    }

    @Override
    public void shadingFill(COSName shadingName) {
        // no-op
    }
}
