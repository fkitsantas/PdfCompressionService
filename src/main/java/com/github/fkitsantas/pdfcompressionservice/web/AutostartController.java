package com.github.fkitsantas.pdfcompressionservice.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.fkitsantas.pdfcompressionservice.service.AutostartManager;
import com.github.fkitsantas.pdfcompressionservice.service.AutostartStatus;

/**
 * Endpoints backing the "Run automatically" panel in the web UI.
 *
 * <p>{@code GET /service/autostart} reports whether the service is set to start
 * automatically and returns the exact artifacts/commands involved. The
 * unprivileged login scope can be toggled directly ({@code POST}/{@code DELETE
 * /service/autostart/login}); the privileged boot scope is never executed here -
 * its commands are only shown for the user to run in their own terminal, so the
 * app never handles an administrator password.
 */
@RestController
public class AutostartController {

    private final AutostartManager autostart;

    public AutostartController(AutostartManager autostart) {
        this.autostart = autostart;
    }

    /** Returns the current autostart status, launch command, blockers and per-scope commands. */
    @GetMapping("/service/autostart")
    public AutostartStatus status() {
        return autostart.status();
    }

    /** Installs the per-user "start at login" autostart entry (no admin privileges needed). */
    @PostMapping("/service/autostart/login")
    public AutostartStatus enableLogin() {
        autostart.installLogin();
        return autostart.status();
    }

    /** Removes the per-user "start at login" autostart entry. */
    @DeleteMapping("/service/autostart/login")
    public AutostartStatus disableLogin() {
        autostart.uninstallLogin();
        return autostart.status();
    }
}
