package com.github.fkitsantas.pdfcompressionservice.compression;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the ICC-profile de-duplicator: two byte-identical embedded profiles are
 * merged into one shared object (so the file shrinks), while a document with a
 * single profile is left untouched.
 */
class IccProfileDeduplicatorTest {

    private static final COSName ICCBASED = COSName.getPDFName("ICCBased");

    @Test
    void mergesTwoIdenticalIccProfiles() throws IOException {
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        byte[] pdf = pdfWithProfiles(icc, icc); // two identical, embedded as separate objects

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            long saved = IccProfileDeduplicator.deduplicate(doc, "req-icc");
            assertThat(saved).as("one duplicate profile's bytes are reclaimed").isPositive();

            COSArray[] arrays = iccArraysInResources(doc);
            assertThat(arrays).hasSize(2);
            assertThat(arrays[0].getObject(1))
                    .as("both colour spaces now share the same profile object")
                    .isSameAs(arrays[1].getObject(1));
        }
    }

    @Test
    void leavesASingleProfileUntouched() throws IOException {
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        byte[] pdf = pdfWithProfiles(icc); // one profile

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(IccProfileDeduplicator.deduplicate(doc, "req-icc-single")).isZero();
        }
    }

    private static byte[] pdfWithProfiles(byte[]... profiles) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);
            org.apache.pdfbox.cos.COSDictionary csDict = new org.apache.pdfbox.cos.COSDictionary();
            for (int i = 0; i < profiles.length; i++) {
                COSStream stream = doc.getDocument().createCOSStream();
                try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                    out.write(profiles[i]);
                }
                stream.setInt(COSName.N, 3);
                COSArray array = new COSArray();
                array.add(ICCBASED);
                array.add(stream);
                csDict.setItem("CS" + i, array);
            }
            resources.getCOSObject().setItem(COSName.COLORSPACE, csDict);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static COSArray[] iccArraysInResources(PDDocument doc) {
        var csDict = (org.apache.pdfbox.cos.COSDictionary) doc.getPage(0).getResources().getCOSObject()
                .getDictionaryObject(COSName.COLORSPACE);
        return csDict.getValues().stream()
                .map(v -> v instanceof org.apache.pdfbox.cos.COSObject o ? o.getObject() : v)
                .filter(COSArray.class::isInstance)
                .map(COSArray.class::cast)
                .filter(a -> ICCBASED.equals(a.getObject(0)))
                .toArray(COSArray[]::new);
    }
}
