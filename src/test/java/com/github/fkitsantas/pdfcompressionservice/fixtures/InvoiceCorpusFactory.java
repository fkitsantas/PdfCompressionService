package com.github.fkitsantas.pdfcompressionservice.fixtures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.util.Matrix;

/**
 * Builds small, deterministic, in-memory PDF fixtures ("invoice corpus")
 * used across the compression test suite. Every fixture is produced with
 * real PDFBox 3 APIs so downstream tests exercise the same code paths the
 * production engine will run against, nothing here is mocked.
 *
 * <p>This is a test utility (no {@code @Test} methods); it is intentionally
 * placed outside the {@code compression}/{@code web} packages so it can be
 * shared by unit, integration and regression tests alike.
 */
public final class InvoiceCorpusFactory {

    /** Fixed seed so fixtures (and their expected byte-size ballpark) are reproducible across runs. */
    private static final long SEED = 42L;

    private InvoiceCorpusFactory() {
    }

    // ------------------------------------------------------------------
    // 1. Single extremely large photographic image on a small page
    // ------------------------------------------------------------------

    /**
     * One page (A4) carrying a single 4000x3000 synthetic photographic RGB
     * image, so the effective DPI (pixels / page-inches) is far above any
     * sane target DPI. Encoded at high JPEG quality so the fixture is
     * genuinely large (multi-MB) despite JPEG compression.
     */
    public static byte[] singleExtremelyLargePhotographicImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage photo = syntheticPhotographicImage(4000, 3000);
            PDImageXObject image = JPEGFactory.createFromImage(doc, photo, 0.95f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 1a. JPEG2000 (JPXDecode) image: exercises the JPX decode path
    // ------------------------------------------------------------------

    /**
     * One A4 page carrying a single genuinely JPEG2000-encoded (JPXDecode)
     * photographic image, drawn small so its effective DPI is well above the
     * default target. This is a <b>real</b> JPX stream (encoded via the
     * jai-imageio JPEG2000 writer), not a poison payload, so the engine can only
     * optimize it if the JPEG2000 ImageIO plugin is on the classpath, decoding
     * it and recompressing it to JPEG. Without that plugin the image would be
     * undecodable and pass through untouched.
     */
    public static byte[] jpeg2000Image() throws IOException {
        int width = 700;
        int height = 500;
        byte[] jp2 = encodeJpeg2000(syntheticPhotographicImage(width, height));
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject image = embedRawImageXObject(doc, jp2, COSName.getPDFName("JPXDecode"),
                    COSName.DEVICERGB, width, height, 8);
            // Small on-page size -> high effective DPI -> the engine downsamples and re-encodes.
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 40, 40, 160, 114);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 1b. Low effective-DPI image (already below target DPI): no-enlarge probe
    // ------------------------------------------------------------------

    /**
     * A modest 300x200 image stretched across nearly a full A4 page, giving
     * an effective DPI (~36) well *below* any sane target DPI (e.g. the
     * default 150). The engine must never upscale to "reach" the target
     * DPI, this fixture lets tests assert the no-enlarge rule: pixel
     * dimensions must come out unchanged (or smaller), never larger.
     */
    public static byte[] lowEffectiveDpiImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage photo = syntheticPhotographicImage(300, 200);
            PDImageXObject image = JPEGFactory.createFromImage(doc, photo, 0.9f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 2. Multiple large invoice images across several pages
    // ------------------------------------------------------------------

    public static byte[] multipleLargeInvoiceImages(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                BufferedImage photo = syntheticPhotographicImage(1600, 1200, SEED + i);
                PDImageXObject image = JPEGFactory.createFromImage(doc, photo, 0.9f);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            return save(doc);
        }
    }

    /**
     * A multi-page PDF that draws the <em>same</em> image on every page, but
     * embeds it as a <em>separate</em> image object per page (identical bytes,
     * distinct COS objects), i.e. the repeated-logo/letterhead case that the
     * engine's same-object sharing does not catch but content-dedup does.
     */
    public static byte[] sameImageRepeatedAcrossPagesAsSeparateObjects(int pageCount) throws IOException {
        BufferedImage logo = syntheticPhotographicImage(900, 700);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                PDImageXObject image = JPEGFactory.createFromImage(doc, logo, 0.9f);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            return save(doc);
        }
    }

