package com.github.fkitsantas.pdfcompressionservice.service;

/** Signals that an autostart install/uninstall could not be completed; its message is client-safe. */
public class AutostartException extends RuntimeException {
    public AutostartException(String message) {
        super(message);
    }
}
