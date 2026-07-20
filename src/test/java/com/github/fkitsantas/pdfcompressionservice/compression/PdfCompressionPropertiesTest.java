package com.github.fkitsantas.pdfcompressionservice.compression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Verifies {@link PdfCompressionProperties} defaults and bound enforcement.
 *
 * <p>Note: {@code jakarta.validation-api} / Hibernate Validator are not
 * available in this offline build (see the class Javadoc on
 * {@link PdfCompressionProperties} for the rationale), so bounds are
 * enforced imperatively in the setters rather than via
 * {@code @Min}/{@code @DecimalMax}-style annotations. This test therefore
 * exercises the setters directly instead of a {@code jakarta.validation.Validator}.
 *
 * <p>Unlike the engine behavioural tests, this class targets a fully
 * implemented skeleton (not a stub) and is expected to be GREEN already.
 */
class PdfCompressionPropertiesTest {

    @Test
    void defaultsMatchSpecifiedValues() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        assertThat(props.getTargetDpi()).isEqualTo(150);
        assertThat(props.getMaxImageDimension()).isEqualTo(0); // 0 = no cap
        assertThat(props.getJpegQuality()).isEqualTo(0.75f);
        assertThat(props.getMinDimension()).isEqualTo(16);
        assertThat(props.getMinByteSize()).isEqualTo(8192L);
        assertThat(props.getMinReductionRatio()).isEqualTo(0.10f);
        assertThat(props.getLargerResultPolicy()).isEqualTo(LargerResultPolicy.KEEP_ORIGINAL);
        assertThat(props.getStreamCache()).isEqualTo(StreamCacheMode.TEMP_FILE);
        assertThat(props.isRecompressCmyk()).isFalse();
    }

    @Test
    void targetDpiAcceptsLowerAndUpperBoundsAndRejectsOutOfRange() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setTargetDpi(1);
        assertThat(props.getTargetDpi()).isEqualTo(1);
        props.setTargetDpi(2400);
        assertThat(props.getTargetDpi()).isEqualTo(2400);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setTargetDpi(0));
        assertThatIllegalArgumentException().isThrownBy(() -> props.setTargetDpi(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> props.setTargetDpi(2401));
    }

    @Test
    void maxImageDimensionAllowsZeroForNoCapAnyPositiveAndRejectsNegative() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setMaxImageDimension(0);       // 0 = no cap (the default)
        props.setMaxImageDimension(1);
        props.setMaxImageDimension(60_000);  // beyond a 48-megapixel edge; no artificial ceiling

        assertThatIllegalArgumentException().isThrownBy(() -> props.setMaxImageDimension(-1));
    }

    @Test
    void maxDecodePixelsAllowsZeroToDisableAnyPositiveAndRejectsNegative() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setMaxDecodePixels(0L);            // 0 = guard disabled
        props.setMaxDecodePixels(1L);
        props.setMaxDecodePixels(500_000_000L);  // the default ceiling

        assertThatIllegalArgumentException().isThrownBy(() -> props.setMaxDecodePixels(-1L));
    }

    @Test
    void jpegQualityAcceptsFullUnitIntervalAndRejectsOutside() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setJpegQuality(0.0f);
        assertThat(props.getJpegQuality()).isEqualTo(0.0f);
        props.setJpegQuality(1.0f);
        assertThat(props.getJpegQuality()).isEqualTo(1.0f);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setJpegQuality(-0.01f));
        assertThatIllegalArgumentException().isThrownBy(() -> props.setJpegQuality(1.01f));
    }

    @Test
    void minDimensionAcceptsBoundsAndRejectsOutOfRange() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setMinDimension(1);
        props.setMinDimension(10_000);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setMinDimension(0));
        assertThatIllegalArgumentException().isThrownBy(() -> props.setMinDimension(10_001));
    }

    @Test
    void minByteSizeAcceptsZeroAndRejectsNegative() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setMinByteSize(0L);
        assertThat(props.getMinByteSize()).isEqualTo(0L);
        props.setMinByteSize(Long.MAX_VALUE);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setMinByteSize(-1L));
    }

    @Test
    void minReductionRatioAcceptsFullUnitIntervalAndRejectsOutside() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setMinReductionRatio(0.0f);
        props.setMinReductionRatio(1.0f);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setMinReductionRatio(-0.01f));
        assertThatIllegalArgumentException().isThrownBy(() -> props.setMinReductionRatio(1.01f));
    }

    @Test
    void largerResultPolicyRejectsNull() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setLargerResultPolicy(LargerResultPolicy.USE_SMALLEST);
        assertThat(props.getLargerResultPolicy()).isEqualTo(LargerResultPolicy.USE_SMALLEST);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setLargerResultPolicy(null))
                .withMessageContaining("larger-result-policy");
    }

    @Test
    void streamCacheRejectsNull() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setStreamCache(StreamCacheMode.MEMORY);
        assertThat(props.getStreamCache()).isEqualTo(StreamCacheMode.MEMORY);

        assertThatIllegalArgumentException().isThrownBy(() -> props.setStreamCache(null))
                .withMessageContaining("stream-cache");
    }

    @Test
    void recompressCmykIsFreelyToggleable() {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setRecompressCmyk(true);
        assertThat(props.isRecompressCmyk()).isTrue();
        props.setRecompressCmyk(false);
        assertThat(props.isRecompressCmyk()).isFalse();
    }
}
