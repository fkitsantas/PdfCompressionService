package com.github.fkitsantas.pdfcompressionservice.web;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.github.fkitsantas.pdfcompressionservice.compression.InvalidCompressionOptionException;
import com.github.fkitsantas.pdfcompressionservice.compression.InvalidPdfException;
import com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionException;
import com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotFoundException;
import com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.JobNotReadyException;
import com.github.fkitsantas.pdfcompressionservice.job.JobExceptions.TooManyActiveJobsException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Translates {@code /compressPdf} failures into a stable JSON {@link ApiError}
 * body instead of a bare status code with no explanation (or, worse, the
 * historical bare {@code 500} with an empty body). Full exception details
 * (including stack traces) go to the server log only, the response body
 * never leaks internals, just a client-safe message and the correlation id.
 *
 * <p>The request id is normally the one the controller generated and stashed
 * on the request via {@link #REQUEST_ID_ATTRIBUTE} before invoking the
 * engine. For failures that happen before the controller method even runs
 * (e.g. a missing multipart part fails argument resolution), no such
 * attribute exists yet, so a fresh id is minted here instead.
 */
@RestControllerAdvice
public class CompressionExceptionHandler {

    /** Request attribute the controller uses to publish its generated request id to this advice. */
    public static final String REQUEST_ID_ATTRIBUTE = "com.github.fkitsantas.pdfcompressionservice.requestId";

    private static final Logger log = LoggerFactory.getLogger(CompressionExceptionHandler.class);

    @ExceptionHandler(InvalidPdfException.class)
    public ResponseEntity<ApiError> handleInvalidPdf(InvalidPdfException ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("requestId={} action=compress-rejected reason=invalid-pdf", requestId, ex);
        return build(HttpStatus.UNPROCESSABLE_ENTITY,
                "The uploaded file is not a valid PDF document.", requestId);
    }

    @ExceptionHandler(InvalidCompressionOptionException.class)
    public ResponseEntity<ApiError> handleInvalidOption(InvalidCompressionOptionException ex,
                                                        HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("requestId={} action=compress-rejected reason=invalid-option detail={}", requestId, ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), requestId);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("requestId={} action=compress-rejected reason=missing-part part={}",
                requestId, ex.getRequestPartName());
        return build(HttpStatus.BAD_REQUEST,
                "Required request part '" + ex.getRequestPartName() + "' is missing.", requestId);
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ApiError> handleJobNotFound(JobNotFoundException ex, HttpServletRequest request) {
        String requestId = requestId(request);
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), requestId);
    }

    @ExceptionHandler(JobNotReadyException.class)
    public ResponseEntity<ApiError> handleJobNotReady(JobNotReadyException ex, HttpServletRequest request) {
        String requestId = requestId(request);
        return build(HttpStatus.CONFLICT, ex.getMessage(), requestId);
    }

    @ExceptionHandler(TooManyActiveJobsException.class)
    public ResponseEntity<ApiError> handleTooManyJobs(TooManyActiveJobsException ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("requestId={} action=job-rejected reason=too-many-active-jobs", requestId);
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), requestId);
    }

    @ExceptionHandler(PdfCompressionException.class)
    public ResponseEntity<ApiError> handleCompressionFailure(PdfCompressionException ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("requestId={} action=compress-failed reason=compression-error", requestId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "PDF compression failed unexpectedly.", requestId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("requestId={} action=compress-failed reason=unexpected-error", requestId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", requestId);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, String requestId) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, requestId);
        return ResponseEntity.status(status).body(body);
    }

    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return attribute != null ? attribute.toString() : UUID.randomUUID().toString();
    }
}
