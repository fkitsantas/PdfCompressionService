package com.github.fkitsantas.pdfcompressionservice.web;

import java.time.Instant;

/**
 * Stable JSON error body returned by {@link CompressionExceptionHandler}.
 *
 * <p>{@code message} is always a client-safe description - never a raw
 * stack trace or exception class name - and {@code requestId} echoes back
 * the correlation id so a caller can cross-reference server-side logs.
 */
public final class ApiError {

    private final String timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String requestId;

    public ApiError(String timestamp, int status, String error, String message, String requestId) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.requestId = requestId;
    }

    public static ApiError of(int status, String error, String message, String requestId) {
        return new ApiError(Instant.now().toString(), status, error, message, requestId);
    }

    public String getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }
}
