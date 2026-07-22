package com.github.fkitsantas.pdfcompressionservice.ui;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;
import java.net.URI;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Puts a small item in the macOS menu bar (the status area, top-right by the
 * clock) so the packaged app can be opened and, crucially, <b>quit</b> without a
 * Dock window.
 *
 * <p>The macOS bundle ships as an {@code LSUIElement} agent rather than a
 * foreground Dock app: a windowless Spring Boot server registered as a normal
 * Dock application never services an AppKit event loop, so macOS keeps flagging
 * it "Application Not Responding". As an agent it is not a foreground app and
 * never gets that flag, and this menu-bar item gives the user a way to stop it.
 *
 * <p>It is a best-effort convenience implemented with pure-Java {@link SystemTray}
 * and nothing here is required for the service to work: when there is no desktop
 * session (a launchd daemon started before login, a headless Linux server, CI)
 * it silently does nothing and the web service runs exactly as before. That is
 * what lets the same bundle both show a menu-bar item for an interactive user and
 * run unattended on a server after a reboot.
 */
@Component
@ConditionalOnProperty(name = "pcs.menu-bar.enabled", havingValue = "true", matchIfMissing = true)
class MenuBarTray {

    private static final Logger log = LoggerFactory.getLogger(MenuBarTray.class);

    private final ConfigurableApplicationContext context;
    private final Environment environment;
    private volatile TrayIcon installed;

    MenuBarTray(ConfigurableApplicationContext context, Environment environment) {
        this.context = context;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        install();
    }

    /**
     * Installs the menu-bar item. Returns {@code true} if it was placed, {@code
     * false} when there is no desktop session (in which case the service simply
     * runs headless). Never throws: a UI convenience must not stop the server.
     */
    boolean install() {
        try {
            if (!desktopAvailable()) {
                log.debug("No desktop session (headless); running as a background service with no menu-bar item.");
                return false;
            }
            SystemTray tray = SystemTray.getSystemTray();

            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open PDF Compression Service");
            open.addActionListener(e -> openBrowser());
            MenuItem quit = new MenuItem("Quit PDF Compression Service");
            quit.addActionListener(e -> quit());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);

            TrayIcon icon = new TrayIcon(loadIcon(), "PDF Compression Service", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openBrowser());
            tray.add(icon);
            this.installed = icon;
            log.info("Menu-bar item installed; click it to open the web UI or to quit the service.");
            return true;
        } catch (Throwable t) {
            // Any AWT/tray failure (odd session state, no display) must leave the service running.
            log.debug("Could not install the menu-bar item; continuing headless: {}", t.toString());
            return false;
        }
    }

    /** Whether a usable desktop session with a system tray/menu bar is present. Overridable for tests. */
    boolean desktopAvailable() {
        return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
    }

    private Image loadIcon() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/static/PdfCompressionService.png")) {
            if (in == null) {
                throw new IllegalStateException("menu-bar icon resource not found");
            }
            return ImageIO.read(in);
        }
    }

    private void openBrowser() {
        try {
            String port = environment.getProperty("local.server.port",
                    environment.getProperty("server.port", "7777"));
            URI uri = URI.create("http://localhost:" + port + "/");
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception e) {
            log.debug("Could not open the browser: {}", e.toString());
        }
    }

    private void quit() {
        removeIcon();
        // Graceful shutdown of the Spring context, then exit the JVM.
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    @PreDestroy
    void removeIcon() {
        TrayIcon icon = this.installed;
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
            this.installed = null;
        }
    }
}
