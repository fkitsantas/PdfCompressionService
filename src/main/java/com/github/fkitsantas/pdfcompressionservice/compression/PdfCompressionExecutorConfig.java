package com.github.fkitsantas.pdfcompressionservice.compression;

import java.util.concurrent.ExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link PdfCompressionEngine}'s single, shared, bounded
 * image-processing {@link ExecutorService} as a named Spring bean.
 *
 * <p>This does <b>not</b> create a second executor: it simply publishes the
 * engine's own {@link PdfCompressionEngine#getImageProcessingExecutor()}
 * instance into the application context so wiring tests (and any future
 * ops/metrics tooling) can look it up without reaching into the engine
 * internals. There remains exactly one executor per engine instance.
 */
@Configuration
public class PdfCompressionExecutorConfig {

    /** Stable bean name the wiring tests and implementer key off of. */
    public static final String BEAN_NAME = "pdfImageProcessingExecutor";

    @Bean(BEAN_NAME)
    public ExecutorService pdfImageProcessingExecutor(PdfCompressionEngine engine) {
        return engine.getImageProcessingExecutor();
    }
}
