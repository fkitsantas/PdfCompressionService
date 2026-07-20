package com.github.fkitsantas.pdfcompressionservice.analysis;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * Computes a {@link DocumentComposition} for a loaded PDF: it buckets every
 * stored stream's encoded (on-disk) byte size into images, fonts, vectors, or
 * other, so callers can see where a document's bytes actually live.
 *
 * <p>Classification is robust to malformed structure because it works mostly
 * from each stream's own dictionary. Image and form XObjects self-identify by
 * {@code /Subtype}; font programs are found by scanning {@code /FontFile*}
 * entries on font descriptors; page content streams are collected from the page
 * tree. Everything else falls into {@code other}. Streams are counted once by
 * identity, so shared objects are never double counted.
 */
public final class PdfCompositionAnalyzer {

    private static final COSName PATTERN_TYPE = COSName.getPDFName("PatternType");

    private PdfCompositionAnalyzer() {
    }

    public static DocumentComposition analyze(PDDocument doc, long fileSizeBytes) {
        COSDocument cos = doc.getDocument();
        Set<COSBase> fontStreams = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSBase> contentStreams = Collections.newSetFromMap(new IdentityHashMap<>());

        // Page content streams are vector/text drawing instructions.
        for (PDPage page : doc.getPages()) {
            collectContentStreams(page.getCOSObject().getDictionaryObject(COSName.CONTENTS), contentStreams);
        }
        // Embedded font programs, from every font descriptor in the document.
        for (COSObjectKey key : cos.getXrefTable().keySet()) {
            if (resolve(cos, key) instanceof COSDictionary dict) {
                addIfStream(dict.getDictionaryObject(COSName.FONT_FILE), fontStreams);
                addIfStream(dict.getDictionaryObject(COSName.FONT_FILE2), fontStreams);
                addIfStream(dict.getDictionaryObject(COSName.FONT_FILE3), fontStreams);
            }
        }

        Set<COSBase> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        long imageBytes = 0, fontBytes = 0, vectorBytes = 0, otherBytes = 0;
        int imageCount = 0, fontCount = 0, vectorCount = 0, otherCount = 0;

        for (COSObjectKey key : cos.getXrefTable().keySet()) {
            if (!(resolve(cos, key) instanceof COSStream stream) || !counted.add(stream)) {
                continue;
            }
            long len = Math.max(0, stream.getLength());
            if (fontStreams.contains(stream)) {
                fontBytes += len;
                fontCount++;
            } else if (contentStreams.contains(stream)) {
                vectorBytes += len;
                vectorCount++;
            } else {
                COSBase subtype = stream.getDictionaryObject(COSName.SUBTYPE);
                if (COSName.IMAGE.equals(subtype)) {
                    imageBytes += len;
                    imageCount++;
                } else if (COSName.FORM.equals(subtype) || stream.getDictionaryObject(PATTERN_TYPE) != null) {
                    vectorBytes += len;
                    vectorCount++;
                } else {
                    otherBytes += len;
                    otherCount++;
                }
            }
        }

        return DocumentComposition.of(fileSizeBytes, doc.getNumberOfPages(),
                imageBytes, imageCount, fontBytes, fontCount, vectorBytes, vectorCount, otherBytes, otherCount);
    }

    private static void collectContentStreams(COSBase contents, Set<COSBase> into) {
        if (contents instanceof COSStream stream) {
            into.add(stream);
        } else if (contents instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSStream stream) {
                    into.add(stream);
                }
            }
        }
    }

    private static void addIfStream(COSBase base, Set<COSBase> into) {
        if (base instanceof COSStream stream) {
            into.add(stream);
        }
    }

    /** Resolves an indirect object from the pool, tolerating a missing/unparseable entry. */
    private static COSBase resolve(COSDocument cos, COSObjectKey key) {
        try {
            return cos.getObjectFromPool(key).getObject();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
