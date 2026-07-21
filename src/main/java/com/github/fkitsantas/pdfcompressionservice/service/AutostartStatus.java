package com.github.fkitsantas.pdfcompressionservice.service;

import java.util.List;

/**
 * JSON view of the autostart situation for the UI: the OS, the launch command
 * that would be registered, any blocker (e.g. macOS quarantine translocation),
 * and, per scope, whether it is installed plus the exact artifact and commands.
 * The privileged (boot) commands are surfaced for the user to run in their own
 * terminal - the app never runs them, so it never handles an admin password.
 */
public record AutostartStatus(boolean enabled, String os, String launchCommand,
                              boolean translocated, String blocker, Scope login, Scope boot) {

    /**
     * @param installCommands   the install steps as copy-pasteable command lines (run by the app for {@code login},
     *                          shown for the user to run for {@code boot})
     * @param uninstallCommands the uninstall steps as command lines
     */
    public record Scope(String id, String title, boolean supported, boolean privileged, boolean installed,
                        String artifactPath, String artifactContent,
                        List<String> installCommands, List<String> uninstallCommands) {
    }
}
