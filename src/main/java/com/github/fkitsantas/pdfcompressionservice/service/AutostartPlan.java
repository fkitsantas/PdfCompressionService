package com.github.fkitsantas.pdfcompressionservice.service;

import java.nio.file.Path;
import java.util.List;

/**
 * A pure, side-effect-free description of how to make this application start
 * automatically on a given OS - the artifacts to create and the exact commands
 * to run - for both the unprivileged <b>login</b> scope and the privileged
 * <b>boot</b> scope. It executes nothing itself; {@link AutostartManager} runs
 * the login steps and {@code AutostartController} surfaces the boot steps for the
 * user to run in their own terminal (so the app never handles an admin password).
 *
 * <p>Every command is a fixed argv built only from the OS, the app's own launch
 * command, and fixed identifiers - never from request input - so there is no
 * command-injection surface.
 */
public record AutostartPlan(OperatingSystem os, String launchDisplay, Scope login, Scope boot) {

    /** The reverse-DNS label / service name / scheduled-task name this app manages. Fixed, never user input. */
    public static final String LABEL = "com.github.fkitsantas.pdfcompressionservice";
    public static final String UNIT = "pdfcompressionservice";
    public static final String TASK = "PdfCompressionService";

    /**
     * One autostart scope (login or boot).
     *
     * @param id              {@code "login"} or {@code "boot"}
     * @param title           human-facing title
     * @param privileged      whether install requires admin/root (and so must be run by the user, not the app)
     * @param supported       whether this OS supports this scope for this app
     * @param artifactPath    a file the app stages before install (plist/unit), or {@code null} (e.g. Windows tasks)
     * @param artifactContent the artifact's content, or {@code null}
     * @param installSteps    argv steps to install (the app runs these for login; they are shown for boot)
     * @param uninstallSteps  argv steps to uninstall
     * @param installedMarker a file whose presence means "installed", or {@code null} when it must be queried
     */
    public record Scope(String id, String title, boolean privileged, boolean supported,
                        Path artifactPath, String artifactContent,
                        List<List<String>> installSteps, List<List<String>> uninstallSteps,
                        Path installedMarker) {
    }

    /**
     * Builds the plan for {@code os}, launching this app via {@code launch}.
     *
     * @param os     the host OS
     * @param launch the argv that starts this application (used verbatim in the autostart entry)
     * @param home   the user's home directory
     * @param user   the user name (for the boot-scope service {@code User=})
     * @param uid    the numeric user id (for {@code launchctl gui/<uid>}), or {@code -1} if unknown
     */
    public static AutostartPlan build(OperatingSystem os, List<String> launch, Path home, String user, int uid) {
        String display = shellJoin(launch);
        return switch (os) {
            case MACOS -> macos(launch, home, uid, display);
            case LINUX -> linux(launch, home, user, display);
            case WINDOWS -> windows(launch, display);
            case UNKNOWN -> new AutostartPlan(os, display,
                    unsupported("login", "Start when I log in"),
                    unsupported("boot", "Start at boot (all users)"));
        };
    }

    private static AutostartPlan macos(List<String> launch, Path home, int uid, String display) {
        Path agent = home.resolve("Library/LaunchAgents/" + LABEL + ".plist");
        Path logDir = home.resolve("Library/Logs");
        String agentPlist = plist(launch, logDir.resolve("PdfCompressionService.out.log").toString(),
                logDir.resolve("PdfCompressionService.err.log").toString());
        String gui = "gui/" + uid;
        Scope login = new Scope("login", "Start when I log in", false, uid >= 0,
                agent, agentPlist,
                List.of(List.of("launchctl", "bootstrap", gui, agent.toString())),
                List.of(List.of("launchctl", "bootout", gui + "/" + LABEL)),
                agent);

        Path staged = home.resolve("Library/Application Support/PdfCompressionService/" + LABEL + ".plist");
        Path daemon = Path.of("/Library/LaunchDaemons/" + LABEL + ".plist");
        String daemonPlist = plist(launch, "/tmp/PdfCompressionService.out.log", "/tmp/PdfCompressionService.err.log");
        Scope boot = new Scope("boot", "Start at boot (all users)", true, true,
                staged, daemonPlist,
                List.of(List.of("sudo", "cp", staged.toString(), daemon.toString()),
                        List.of("sudo", "launchctl", "bootstrap", "system", daemon.toString())),
                List.of(List.of("sudo", "launchctl", "bootout", "system/" + LABEL),
                        List.of("sudo", "rm", daemon.toString())),
                daemon);
        return new AutostartPlan(OperatingSystem.MACOS, display, login, boot);
    }

