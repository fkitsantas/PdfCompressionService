package com.github.fkitsantas.pdfcompressionservice.robustness;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

/**
 * Test-only utility: locates and fingerprints the deliberately-undecodable
 * ("poison") image XObjects the robustness fixtures embed, see {@link
 * InvoiceCorpusFactory#embedRawImageXObject}. A poison image is identified
 * unambiguously by its {@code /Filter} name ({@link InvoiceCorpusFactory#UNDECODABLE_IMAGE_FILTER}):
 * no normally-produced image in this test suite ever carries {@code
 * /JPXDecode}, since PDFBox has no JPEG2000 encoder to have produced one, so
 * this trait cannot collide with a "real" image.
 */
final class PoisonImageLocator {

    private PoisonImageLocator() {
    }

    static PDImageXObject find(PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDImageXObject image && isPoison(image)) {
                    return image;
                }
            }
        }
        throw new IllegalStateException("No poison (/JPXDecode) image XObject found in document");
    }

    static boolean isPoison(PDImageXObject image) {
        COSBase filterItem = ((COSStream) image.getCOSObject()).getItem(COSName.FILTER);
        return InvoiceCorpusFactory.UNDECODABLE_IMAGE_FILTER.equals(filterItem);
    }

    /** Raw (undecoded/as-filtered) stream bytes, the exact bytes a byte-for-byte "untouched" check must compare. */
    static byte[] rawEncodedBytes(PDImageXObject image) throws IOException {
        try (InputStream in = ((COSStream) image.getCOSObject()).createRawInputStream()) {
            return in.readAllBytes();
        }
    }

    /** The single (non-poison) image XObject on the given page. Mirrors the helper pattern used by the other engine test suites. */
    static PDImageXObject firstNonPoisonImage(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image && !isPoison(image)) {
                return image;
            }
        }
        throw new IllegalStateException("No non-poison image XObject found on page");
    }

    static long encodedLength(PDImageXObject image) {
        return ((COSStream) image.getCOSObject()).getLength();
    }

    static int countAllImageXObjects(PDDocument doc) throws IOException {
        int count = 0;
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject) {
                    count++;
                }
            }
        }
        return count;
    }
}
