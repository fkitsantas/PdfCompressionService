package com.uk.noonans.pdfcompressionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import javax.annotation.PostConstruct;
import org.springframework.web.method.HandlerMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the PdfCompressionService application. This class
 * configures the Spring Boot application and logs the request mapping
 * information at startup.
 */
@SpringBootApplication(scanBasePackages="com.uk.noonans.pdfcompressionservice")
public class PdfCompressionServiceApplication {

    private final RequestMappingHandlerMapping handlerMapping; // Used to access request mapping details
    private static final Logger log = LoggerFactory.getLogger(PdfCompressionServiceApplication.class); // Logger for this class

    /**
     * Constructor for PdfCompressionServiceApplication.
     *
     * @param handlerMapping The object to access request mapping handler details.
     */
    public PdfCompressionServiceApplication(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    /**
     * Logs the mapping information after the application context is fully
     * created and just before the Spring Application starts. Useful for
     * debugging and understanding how endpoints are mapped at runtime.
     */
    @PostConstruct
    public void logMappings() {
        // Iterating over the handler methods and logging their mapping information
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            log.info("{} is mapped to {}", handler.getMethod(), handler.getBeanType());
        }
    }

    /**
     * The main method that serves as an entry point for the Spring Boot application.
     *
     * @param args The command-line arguments passed to the application.
     */
    public static void main(String[] args) {
        SpringApplication.run(PdfCompressionServiceApplication.class, args); // Starting the Spring Boot application
    }
}