    private static AutostartPlan linux(List<String> launch, Path home, String user, String display) {
        String exec = shellJoin(launch);
        Path userUnit = home.resolve(".config/systemd/user/" + UNIT + ".service");
        Scope login = new Scope("login", "Start when I log in", false, true,
                userUnit, unit(exec, "default.target", null),
                List.of(List.of("systemctl", "--user", "daemon-reload"),
                        List.of("systemctl", "--user", "enable", "--now", UNIT)),
                List.of(List.of("systemctl", "--user", "disable", "--now", UNIT)),
                userUnit);

        Path staged = home.resolve(".config/pdfcompressionservice/" + UNIT + ".service");
        Path systemUnit = Path.of("/etc/systemd/system/" + UNIT + ".service");
        Scope boot = new Scope("boot", "Start at boot (all users)", true, true,
                staged, unit(exec, "multi-user.target", user),
                List.of(List.of("sudo", "cp", staged.toString(), systemUnit.toString()),
                        List.of("sudo", "systemctl", "enable", "--now", UNIT)),
                List.of(List.of("sudo", "systemctl", "disable", "--now", UNIT),
                        List.of("sudo", "rm", systemUnit.toString())),
                systemUnit);
        return new AutostartPlan(OperatingSystem.LINUX, display, login, boot);
    }

    private static AutostartPlan windows(List<String> launch, String display) {
        String tr = shellJoin(launch);
        Scope login = new Scope("login", "Start when I log in", false, true,
                null, null,
                List.of(List.of("schtasks", "/Create", "/SC", "ONLOGON", "/TN", TASK, "/TR", tr, "/F")),
                List.of(List.of("schtasks", "/Delete", "/TN", TASK, "/F")),
                null);
        Scope boot = new Scope("boot", "Start at boot (all users)", true, true,
                null, null,
                List.of(List.of("schtasks", "/Create", "/SC", "ONSTART", "/RU", "SYSTEM", "/RL", "HIGHEST",
                        "/TN", TASK, "/TR", tr, "/F")),
                List.of(List.of("schtasks", "/Delete", "/TN", TASK, "/F")),
                null);
        return new AutostartPlan(OperatingSystem.WINDOWS, display, login, boot);
    }

    private static Scope unsupported(String id, String title) {
        return new Scope(id, title, id.equals("boot"), false, null, null, List.of(), List.of(), null);
    }

    // ---- artifact templates ----

    private static String plist(List<String> launch, String outLog, String errLog) {
        StringBuilder args = new StringBuilder();
        for (String a : launch) {
            args.append("        <string>").append(xml(a)).append("</string>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>            <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                %s    </array>
                    <key>RunAtLoad</key>        <true/>
                    <key>KeepAlive</key>        <true/>
                    <key>StandardOutPath</key>  <string>%s</string>
                    <key>StandardErrorPath</key> <string>%s</string>
                </dict>
                </plist>
                """.formatted(LABEL, args.toString(), xml(outLog), xml(errLog));
    }

    private static String unit(String exec, String wantedBy, String user) {
        String userLine = user == null ? "" : "User=" + user + "\n";
        return """
                [Unit]
                Description=PDF Compression Service
                After=network.target

                [Service]
                Type=simple
                ExecStart=%s
                Restart=always
                RestartSec=5
                %s
                [Install]
                WantedBy=%s
                """.formatted(exec, userLine, wantedBy);
    }

    /** Joins an argv into a single command line, double-quoting any argument that needs it (for display and unit/task use). */
    static String shellJoin(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (a.isEmpty() || a.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\t' || c == '\\')) {
                sb.append('"').append(a.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
