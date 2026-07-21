package com.github.fkitsantas.pdfcompressionservice.service;

import java.util.Locale;

/** The host operating system family, used to pick the right autostart mechanism. */
public enum OperatingSystem {

    MACOS, LINUX, WINDOWS, UNKNOWN;

    /** Detects the OS from the given {@code os.name} value. */
    public static OperatingSystem detect(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("nux") || name.contains("nix") || name.contains("aix")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    public static OperatingSystem current() {
        return detect(System.getProperty("os.name"));
    }
}
