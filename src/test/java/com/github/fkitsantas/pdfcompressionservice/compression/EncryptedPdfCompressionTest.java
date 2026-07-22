package com.github.fkitsantas.pdfcompressionservice.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import com.github.fkitsantas.pdfcompressionservice.fixtures.InvoiceCorpusFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A permission-restricted (owner-password) PDF opens without a password but still
 * carries an {@code /Encrypt} dictionary, which PDFBox refuses to write - PDFBox
 * throws "PDF contains an encryption dictionary ...". The engine must drop that
 * security so such files still compress, rather than failing the request with a 500.
 */
class EncryptedPdfCompressionTest {

    @Test
    void permissionRestrictedPdfIsCompressedWithSecurityRemoved() throws IOException {
        byte[] encrypted = encrypt(InvoiceCorpusFactory.multipleLargeInvoiceImages(3));
        // Precondition: the fixture really is encrypted, yet opens without a password.
        try (PDDocument doc = Loader.loadPDF(encrypted)) {
            assertThat(doc.isEncrypted()).as("fixture must be encrypted").isTrue();
        }

        CompressionResult result = new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(encrypted, "restricted.pdf", "req-encrypted");

        assertThat(result.isReturnedOriginal())
                .as("the image-heavy document should still compress").isFalse();
        try (PDDocument out = Loader.loadPDF(result.getCompressedPdf())) {
            assertThat(out.isEncrypted())
                    .as("the compressed output is written without the encryption dictionary").isFalse();
        }
    }

    @Test
    void encryptedPdfNeverThrows() throws IOException {
        byte[] encrypted = encrypt(InvoiceCorpusFactory.multipleLargeInvoiceImages(2));
        assertThatCode(() -> new PdfCompressionEngine(new PdfCompressionProperties())
                .compress(encrypted, "restricted.pdf", "req-enc-2"))
                .doesNotThrowAnyException();
    }

    /** Applies standard security with an empty user password (opens freely) and an owner password. */
    private static byte[] encrypt(byte[] plain) throws IOException {
        try (PDDocument doc = Loader.loadPDF(plain)) {
            AccessPermission permissions = new AccessPermission();
            permissions.setCanModify(false);
            permissions.setCanExtractContent(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "", permissions);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
