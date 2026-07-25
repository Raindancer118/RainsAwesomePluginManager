package de.raindancer.apm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Makes {@code /apm restart} actually restart.
 *
 * <p>Paper's own mechanism is not enough on its own, which was proven the hard way: with the server
 * running inside a {@code screen} session, Paper reported <em>"Attempting to restart with
 * ./start.sh"</em> and the freshly spawned JVM was killed moments later, because it inherited the
 * dying session's process group. A restart script alone therefore does not make restarts reliable —
 * something has to outlive the JVM.
 *
 * <p>{@link org.bukkit.Server#restart()} does not re-launch the JVM itself. It execs the script
 * named by {@code settings.restart-script} in {@code spigot.yml} (default {@code ./start.sh}) and,
 * if that file does not exist, logs
 * <em>"Startup script './start.sh' does not exist! Stopping server."</em> and shuts the server down
 * instead. A plugin that offers a restart button therefore has to deal with this, otherwise the
 * button silently means "shut down" — which is how a test server ended up offline on 25.07.2026.
 *
 * <p>This service does two things: it reports whether a restart would really restart, and it can
 * generate a working script by reproducing how the current JVM was launched
 * ({@link ProcessHandle}). Nothing is written without an explicit request, existing files are
 * backed up first, and if the JVM does not expose its own command line the service says so rather
 * than writing a script that would not work.
 */
public final class RestartScriptService {

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** Environment variable the generated supervisor script exports, so APM can recognise it. */
    public static final String SUPERVISOR_MARKER = "APM_SUPERVISED";

    /** How a restart should be carried out on this server. */
    public enum Strategy {
        /**
         * Something outside the JVM restarts the process when it exits — our own supervisor loop,
         * systemd with {@code Restart=}, a Pterodactyl/Wings container. Here a plain shutdown is
         * both sufficient and more reliable than Paper's script exec, which would additionally
         * spawn a second server alongside the one the supervisor starts.
         */
        SUPERVISOR_RESTARTS_US,
        /** Nothing supervises us, so Paper's {@code restart-script} exec is the only option. */
        PAPER_RESTART_SCRIPT
    }

    /** Whether a restart would really come back up. */
    public enum Verdict {
        /** The configured script exists and is executable — a restart will restart. */
        READY,
        /** The script is missing, so {@code Server#restart()} would shut the server down. */
        MISSING,
        /** The script exists but is not executable, so the exec will fail. */
        NOT_EXECUTABLE,
        /**
         * A supervisor is watching this process and will start it again — a restart works
         * regardless of any script.
         */
        SUPERVISED
    }

    /**
     * @param verdict       what would happen on a restart
     * @param configuredPath the raw value from {@code spigot.yml}
     * @param resolvedPath  that value resolved against the server directory
     * @param canGenerate   whether this JVM exposes enough information to write a script
     */
    public record Status(Verdict verdict, String configuredPath, Path resolvedPath,
                         boolean canGenerate) {

        public boolean willRestart() {
            return verdict == Verdict.READY || verdict == Verdict.SUPERVISED;
        }

        /** @return how {@link RestartService} should perform the restart */
        public Strategy strategy() {
            return verdict == Verdict.SUPERVISED
                    ? Strategy.SUPERVISOR_RESTARTS_US
                    : Strategy.PAPER_RESTART_SCRIPT;
        }

        /** One-line explanation for a confirmation dialog. */
        public String detail() {
            return switch (verdict) {
                case SUPERVISED -> "This server runs under a supervisor that starts it again when "
                        + "it exits, so a restart reliably comes back up.";
                case READY -> "Restart script " + configuredPath + " is in place. Note that Paper "
                        + "spawns it as a child process, which does not survive on every setup — "
                        + "a supervisor loop is the dependable option.";
                case MISSING -> "There is no restart script at " + configuredPath
                        + ", so the server would SHUT DOWN and stay down.";
                case NOT_EXECUTABLE -> "The restart script " + configuredPath
                        + " exists but is not executable, so the restart would fail and the server "
                        + "would stay down.";
            };
        }
    }

    /** Outcome of generating a script. */
    public record GenerateResult(boolean success, String message, Path script) {
    }

    private final Path serverDirectory;
    private final Logger logger;
    private final java.util.function.Function<String, String> environment;

    public RestartScriptService(Path serverDirectory, Logger logger) {
        this(serverDirectory, logger, System::getenv);
    }

    /**
     * @param environment environment lookup, injectable so supervisor detection can be tested
     *                    without depending on whatever launched the test JVM
     */
    RestartScriptService(Path serverDirectory, Logger logger,
                         java.util.function.Function<String, String> environment) {
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.logger = logger;
        this.environment = environment;
    }

    /** Reads {@code spigot.yml} and checks the configured script. Cheap enough to call per render. */
    public Status status() {
        String configured = configuredScriptPath();
        Path resolved = serverDirectory.resolve(configured).normalize();
        boolean canGenerate = launchCommand().isPresent();

        if (supervisorDetected()) {
            return new Status(Verdict.SUPERVISED, configured, resolved, canGenerate);
        }
        if (!Files.isRegularFile(resolved)) {
            return new Status(Verdict.MISSING, configured, resolved, canGenerate);
        }
        if (!isWindows() && !Files.isExecutable(resolved)) {
            return new Status(Verdict.NOT_EXECUTABLE, configured, resolved, canGenerate);
        }
        return new Status(Verdict.READY, configured, resolved, canGenerate);
    }

    /**
     * Writes a restart script that reproduces how this server was started.
     *
     * <p>Only ever called from an explicit operator action. An existing file at the target is
     * copied aside first, so a hand-written script is never lost.
     *
     * @param makeExecutable set the executable bit (POSIX only)
     */
    public GenerateResult generate(boolean makeExecutable) {
        Optional<List<String>> launch = launchCommand();
        if (launch.isEmpty()) {
            return new GenerateResult(false,
                    "This JVM does not expose its own command line, so APM cannot reconstruct how "
                            + "the server was started. Write the start script by hand and point "
                            + "settings.restart-script in spigot.yml at it.", null);
        }

        Status status = status();
        Path target = status.resolvedPath();
        if (!target.startsWith(serverDirectory)) {
            return new GenerateResult(false,
                    "settings.restart-script points outside the server directory ("
                            + status.configuredPath() + "). Refusing to write there.", null);
        }

        String backupNote = "";
        if (Files.exists(target)) {
            Path backup = target.resolveSibling(target.getFileName()
                    + ".apm-backup-" + LocalDateTime.now().format(BACKUP_STAMP));
            try {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                backupNote = " The previous file was kept as " + backup.getFileName() + ".";
            } catch (IOException e) {
                logger.error("Could not back up the existing restart script {}", target, e);
                return new GenerateResult(false,
                        "There is already a file at " + status.configuredPath() + " and APM could "
                                + "not back it up, so it was left untouched.", null);
            }
        }

        String script = isWindows() ? renderBatch(launch.get()) : renderShell(launch.get());
        try {
            Files.writeString(target, script);
            if (makeExecutable && !isWindows()) {
                var permissions = new java.util.HashSet<>(Files.getPosixFilePermissions(target));
                permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
                Files.setPosixFilePermissions(target, permissions);
            }
        } catch (IOException | UnsupportedOperationException e) {
            logger.error("Could not write the restart script {}", target, e);
            return new GenerateResult(false,
                    "Could not write " + status.configuredPath() + ": " + e.getMessage(), null);
        }

        logger.info("Wrote a restart script to {} reproducing this server's launch command.", target);
        return new GenerateResult(true,
                "Wrote " + target.getFileName() + ", reproducing exactly how this server was "
                        + "started." + backupNote + " Restarts will now actually come back up — but "
                        + "note that the currently running process was NOT started through it, so "
                        + "verify the next restart.",
                target);
    }

    /**
     * Detects an external supervisor: something that starts the server again when this JVM exits.
     *
     * <p>Only signals that are specific enough to bet a production server on are accepted — our own
     * generated loop script's marker, and the variable a Pterodactyl/Wings container sets.
     *
     * <p>Notably <em>not</em> used: systemd's {@code INVOCATION_ID}. It is inherited by every child
     * process of any systemd unit, so a screen session launched from a desktop login session carries
     * it too. Treating that as "systemd will restart me" would make APM shut a server down and leave
     * it down. Operators running under systemd should set {@code APM_SUPERVISED=1} in the unit's
     * {@code Environment=} instead, which states the intent explicitly.
     */
    public boolean supervisorDetected() {
        return environment.apply(SUPERVISOR_MARKER) != null
                || environment.apply("P_SERVER_UUID") != null;
    }

    /** @return the raw {@code settings.restart-script} value, or Paper's default */
    private String configuredScriptPath() {
        Path spigotYml = serverDirectory.resolve("spigot.yml");
        String fallback = isWindows() ? "./start.bat" : "./start.sh";
        if (!Files.isRegularFile(spigotYml)) {
            return fallback;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(spigotYml.toFile());
            String configured = yaml.getString("settings.restart-script");
            return configured == null || configured.isBlank() ? fallback : configured.trim();
        } catch (RuntimeException e) {
            logger.warn("Could not read settings.restart-script from {}: {}",
                    spigotYml, e.getMessage());
            return fallback;
        }
    }

    /**
     * The command line this JVM was launched with.
     *
     * <p>{@link ProcessHandle.Info#arguments()} is documented as possibly unavailable, and on some
     * platforms and with some security settings it is. That is why the caller has to handle an
     * empty result instead of getting a half-built script.
     */
    private Optional<List<String>> launchCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> command = info.command();
        Optional<String[]> arguments = info.arguments();
        if (command.isEmpty() || arguments.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        parts.add(command.get());
        parts.addAll(List.of(arguments.get()));
        return Optional.of(parts);
    }

    private String renderShell(List<String> launch) {
        StringBuilder out = new StringBuilder();
        out.append("""
                #!/usr/bin/env bash
                # Supervisor start script generated by Rain's Awesome Plugin Manager.
                #
                # Why a loop instead of a plain java call: Paper's own restart mechanism execs this
                # script as a CHILD of the dying JVM, and on many setups — a screen or tmux session
                # in particular — that child is killed the moment the session tears down. The server
                # then stays offline even though a restart script exists.
                #
                # This script is the supervisor instead. When the JVM exits it simply starts it
                # again, which survives anything that kills the old process. APM recognises the
                # exported marker below and asks the server to stop rather than using Paper's exec,
                # so exactly one server comes back.
                #
                # Stopping for good: use /stop (or /apm restart is fine), then delete the flag file
                # printed below, or kill this script. Regenerate after changing JVM flags with
                # /apm restartscript create.
                export APM_SUPERVISED=1
                cd "$(dirname "$0")" || exit 1

                # A clean /stop should not be undone by the supervisor, so only a restart requested
                # through APM (which touches this flag) triggers a relaunch.
                FLAG=".apm-restart-requested"

                while true; do
                """);
        out.append("  java_exit=0\n  ");
        for (int i = 0; i < launch.size(); i++) {
            out.append(shellQuote(launch.get(i)));
            if (i < launch.size() - 1) {
                out.append(" \\\n    ");
            }
        }
        out.append("""
                 || java_exit=$?

                  if [ -f "$FLAG" ]; then
                    rm -f "$FLAG"
                    echo "[apm] restart requested — starting the server again"
                    sleep 2
                    continue
                  fi

                  echo "[apm] server exited (code ${java_exit}) without a restart request — done"
                  exit "$java_exit"
                done
                """);
        return out.toString();
    }

    private String renderBatch(List<String> launch) {
        StringBuilder out = new StringBuilder();
        out.append("""
                @echo off
                REM Restart script generated by Rain's Awesome Plugin Manager.
                REM Paper's settings.restart-script (spigot.yml) points here. Without it, a restart
                REM shuts the server down instead of restarting it.
                cd /d "%~dp0"

                """);
        for (String part : launch) {
            out.append('"').append(part.replace("\"", "\"\"")).append("\" ");
        }
        out.append("\n");
        return out.toString();
    }

    /** Single-quotes a shell word, which is safe for every character except the quote itself. */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
