package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges byte-identical embedded ICC colour profiles into a single shared object.
 * Producers frequently embed the same profile (e.g. an sRGB or a multi-hundred-KB
 * CMYK profile) once per image; on a many-image document that is pure duplication.
 *
 * <p>Losslessly safe by construction: two {@code /ICCBased} profiles are merged
 * only when their stored bytes and component count are identical, and the merge
 * is just re-pointing the second reference at the first, so the rendered colour
 * is unchanged. The now-unreferenced duplicate is dropped when the document is
 * written.
 */
final class IccProfileDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(IccProfileDeduplicator.class);
    private static final COSName ICCBASED = COSName.getPDFName("ICCBased");

    private IccProfileDeduplicator() {
    }

    /** De-duplicates ICC profiles in place and returns the approximate stored bytes saved. */
    static long deduplicate(PDDocument doc, String requestId) {
        List<COSArray> iccArrays = findIccBasedArrays(doc);
        if (iccArrays.size() < 2) {
            return 0;
        }
        Map<String, COSStream> canonicalByHash = new HashMap<>();
        Set<COSBase> eliminated = Collections.newSetFromMap(new IdentityHashMap<>());
        long saved = 0;
        for (COSArray array : iccArrays) {
            if (!(array.getObject(1) instanceof COSStream profile)) {
                continue;
            }
            String hash = fingerprint(profile);
            if (hash == null) {
                continue;
            }
            COSStream canonical = canonicalByHash.putIfAbsent(hash, profile);
            if (canonical != null && canonical != profile) {
                array.set(1, canonical); // share the first identical profile
                if (eliminated.add(profile)) {
                    saved += Math.max(0, profile.getLength());
                }
            }
        }
        if (saved > 0) {
            log.info("requestId={} action=icc-deduplicated profiles={} approxBytesSaved={}",
                    requestId, eliminated.size(), saved);
        }
        return saved;
    }

    /** Walks the whole object graph and collects every {@code [/ICCBased <stream>]} colour-space array. */
    private static List<COSArray> findIccBasedArrays(PDDocument doc) {
        List<COSArray> result = new ArrayList<>();
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> stack = new ArrayDeque<>();
        stack.push(doc.getDocumentCatalog().getCOSObject());
        while (!stack.isEmpty()) {
            COSBase base = resolve(stack.pop());
            if (base == null || !visited.add(base)) {
                continue;
            }
            if (base instanceof COSArray array) {
                if (array.size() >= 2 && ICCBASED.equals(array.getObject(0))
                        && array.getObject(1) instanceof COSStream) {
                    result.add(array);
                }
                for (int i = 0; i < array.size(); i++) {
                    stack.push(array.get(i));
                }
            } else if (base instanceof COSDictionary dict) { // COSStream is a COSDictionary too
                for (COSBase value : dict.getValues()) {
                    stack.push(value);
                }
            }
        }
        return result;
    }

    private static COSBase resolve(COSBase base) {
        try {
            return base instanceof COSObject object ? object.getObject() : base;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** SHA-256 over the profile's stored bytes plus its component count; {@code null} if unreadable. */
    private static String fingerprint(COSStream profile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = profile.createRawInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) profile.getInt(COSName.N, 0));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }
}
