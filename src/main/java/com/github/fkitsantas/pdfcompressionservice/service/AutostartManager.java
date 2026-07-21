package com.github.fkitsantas.pdfcompressionservice.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects how this application was launched and manages its OS autostart entry.
 *
 * <p>The unprivileged <b>login</b> scope is installed/uninstalled directly by the
 * app (no password needed). The privileged <b>boot</b> scope is <b>never</b>
 * executed here: its commands are only surfaced (via {@link #status()}) for the
 * user to run in their own terminal, so the app never asks for, sees, or types
 * an admin password. Every command is a fixed argv (no request input), so there
 * is no command-injection surface; the app only ever manages its own service.
 */
@Component
public class AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(AutostartManager.class);

    private final boolean enabled;

    public AutostartManager(
            @org.springframework.beans.factory.annotation.Value("${pdf.service.autostart.enabled:true}")
            boolean enabled) {
        this.enabled = enabled;
    }

    /** Current status for the UI. Best-effort: detection failures degrade to "unsupported", never throw. */
    public AutostartStatus status() {
        OperatingSystem os = OperatingSystem.current();
        List<String> launch = detectLaunch();
        AutostartPlan plan = buildPlan(os, launch);
        String blocker = blocker(os, launch);
        // Stage the boot artifact so the shown `sudo cp` refers to a real file; harmless, not an install.
        stageQuietly(plan.boot());
        return new AutostartStatus(enabled, os.name(), plan.launchDisplay(),
                blocker != null, blocker, view(plan.login()), view(plan.boot()));
    }

    /** Installs the unprivileged login-scope autostart entry (writes the artifact, runs the steps). */
    public void installLogin() {
        requireEnabled();
        OperatingSystem os = OperatingSystem.current();
        List<String> launch = detectLaunch();
        String blocker = blocker(os, launch);
        if (blocker != null) {
            throw new AutostartException(blocker);
        }
        AutostartPlan.Scope s = buildPlan(os, launch).login();
        if (!s.supported()) {
            throw new AutostartException("Starting at login is not supported on this system.");
        }
        writeArtifact(s);
        runAll(s.installSteps());
        log.info("action=autostart-installed scope=login os={}", os);
    }

    /** Uninstalls the login-scope autostart entry (runs the steps, deletes the artifact). */
    public void uninstallLogin() {
        requireEnabled();
        OperatingSystem os = OperatingSystem.current();
        AutostartPlan.Scope s = buildPlan(os, detectLaunch()).login();
        runAll(s.uninstallSteps());
        if (s.artifactPath() != null) {
            try {
                Files.deleteIfExists(s.artifactPath());
            } catch (IOException e) {
                throw new AutostartException("Removed the service but could not delete " + s.artifactPath());
            }
        }
        log.info("action=autostart-uninstalled scope=login os={}", os);
    }

    // ------------------------------------------------------------------

    private void requireEnabled() {
        if (!enabled) {
            throw new AutostartException("The autostart feature is disabled (pdf.service.autostart.enabled=false).");
        }
    }

    AutostartPlan buildPlan(OperatingSystem os, List<String> launch) {
        Path home = Path.of(System.getProperty("user.home", "."));
        String user = System.getProperty("user.name", "");
        return AutostartPlan.build(os, launch, home, user, detectUid(os));
    }

    private AutostartStatus.Scope view(AutostartPlan.Scope s) {
        return new AutostartStatus.Scope(s.id(), s.title(), s.supported(), s.privileged(), isInstalled(s),
                s.artifactPath() == null ? null : s.artifactPath().toString(), s.artifactContent(),
                lines(s.installSteps()), lines(s.uninstallSteps()));
    }

    private static List<String> lines(List<List<String>> steps) {
        List<String> out = new ArrayList<>(steps.size());
        for (List<String> step : steps) {
            out.add(AutostartPlan.shellJoin(step));
        }
        return out;
    }

    /** Whether the scope is currently installed: a marker file for file-based OSes, else a scheduled-task query. */
    private boolean isInstalled(AutostartPlan.Scope s) {
        if (s.installedMarker() != null) {
            return Files.exists(s.installedMarker());
        }
        if (OperatingSystem.current() == OperatingSystem.WINDOWS && !s.privileged()) {
            ExecResult r = execQuiet(List.of("schtasks", "/Query", "/TN", AutostartPlan.TASK));
            return r != null && r.exit() == 0;
        }
        return false;
    }

    private String blocker(OperatingSystem os, List<String> launch) {
        if (os == OperatingSystem.MACOS && launch.stream().anyMatch(a -> a.contains("/AppTranslocation/"))) {
            return "The app is running from a temporary quarantine location (App Translocation), whose path "
                    + "changes on every launch. Move PdfCompressionService.app into /Applications and remove the "
                    + "quarantine flag (xattr -dr com.apple.quarantine PdfCompressionService.app), then relaunch it "
                    + "before enabling autostart.";
        }
        return null;
    }

    private void writeArtifact(AutostartPlan.Scope s) {
        if (s.artifactPath() == null) {
            return;
        }
        try {
            Files.createDirectories(s.artifactPath().getParent());
            Files.writeString(s.artifactPath(), s.artifactContent(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AutostartException("Could not write " + s.artifactPath() + ": " + e.getMessage());
        }
    }

    private void stageQuietly(AutostartPlan.Scope s) {
        try {
            writeArtifact(s);
        } catch (RuntimeException ignored) {
            // best-effort staging for the displayed boot commands
        }
    }

    private void runAll(List<List<String>> steps) {
        for (List<String> step : steps) {
            ExecResult r = execQuiet(step);
            if (r == null) {
                throw new AutostartException("Could not run: " + AutostartPlan.shellJoin(step));
            }
            if (r.exit() != 0) {
                throw new AutostartException("Command failed (" + r.exit() + "): "
                        + AutostartPlan.shellJoin(step) + (r.output().isBlank() ? "" : " -> " + r.output()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Launch-command / environment detection
    // ------------------------------------------------------------------

    /** The argv that starts this app, to be registered for autostart. */
    List<String> detectLaunch() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> command = info.command();
        Optional<String[]> arguments = info.arguments();
        List<String> out = new ArrayList<>();
        if (command.isPresent()) {
            String exe = command.get();
            boolean java = exe.endsWith("java") || exe.endsWith("java.exe") || exe.endsWith("javaw.exe");
            if (!java) {
                // Native jpackage launcher (self-contained bundle): use it and its args verbatim.
                out.add(exe);
                arguments.ifPresent(a -> out.addAll(Arrays.asList(a)));
                return out;
            }
            String jar = currentJarPath();
            if (jar != null) {
                return List.of(exe, "-jar", jar);
            }
            out.add(exe);
            arguments.ifPresent(a -> out.addAll(Arrays.asList(a)));
            return out;
        }
        String jar = currentJarPath();
        String javaBin = System.getProperty("java.home", "") + "/bin/java";
        return jar != null ? List.of(javaBin, "-jar", jar) : List.of(javaBin);
    }

    private String currentJarPath() {
        try {
            Path p = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                return p.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {
            // running exploded (dev) or location unavailable
        }
        return null;
    }

    private int detectUid(OperatingSystem os) {
        if (os == OperatingSystem.WINDOWS || os == OperatingSystem.UNKNOWN) {
            return -1;
        }
        ExecResult r = execQuiet(List.of("id", "-u"));
        if (r != null && r.exit() == 0) {
            try {
                return Integer.parseInt(r.output().strip());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    private record ExecResult(int exit, String output) {
    }

    private ExecResult execQuiet(List<String> argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new ExecResult(p.exitValue(), out.strip());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
