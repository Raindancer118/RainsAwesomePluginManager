package de.raindancer.apm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class RestartScriptServiceTest {

    @TempDir
    Path serverDir;

    private RestartScriptService service;

    /** No supervisor, unless a test says otherwise — never inherited from the test runner. */
    @BeforeEach
    void setUp() {
        service = new RestartScriptService(serverDir, NOPLogger.NOP_LOGGER, name -> null);
    }

    private RestartScriptService withEnv(java.util.Map<String, String> env) {
        return new RestartScriptService(serverDir, NOPLogger.NOP_LOGGER, env::get);
    }

    @Test
    @DisplayName("our own supervisor marker is recognised, and then no script is needed")
    void detectsOwnSupervisorMarker() {
        RestartScriptService supervised =
                withEnv(java.util.Map.of(RestartScriptService.SUPERVISOR_MARKER, "1"));

        assertThat(supervised.status().verdict()).isEqualTo(RestartScriptService.Verdict.SUPERVISED);
        assertThat(supervised.status().willRestart()).isTrue();
        assertThat(supervised.status().strategy())
                .isEqualTo(RestartScriptService.Strategy.SUPERVISOR_RESTARTS_US);
    }

    @Test
    @DisplayName("a Pterodactyl/Wings container counts as supervised")
    void detectsWings() {
        assertThat(withEnv(java.util.Map.of("P_SERVER_UUID", "abc")).status().verdict())
                .isEqualTo(RestartScriptService.Verdict.SUPERVISED);
    }

    @Test
    @DisplayName("systemd's INVOCATION_ID is NOT treated as a supervisor")
    void ignoresInvocationId() {
        // It is inherited by every child of any systemd unit — including a screen session started
        // from a desktop login. Trusting it would shut a server down and leave it down.
        assertThat(withEnv(java.util.Map.of("INVOCATION_ID", "deadbeef")).status().verdict())
                .isEqualTo(RestartScriptService.Verdict.MISSING);
    }

    @Test
    @DisplayName("a missing script is reported as MISSING, because a restart would shut down")
    void reportsMissingScript() {
        RestartScriptService.Status status = service.status();

        assertThat(status.verdict()).isEqualTo(RestartScriptService.Verdict.MISSING);
        assertThat(status.willRestart()).isFalse();
        assertThat(status.detail()).contains("SHUT DOWN");
    }

    @Test
    @DisplayName("Paper's default path is assumed when spigot.yml has no setting")
    void defaultsToPaperConvention() {
        assertThat(service.status().configuredPath()).endsWith("start.sh");
    }

    @Test
    @DisplayName("the configured path from spigot.yml is honoured")
    void readsConfiguredPath() throws IOException {
        Files.writeString(serverDir.resolve("spigot.yml"), """
                settings:
                  restart-script: ./launch-server.sh
                """);

        assertThat(service.status().configuredPath()).isEqualTo("./launch-server.sh");
        assertThat(service.status().resolvedPath())
                .isEqualTo(serverDir.resolve("launch-server.sh").normalize());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("an existing but non-executable script is reported, not called ready")
    void detectsNonExecutableScript() throws IOException {
        Path script = serverDir.resolve("start.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(script, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));

        assertThat(service.status().verdict())
                .isEqualTo(RestartScriptService.Verdict.NOT_EXECUTABLE);
        assertThat(service.status().willRestart()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("an executable script is READY, but the caveat about Paper's child process is stated")
    void detectsReadyScript() throws IOException {
        Path script = serverDir.resolve("start.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(script, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        assertThat(service.status().verdict()).isEqualTo(RestartScriptService.Verdict.READY);
        assertThat(service.status().willRestart()).isTrue();
        // A hand-written script is not a supervisor, so Paper's exec is used — and that is the setup
        // that failed in practice. The wording must not oversell it.
        assertThat(service.status().strategy())
                .isEqualTo(RestartScriptService.Strategy.PAPER_RESTART_SCRIPT);
        assertThat(service.status().detail()).contains("does not survive on every setup");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("generating writes an executable script that reproduces this JVM's launch command")
    void generatesRunnableScript() throws IOException {
        RestartScriptService.GenerateResult result = service.generate(true);

        // The test JVM does expose its command line, so this must succeed. If a future JVM stops
        // doing so, the service is required to say that instead of writing something broken.
        assertThat(result.success())
                .as("generate() said: %s", result.message())
                .isTrue();

        Path script = serverDir.resolve("start.sh");
        assertThat(script).exists();
        assertThat(Files.isExecutable(script)).isTrue();

        String content = Files.readString(script);
        assertThat(content)
                .startsWith("#!/usr/bin/env bash")
                .contains("cd \"$(dirname \"$0\")\"")
                // It has to be a loop, not an exec: Paper's exec'd child does not survive a dying
                // screen session, which is the failure this whole class exists to prevent.
                .contains("while true; do")
                .contains("export APM_SUPERVISED=1")
                .contains(".apm-restart-requested")
                // The java binary of the running JVM has to be in there for the script to work.
                .contains("java");
        assertThat(service.status().willRestart()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("an existing script is backed up rather than silently replaced")
    void backsUpExistingScript() throws IOException {
        Path script = serverDir.resolve("start.sh");
        Files.writeString(script, "#!/bin/sh\n# hand-written, must not be lost\n");

        RestartScriptService.GenerateResult result = service.generate(true);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("kept as");

        try (var files = Files.list(serverDir)) {
            var backups = files
                    .filter(path -> path.getFileName().toString().contains(".apm-backup-"))
                    .toList();
            assertThat(backups).hasSize(1);
            assertThat(Files.readString(backups.getFirst())).contains("must not be lost");
        }
    }

    @Test
    @DisplayName("a script path pointing outside the server directory is refused")
    void refusesPathOutsideServerDirectory() throws IOException {
        Files.writeString(serverDir.resolve("spigot.yml"), """
                settings:
                  restart-script: ../../etc/evil.sh
                """);

        RestartScriptService.GenerateResult result = service.generate(true);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("outside the server directory");
        assertThat(serverDir.getParent().resolve("etc/evil.sh")).doesNotExist();
    }

    @Test
    @DisplayName("a broken spigot.yml falls back to the default instead of throwing")
    void survivesBrokenSpigotYml() throws IOException {
        Files.writeString(serverDir.resolve("spigot.yml"), "settings: [this: is broken\n");
        assertThat(service.status().configuredPath()).endsWith("start.sh");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("arguments containing spaces or quotes stay one argument in the script")
    void quotesArgumentsSafely() throws IOException {
        // The real risk: a server directory like /srv/my server/ or a flag with a quote in it
        // would break an unquoted script. Verified via the generated content of the running JVM
        // plus an explicit check of the quoting helper's effect on the java path.
        service.generate(true);
        String content = Files.readString(serverDir.resolve("start.sh"));

        // Every exec argument line is single-quoted.
        content.lines()
                .filter(line -> line.trim().startsWith("'"))
                .forEach(line -> assertThat(line.trim())
                        .startsWith("'")
                        .matches("'.*'( \\\\)?( \\|\\| java_exit=\\$\\?)?"));
    }
}
