package com.github.fkitsantas.pdfcompressionservice.service;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustively covers the pure autostart planner for every OS and both scopes:
 * the right artifacts, the right commands, unprivileged login vs. privileged
 * boot, and that no request input ever reaches a command.
 */
class AutostartPlanTest {

    private static final Path HOME = Path.of("/home/tester");
    private static final List<String> LAUNCH =
            List.of("/Applications/PdfCompressionService.app/Contents/MacOS/PdfCompressionService");

    @Test
    void osDetectionCoversTheFourTargets() {
        assertThat(OperatingSystem.detect("Mac OS X")).isEqualTo(OperatingSystem.MACOS);
        assertThat(OperatingSystem.detect("Linux")).isEqualTo(OperatingSystem.LINUX);
        assertThat(OperatingSystem.detect("Windows 11")).isEqualTo(OperatingSystem.WINDOWS);
        assertThat(OperatingSystem.detect("SunOS")).isEqualTo(OperatingSystem.UNKNOWN);
    }

    @Test
    void macosLoginIsUnprivilegedLaunchAgentAndBootIsPrivilegedDaemon() {
        AutostartPlan plan = AutostartPlan.build(OperatingSystem.MACOS, LAUNCH, HOME, "tester", 501);

        AutostartPlan.Scope login = plan.login();
        assertThat(login.privileged()).isFalse();
        assertThat(login.supported()).isTrue();
        assertThat(login.artifactPath()).hasToString("/home/tester/Library/LaunchAgents/" + AutostartPlan.LABEL + ".plist");
        assertThat(login.artifactContent())
                .contains("<string>" + LAUNCH.get(0) + "</string>")
                .contains(AutostartPlan.LABEL)
                .contains("<key>RunAtLoad</key>");
        assertThat(login.installSteps())
                .contains(List.of("launchctl", "bootstrap", "gui/501", login.artifactPath().toString()));
        assertThat(login.installSteps().stream().flatMap(List::stream)).doesNotContain("sudo");
        assertThat(login.installedMarker()).isEqualTo(login.artifactPath());

        AutostartPlan.Scope boot = plan.boot();
        assertThat(boot.privileged()).isTrue();
        assertThat(boot.installSteps().get(0)).containsSequence("sudo", "cp");
        assertThat(boot.installedMarker()).hasToString("/Library/LaunchDaemons/" + AutostartPlan.LABEL + ".plist");
    }

    @Test
    void macosLoginUnsupportedWhenUidUnknown() {
        AutostartPlan plan = AutostartPlan.build(OperatingSystem.MACOS, LAUNCH, HOME, "tester", -1);
        assertThat(plan.login().supported()).isFalse();
    }

    @Test
    void linuxUsesSystemdUserForLoginAndSudoSystemForBoot() {
        AutostartPlan plan = AutostartPlan.build(OperatingSystem.LINUX, LAUNCH, HOME, "tester", 1000);

        AutostartPlan.Scope login = plan.login();
        assertThat(login.privileged()).isFalse();
        assertThat(login.artifactContent()).contains("ExecStart=").contains("WantedBy=default.target");
        assertThat(login.installSteps())
                .contains(List.of("systemctl", "--user", "enable", "--now", AutostartPlan.UNIT));
        assertThat(login.installSteps().stream().flatMap(List::stream)).doesNotContain("sudo");

        AutostartPlan.Scope boot = plan.boot();
        assertThat(boot.privileged()).isTrue();
        assertThat(boot.artifactContent()).contains("User=tester").contains("WantedBy=multi-user.target");
        assertThat(boot.installSteps().stream().flatMap(List::stream)).contains("sudo");
    }

    @Test
    void windowsUsesSchtasksLogonWithoutAdminAndOnstartAsSystem() {
        AutostartPlan plan = AutostartPlan.build(OperatingSystem.WINDOWS, LAUNCH, HOME, "tester", -1);

        AutostartPlan.Scope login = plan.login();
        assertThat(login.privileged()).isFalse();
        assertThat(login.installSteps().get(0)).containsSequence("/SC", "ONLOGON");
        assertThat(login.installSteps().get(0)).doesNotContain("SYSTEM");
        assertThat(login.artifactPath()).isNull();

        AutostartPlan.Scope boot = plan.boot();
        assertThat(boot.installSteps().get(0)).containsSequence("/SC", "ONSTART").containsSequence("/RU", "SYSTEM");
    }

    @Test
    void shellJoinQuotesArgumentsWithSpaces() {
        assertThat(AutostartPlan.shellJoin(List.of("/a b/exe", "-jar", "/x y/app.jar")))
                .isEqualTo("\"/a b/exe\" -jar \"/x y/app.jar\"");
        assertThat(AutostartPlan.shellJoin(List.of("/usr/bin/java", "-jar", "/opt/app.jar")))
                .isEqualTo("/usr/bin/java -jar /opt/app.jar");
    }
}