    /**
     * A one-page PDF whose resources contain a real, optimizable image
     * alongside a sibling XObject with an invalid {@code /Subtype}, which makes
     * {@code PDResources.getXObject(name)} throw {@code IOException} during
     * image discovery for that one entry. Exercises graceful degradation: the
     * whole request must still succeed and optimise the valid sibling image,
     * isolating the unreadable XObject rather than failing the document with a
     * 500. (A malformed content stream was tried first but PDFBox's Flate
     * filter recovers from it leniently and never throws; an invalid XObject
     * subtype is a deterministic trigger.)
     */
    public static byte[] pdfWithUnreadableXObjectInResources() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            // A real large image drawn normally, registering a valid XObject in resources.
            BufferedImage photo = syntheticPhotographicImage(1600, 1200, SEED);
            PDImageXObject image = JPEGFactory.createFromImage(doc, photo, 0.9f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }

            // A sibling XObject with a bogus /Subtype -> getXObject(name) throws for it only.
            COSStream bad = doc.getDocument().createCOSStream();
            bad.setItem(COSName.TYPE, COSName.XOBJECT);
            bad.setItem(COSName.SUBTYPE, COSName.getPDFName("Bogus"));
            COSDictionary xobjects = (COSDictionary) page.getResources().getCOSObject()
                    .getDictionaryObject(COSName.XOBJECT);
            xobjects.setItem(COSName.getPDFName("ImBad"), bad);

            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 3. Mixed content page: photo + vector line art + real text + small logo
    // ------------------------------------------------------------------

    /** Marker text asserted by fidelity tests as surviving text extraction. */
    public static final String MIXED_PAGE_TEXT = "Invoice No. INV-2026-0042 Total Due: 1234.56";

    public static byte[] mixedContentPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            BufferedImage photo = syntheticPhotographicImage(600, 400);
            PDImageXObject photoImage = JPEGFactory.createFromImage(doc, photo, 0.85f);

            BufferedImage logo = syntheticPhotographicImage(64, 64, SEED + 99);
            PDImageXObject logoImage = LosslessFactory.createFromImage(doc, logo);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Photographic image.
                cs.drawImage(photoImage, 50, 400, 300, 200);

                // Small logo image.
                cs.drawImage(logoImage, 480, 750, 64, 64);

                // Vector/line-art drawing: NOT an image, pure content-stream operators.
                cs.setLineWidth(2f);
                cs.setNonStrokingColor(0.2f, 0.4f, 0.8f);
                cs.moveTo(50, 350);
                cs.lineTo(300, 350);
                cs.lineTo(175, 250);
                cs.lineTo(50, 350);
                cs.stroke();
                cs.addRect(50, 150, 250, 60);
                cs.stroke();

