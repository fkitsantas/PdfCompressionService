package com.github.fkitsantas.pdfcompressionservice.compression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Verifies the two new {@code pdf.compression.*} tunables that control
 * intra-request image-processing parallelism: {@code parallelism} and
 * {@code parallel-image-threshold}.
 *
 * <p>Same rationale as {@link PdfCompressionPropertiesTest} for testing the
 * setters directly rather than via a {@code jakarta.validation.Validator}
 * (none is on this offline build's classpath, see that class's Javadoc).
 *
 * <p><b>Unlike the behavioural parallelism tests in the {@code concurrency}
 * package, this class is expected to be GREEN already</b>, exactly like its
 * sibling {@link PdfCompressionPropertiesTest}: configuration binding is
 * plain data-holder plumbing, not "the real logic" this feature defers to
 * the implementer (the actual parallel dispatch inside
 * {@link PdfCompressionEngine#compress}). Pinning correct, fully-working
 * config binding now means the implementer can safely build the real
 * parallel path on top of a trustworthy {@link PdfCompressionProperties}
 * contract without also having to get the plumbing right.
 */
class PdfCompressionParallelPropertiesTest {

    @Test
    void parallelismDefaultsToZeroMeaningAuto() {
        PdfCompressionProperties props = new PdfCompressionProperties();
        assertThat(props.getParallelism()).isEqualTo(0);
    }

    @Test
    void parallelImageThresholdDefaultsToTwo() {
        PdfCompressionProperties props = new PdfCompressionProperties();
        assertThat(props.getParallelImageThreshold()).isEqualTo(2);
    }

    @Test
    void parallelismAcceptsZeroAndExplicitPositiveValues() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setParallelism(0);
        assertThat(props.getParallelism()).isEqualTo(0);

        props.setParallelism(8);
        assertThat(props.getParallelism()).isEqualTo(8);

        props.setParallelism(1);
        assertThat(props.getParallelism()).isEqualTo(1);
    }

    @Test
    void parallelismRejectsNegativeValues() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> props.setParallelism(-1))
                .withMessageContaining("parallelism");
    }

    @Test
    void parallelismZeroResolvesToAvailableProcessorsAtPointOfUse() {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setParallelism(0);

        assertThat(props.resolveParallelism()).isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void parallelismExplicitPositiveValueResolvesToItselfUnchanged() {
        PdfCompressionProperties props = new PdfCompressionProperties();
        props.setParallelism(6);

        assertThat(props.resolveParallelism()).isEqualTo(6);
    }

    @Test
    void parallelImageThresholdAcceptsZeroAndPositiveValues() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        props.setParallelImageThreshold(0);
        assertThat(props.getParallelImageThreshold()).isEqualTo(0);

        props.setParallelImageThreshold(10);
        assertThat(props.getParallelImageThreshold()).isEqualTo(10);
    }

    @Test
    void parallelImageThresholdRejectsNegativeValues() {
        PdfCompressionProperties props = new PdfCompressionProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> props.setParallelImageThreshold(-1))
                .withMessageContaining("parallel-image-threshold");
    }
}
