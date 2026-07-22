package com.github.fkitsantas.pdfcompressionservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The menu-bar item is a convenience; the service must run without it. This pins
 * the safety contract: with no desktop session, {@link MenuBarTray#install()}
 * skips cleanly, never throws, and never touches the application context (so it
 * cannot shut the server down on a headless host).
 */
class MenuBarTrayTest {

    @Test
    void skipsCleanlyWhenThereIsNoDesktopSession() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        MenuBarTray tray = new MenuBarTray(context, new StandardEnvironment()) {
            @Override
            boolean desktopAvailable() {
                return false; // simulate a headless server / launchd daemon before login
            }
        };

        assertThat(tray.install()).isFalse();
        verifyNoInteractions(context);
    }
}