                // Real extractable text.
                PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 100);
                cs.showText(MIXED_PAGE_TEXT);
                cs.endText();
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 4. Transparent PNG (genuine alpha / soft mask)
    // ------------------------------------------------------------------

    public static byte[] transparentPngImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage argb = syntheticTransparentImage(500, 500);
            PDImageXObject image = LosslessFactory.createFromImage(doc, argb);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 300, 400, 400);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 5. Grayscale image
    // ------------------------------------------------------------------

    public static byte[] grayscaleImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage gray = syntheticGrayscaleImage(800, 600);
            PDImageXObject image = LosslessFactory.createFromImage(doc, gray);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 100, 500, 375);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 6. Bitonal / monochrome scan (1-bit) image
    // ------------------------------------------------------------------

    public static byte[] bitonalScanImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage bitonal = syntheticBitonalImage(1000, 1400);
            PDImageXObject image = CCITTFactory.createFromImage(doc, bitonal);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 50, 495, 693);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 7. Same image XObject reused across multiple pages (shared resource)
    // ------------------------------------------------------------------

    public static byte[] sharedImageAcrossPages(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage photo = syntheticPhotographicImage(900, 900);
            PDImageXObject sharedImage = JPEGFactory.createFromImage(doc, photo, 0.9f);
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    // Same PDImageXObject instance (same underlying COSStream) on every page.
                    cs.drawImage(sharedImage, 50, 50, 300, 300);
                }
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 8. Rotated / transformed image + a page with /Rotate
    // ------------------------------------------------------------------

    public static byte[] rotatedTransformedImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(90);
            doc.addPage(page);

            BufferedImage photo = syntheticPhotographicImage(500, 300);
            PDImageXObject image = JPEGFactory.createFromImage(doc, photo, 0.9f);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                // Non-identity CTM: translate + rotate + scale before painting the unit image square.
                Matrix translate = Matrix.getTranslateInstance(300, 400);
                Matrix rotate = Matrix.getRotateInstance(Math.toRadians(30), 0, 0);
                Matrix scale = Matrix.getScaleInstance(250, 150);
                Matrix combined = Matrix.concatenate(scale, Matrix.concatenate(rotate, translate));
                cs.transform(combined);
                cs.drawImage(image, 0, 0, 1, 1);
                cs.restoreGraphicsState();
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 9. Already-efficiently-compressed small JPEG (below optimization benefit)
    // ------------------------------------------------------------------

    public static byte[] alreadyEfficientSmallJpeg() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            // 320x240 at moderate quality clears both minDimension (16px) and
            // minByteSize (8192 bytes) comfortably, while sitting at ~72 effective
            // DPI on an A4 page, i.e. it is a legitimate optimization candidate on
            // size alone, but already efficiently encoded, so recompression should
            // yield negligible-to-no further benefit.
            byte[] rawJpeg = encodeRawJpeg(syntheticPhotographicImage(320, 240), 0.6f);
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, rawJpeg, "small-efficient.jpg");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 550, 320, 240);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 10. Tiny sub-threshold image (smaller than minDimension / minByteSize)
    // ------------------------------------------------------------------

    public static byte[] tinySubThresholdImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage tiny = syntheticPhotographicImage(10, 10);
            PDImageXObject image = LosslessFactory.createFromImage(doc, tiny);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 750, 10, 10);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 10b. Threshold-boundary isolation fixtures
    // ------------------------------------------------------------------

    /**
     * A single square, high-entropy (incompressible) image at the given pixel
     * dimension, embedded losslessly. Used to probe the {@code minDimension}
     * boundary in isolation: because pixel counts this small can never
     * legitimately clear {@code minByteSize} (8192 bytes) even at maximum
     * per-pixel entropy, the accompanying tests treat "image left unchanged"
     * as the expected outcome at and below the dimension boundary regardless
     * of which of the two gates is technically responsible.
     */
    public static byte[] squareNoiseImageAtDimension(int pixels) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage img = highEntropyNoiseImage(pixels, pixels);
            PDImageXObject image = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 700, pixels, pixels);
            }
            return save(doc);
        }
    }

    /**
     * A large-pixel-dimension (well above {@code minDimension}) but flat,
     * single-colour image, JPEG-encoded so it collapses to a byte size well
     * under the default {@code minByteSize} (8192 bytes). Isolates the
     * byte-size gate: dimension alone would qualify this image for
     * processing, but its encoded size should not.
     */
    public static byte[] largeDimensionBelowMinByteSize() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage flat = new BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = flat.createGraphics();
            g.setColor(new java.awt.Color(210, 60, 60));
            g.fillRect(0, 0, 500, 500);
            g.dispose();
            byte[] rawJpeg = encodeRawJpeg(flat, 0.8f);
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, rawJpeg, "flat.jpg");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 200, 500, 500);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 11. Already-optimal PDF: recompression at default settings would grow it
    // ------------------------------------------------------------------

    /**
     * A page whose only image is already JPEG-encoded at very low quality
     * (0.2). Re-encoding it at {@code PdfCompressionProperties} defaults
     * (quality 0.75) would almost certainly produce a *larger* byte stream,
     * so the engine's larger-result guard must kick in.
     */
    public static byte[] alreadyOptimalPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            byte[] rawJpeg = encodeRawJpeg(syntheticPhotographicImage(300, 200), 0.2f);
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, rawJpeg, "already-optimal.jpg");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 50, 550, 300, 200);
            }
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 12/13. Corrupt / truncated bytes (not loadable)
    // ------------------------------------------------------------------

    public static byte[] corruptBytes() {
        String garbage = "%PDF-1.4\nThis is not a real PDF body.\n%%totally bogus xref\ntrailer<<>>\n%%EOF";
        return garbage.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] truncatedPdfBytes() throws IOException {
        byte[] full = grayscaleImage();
        int cut = full.length / 3;
        byte[] truncated = new byte[cut];
        System.arraycopy(full, 0, truncated, 0, cut);
        return truncated;
    }

    // ------------------------------------------------------------------
    // Preservation helpers: bookmarks / outline
    // ------------------------------------------------------------------

    public static final String BOOKMARK_TITLE = "Invoice Summary";

    public static byte[] pdfWithBookmarks() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(BOOKMARK_TITLE);
            item.setDestination(page2);
            outline.addLast(item);
            outline.openNode();

            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // Preservation helpers: annotation with appearance stream
    // ------------------------------------------------------------------

    public static byte[] pdfWithAnnotationAppearance() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAnnotationSquare square = new PDAnnotationSquare();
            PDRectangle rect = new PDRectangle(100, 600, 150, 80);
            square.setRectangle(rect);
            square.setColor(new PDColor(new float[]{1f, 0f, 0f}, PDDeviceRGB.INSTANCE));

            PDAppearanceStream appearanceStream = new PDAppearanceStream(doc);
            appearanceStream.setBBox(new PDRectangle(150, 80));
            try (PDPageContentStream cs = new PDPageContentStream(doc, appearanceStream)) {
                cs.setNonStrokingColor(1f, 0.9f, 0.9f);
                cs.addRect(2, 2, 146, 76);
                cs.fill();
            }
            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.setNormalAppearance(appearanceStream);
            square.setAppearance(appearance);
            square.setPage(page);

            page.getAnnotations().add(square);
            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // Preservation helpers: simple AcroForm field
    // ------------------------------------------------------------------

    public static final String ACROFORM_FIELD_NAME = "invoiceNumber";
    public static final String ACROFORM_FIELD_VALUE = "INV-0001";

    public static byte[] pdfWithAcroForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            org.apache.pdfbox.pdmodel.PDResources defaultResources = new org.apache.pdfbox.pdmodel.PDResources();
            defaultResources.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            acroForm.setDefaultResources(defaultResources);
            acroForm.setDefaultAppearance("/Helv 10 Tf 0 g");

            PDTextField field = new PDTextField(acroForm);
            field.setPartialName(ACROFORM_FIELD_NAME);
            field.getCOSObject().setItem(COSName.SUBTYPE, COSName.WIDGET);
            field.getCOSObject().setItem(COSName.TYPE, COSName.ANNOT);

            PDAnnotationWidget widget = new PDAnnotationWidget(field.getCOSObject());
            widget.setRectangle(new PDRectangle(50, 700, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);

            // Rectangle/page must exist before setValue(), which regenerates the
            // widget's appearance stream and needs a BBox to render into.
            field.setValue(ACROFORM_FIELD_VALUE);

            List<org.apache.pdfbox.pdmodel.interactive.form.PDField> fields = new ArrayList<>();
            fields.add(field);
            acroForm.setFields(fields);

            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // Regression fixture: a page-level Form XObject with its own vector content
    // ------------------------------------------------------------------

    /**
     * A page whose content stream paints via {@code /Fm1 Do}, i.e. a
     * {@code PDFormXObject} resource (not an image). The historically
     * defective controller mangled such form XObjects by naively
     * run-length-"compressing" their content-stream bytes as if they were
     * UTF-8 text. This fixture lets regression tests assert the vector
     * shape the form draws survives intact.
     */
    public static byte[] pdfWithFormXObject() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form =
                    new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
            form.setBBox(new PDRectangle(200, 100));
            form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            byte[] formOperators = ("0 0.6 0 rg\n" +
                    "10 10 180 80 re\n" +
                    "f\n" +
                    "0 0 0 RG\n" +
                    "3 w\n" +
                    "0 0 m\n" +
                    "200 100 l\n" +
                    "S\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try (java.io.OutputStream formOut = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                formOut.write(formOperators);
            }

            // cs.drawForm(form) below registers the form under an auto-generated
            // resource name in the page's /Resources /XObject dictionary.
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, false)) {
                cs.saveGraphicsState();
                cs.transform(Matrix.getTranslateInstance(150, 400));
                cs.drawForm(form);
                cs.restoreGraphicsState();
            }

            return save(doc);
        }
    }

    // ------------------------------------------------------------------
    // 14. Robustness: deliberately undecodable ("poison") image XObjects
    // ------------------------------------------------------------------

    /**
     * PDF {@code /Filter} name every "poison" image fixture below is tagged
     * with. This project pulls in no JPEG2000 codec (see {@code pom.xml}):
     * neither PDFBox's own filter chain nor the JRE's default {@code ImageIO}
     * registry can decode anything tagged with it, so decode failure is
     * deterministic and independent of JVM/OS/optional-codec availability -
     * confirmed empirically (see the engineering log for this change): a
     * freshly built image tagged this way throws
     * {@code org.apache.pdfbox.filter.MissingImageReaderException} from
     * {@code PDImageXObject#getImage()} both immediately after construction
     * and after a full {@code save()}/{@code Loader.loadPDF()} round-trip.
     */
    public static final COSName UNDECODABLE_IMAGE_FILTER = COSName.getPDFName("JPXDecode");

    /**
     * Embeds a raw image XObject built directly from caller-supplied bytes,
     * tagged with an explicit PDF {@code /Filter} name and {@code /ColorSpace}
     *, with <b>no validation whatsoever</b> that the bytes are actually
     * valid data for that filter. This is exactly what lets robustness tests
     * construct a deterministically undecodable ("poison") image XObject: a
     * structurally valid PDF image dictionary (present in the resources,
     * counted normally, has real {@code /Width}/{@code /Height}/{@code
     * /BitsPerComponent} metadata so dictionary-only reads succeed) whose
     * pixel data can never actually be decoded.
     *
     * <p>The returned {@link PDImageXObject} has not been drawn onto any page
     * yet, place it exactly like any other image, e.g. via
     * {@code PDPageContentStream#drawImage}.
     */
    public static PDImageXObject embedRawImageXObject(PDDocument doc,
                                                        byte[] rawBytes,
                                                        COSName filter,
                                                        COSName colorSpace,
                                                        int width,
                                                        int height,
                                                        int bitsPerComponent) throws IOException {
        COSStream cosStream = doc.getDocument().createCOSStream();
        // The no-filter overload: writes rawBytes exactly as given, with no
        // encoding applied or validated. The /Filter entry set below is then
        // a bare, and, for a poison fixture, deliberately false, claim
        // about how those bytes are supposed to be decoded.
        try (OutputStream out = cosStream.createOutputStream()) {
            out.write(rawBytes);
        }
        if (filter != null) {
            cosStream.setItem(COSName.FILTER, filter);
        }
        cosStream.setItem(COSName.TYPE, COSName.XOBJECT);
        cosStream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        cosStream.setInt(COSName.WIDTH, width);
        cosStream.setInt(COSName.HEIGHT, height);
        cosStream.setInt(COSName.BITS_PER_COMPONENT, bitsPerComponent);
        cosStream.setItem(COSName.COLORSPACE, colorSpace);
        return new PDImageXObject(new PDStream(cosStream), null);
    }

    /**
     * Deterministic filler, large enough (&gt;= the default {@code
     * min-byte-size} of 8192 bytes) that a poison image built from it clears
     * the engine's size-based skip-gate and actually reaches decode-dependent
     * processing, rather than being left alone for the unrelated reason of
     * "too small to bother with".
     */
    private static byte[] undecodablePayloadBytes() {
        byte[] payload = new byte[9000];
        new Random(SEED + 555).nextBytes(payload);
        return payload;
    }

    /**
     * One normal, large, optimizable photographic image on page 1, plus one
     * deliberately undecodable ("poison") image XObject (see {@link
     * #UNDECODABLE_IMAGE_FILTER}) on page 2. The baseline robustness fixture:
     * a single undecodable image must never abort compressing the whole
     * document, must be left byte-for-byte untouched, and must not stop the
     * normal image from being optimized.
     */
    public static byte[] mixedCorpusWithUndecodableImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage normalPage = new PDPage(PDRectangle.A4);
            doc.addPage(normalPage);
            BufferedImage photo = syntheticPhotographicImage(1600, 1200);
            PDImageXObject normalImage = JPEGFactory.createFromImage(doc, photo, 0.9f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, normalPage)) {
                cs.drawImage(normalImage, 0, 0, normalPage.getMediaBox().getWidth(), normalPage.getMediaBox().getHeight());
            }

            PDPage poisonPage = new PDPage(PDRectangle.A4);
            doc.addPage(poisonPage);
            PDImageXObject poisonImage = embedRawImageXObject(doc, undecodablePayloadBytes(),
                    UNDECODABLE_IMAGE_FILTER, COSName.DEVICERGB, 200, 200, 8);
            try (PDPageContentStream cs = new PDPageContentStream(doc, poisonPage)) {
                cs.drawImage(poisonImage, 50, 50, 200, 200);
            }

            return save(doc);
        }
    }

    /**
     * A single document mixing several genuinely different image kinds -
     * photographic RGB, grayscale, 1-bit CCITT/bitonal, low-colour/indexed-
     * style line art, with one undecodable "poison" image XObject, one per
     * page, in this fixed order (pages 0-4). "Handle anything and
     * everything": every one of these must survive compression, either
     * optimized or passed through untouched, with none corrupted and no page
     * dropped.
     */
    public static byte[] mixedCodecCorpusWithUndecodableImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            // Page 0: photographic RGB.
            PDPage photoPage = new PDPage(PDRectangle.A4);
            doc.addPage(photoPage);
            PDImageXObject photoImage = JPEGFactory.createFromImage(doc, syntheticPhotographicImage(1200, 900), 0.9f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, photoPage)) {
                cs.drawImage(photoImage, 0, 0, photoPage.getMediaBox().getWidth(), photoPage.getMediaBox().getHeight());
            }

            // Page 1: grayscale.
            PDPage grayPage = new PDPage(PDRectangle.A4);
            doc.addPage(grayPage);
            PDImageXObject grayImage = LosslessFactory.createFromImage(doc, syntheticGrayscaleImage(800, 600));
            try (PDPageContentStream cs = new PDPageContentStream(doc, grayPage)) {
                cs.drawImage(grayImage, 50, 100, 500, 375);
            }

            // Page 2: 1-bit bitonal (CCITT).
            PDPage bitonalPage = new PDPage(PDRectangle.A4);
            doc.addPage(bitonalPage);
            PDImageXObject bitonalImage = CCITTFactory.createFromImage(doc, syntheticBitonalImage(1000, 1400));
            try (PDPageContentStream cs = new PDPageContentStream(doc, bitonalPage)) {
                cs.drawImage(bitonalImage, 50, 50, 495, 693);
            }

            // Page 3: low-colour / indexed-style line art (few distinct colours).
            PDPage indexedPage = new PDPage(PDRectangle.A4);
            doc.addPage(indexedPage);
            PDImageXObject indexedImage = LosslessFactory.createFromImage(doc, syntheticLowColorBlocksImage(640, 480));
            try (PDPageContentStream cs = new PDPageContentStream(doc, indexedPage)) {
                cs.drawImage(indexedImage, 50, 150, 500, 375);
            }

            // Page 4: undecodable "poison" image.
            PDPage poisonPage = new PDPage(PDRectangle.A4);
            doc.addPage(poisonPage);
            PDImageXObject poisonImage = embedRawImageXObject(doc, undecodablePayloadBytes(),
                    UNDECODABLE_IMAGE_FILTER, COSName.DEVICERGB, 200, 200, 8);
            try (PDPageContentStream cs = new PDPageContentStream(doc, poisonPage)) {
                cs.drawImage(poisonImage, 50, 50, 200, 200);
            }

            return save(doc);
        }
    }

    /**
     * A handful of large, flat colour bands: low colour-count, non-
     * photographic content (unlike {@link #syntheticPhotographicImage}), so
     * the engine's content classifier routes it down the lossless/indexed-
     * friendly path rather than JPEG, useful purely as "one more distinct
     * codec path" in the mixed-codec robustness corpus.
     */
    private static BufferedImage syntheticLowColorBlocksImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] palette = {0xE63946, 0xF1FAEE, 0xA8DADC, 0x457B9D, 0x1D3557};
        java.awt.Graphics2D g = image.createGraphics();
        int bandWidth = Math.max(1, width / palette.length);
        for (int i = 0; i < palette.length; i++) {
            g.setColor(new java.awt.Color(palette[i]));
            g.fillRect(i * bandWidth, 0, bandWidth, height);
        }
        g.dispose();
        return image;
    }

    // ------------------------------------------------------------------
    // Synthetic image generation
    // ------------------------------------------------------------------

    private static BufferedImage syntheticPhotographicImage(int width, int height) {
        return syntheticPhotographicImage(width, height, SEED);
    }

    /** Encodes an image to a JPEG2000 (JP2) byte stream via the jai-imageio JPEG2000 writer. */
    private static byte[] encodeJpeg2000(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpeg2000", out)) {
            throw new IllegalStateException("No JPEG2000 ImageIO writer is registered on the classpath");
        }
        return out.toByteArray();
    }

    /** High-entropy gradient + noise RGB image: genuinely "photographic", not flat-fillable. */
    private static BufferedImage syntheticPhotographicImage(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = clamp((int) (128 + 100 * Math.sin(x / 37.0) + random.nextInt(40) - 20));
                int g = clamp((int) (128 + 100 * Math.cos(y / 53.0) + random.nextInt(40) - 20));
                int b = clamp((int) (128 + 90 * Math.sin((x + y) / 61.0) + random.nextInt(40) - 20));
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static BufferedImage syntheticGrayscaleImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Random random = new Random(SEED);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = clamp((int) (128 + 110 * Math.sin((x + y) / 45.0) + random.nextInt(20) - 10));
                int rgb = (v << 16) | (v << 8) | v;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /** Fully random per-pixel RGB noise: maximum entropy, effectively incompressible. */
    private static BufferedImage highEntropyNoiseImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(SEED + width * 1000L + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF + 1));
            }
        }
        return image;
    }

    /** 1-bit black/white "scan": alternating text-like horizontal bars. */
    private static BufferedImage syntheticBitonalImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            boolean bandOn = (y / 20) % 3 == 0;
            for (int x = 0; x < width; x++) {
                boolean mark = bandOn && ((x / 4) % 5 != 0);
                image.setRGB(x, y, mark ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }

    /** ARGB image with a genuine alpha gradient: fully transparent -> fully opaque red. */
    private static BufferedImage syntheticTransparentImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int alpha = clamp((int) (255.0 * y / (height - 1)));
            for (int x = 0; x < width; x++) {
                int r = 220;
                int g = 40;
                int b = 40;
                int argb = (alpha << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static byte[] encodeRawJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(image, 0, 0, null);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /** A common system TrueType font, or {@code null} if none is present (so font tests can skip). */
    public static java.nio.file.Path systemTrueTypeFont() {
        String[] candidates = {
                "/System/Library/Fonts/Supplemental/Arial.ttf",
                "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
                "/System/Library/Fonts/Supplemental/Verdana.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans.ttf",
                "/Library/Fonts/Arial.ttf"
        };
        for (String path : candidates) {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (java.nio.file.Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * A multi-page, text-heavy PDF with a <b>fully embedded</b> (not subset) TrueType font, the
     * kind of document where font subsetting is the only meaningful win. Returns {@code null} if
     * no system TrueType font is available to embed.
     */
    public static byte[] fontHeavyDocument(int pageCount) throws IOException {
        java.nio.file.Path fontFile = systemTrueTypeFont();
        if (fontFile == null) {
            return null;
        }
        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.font.PDType0Font font;
            try (var in = java.nio.file.Files.newInputStream(fontFile)) {
                font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, in, false); // full embed
            }
            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.setLeading(15);
                    cs.newLineAtOffset(50, 780);
                    for (int line = 0; line < 40; line++) {
                        cs.showText("The DoctorHand guide, chapter " + line + ": record, review and sign notes.");
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            return save(doc);
        }
    }
}
