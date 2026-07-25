package de.raindancer.apm.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.raindancer.apm.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Countdown-then-restart, with a broadcast so players are not dropped without warning.
 *
 * <p>{@link Server#restart()} only actually restarts the process when the server was started
 * through a wrapper script that re-launches the JVM — Paper's {@code restart-script} setting. On a
 * plain {@code java -jar} setup it behaves as a shutdown. APM cannot detect the difference
 * reliably, so it says so up front rather than promising a restart it cannot guarantee.
 *
 * <p>All methods must run on the main server thread.
 */
public final class RestartService {

    /**
     * Touched to tell the supervisor script that this exit is a restart, not a deliberate stop.
     * Keeps {@code /stop} meaning stop while {@code /apm restart} means come back.
     */
    static final String RESTART_FLAG_FILE = ".apm-restart-requested";

    private final Plugin plugin;
    private final int defaultCountdown;
    private final RestartScriptService scripts;
    private BukkitTask task;

    public RestartService(Plugin plugin, int defaultCountdown, RestartScriptService scripts) {
        this.plugin = plugin;
        this.defaultCountdown = defaultCountdown;
        this.scripts = scripts;
    }

    /** @return the countdown length from config.yml, for screens that want to say it up front */
    public int configuredCountdown() {
        return defaultCountdown;
    }

    public boolean isCountingDown() {
        return task != null && !task.isCancelled();
    }

    /**
     * Starts the countdown.
     *
     * @param seconds countdown length; a value below zero uses the configured default
     * @param reason  shown in the broadcast, may be null
     * @return false if a countdown is already running
     */
    public boolean start(int seconds, String reason) {
        if (isCountingDown()) {
            return false;
        }
        int total = seconds < 0 ? defaultCountdown : seconds;

        if (total == 0) {
            announce(reason, 0);
            performRestart();
            return true;
        }

        announce(reason, total);
        AtomicInteger remaining = new AtomicInteger(total);
        // Ticks, not wall clock: a lagging server counting down in real time would announce "1"
        // and then sit there. Aligning to ticks keeps the message and the action consistent.
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int left = remaining.decrementAndGet();
            if (left <= 0) {
                cancelTask();
                performRestart();
                return;
            }
            if (left <= 5 || left % 10 == 0) {
                broadcast(Msg.warn("Restarting in <" + Msg.BAD + "><n></" + Msg.BAD + "> second<s>…",
                        Msg.arg("n", String.valueOf(left)),
                        Msg.arg("s", left == 1 ? "" : "s")));
            }
        }, 20L, 20L);
        return true;
    }

    /**
     * Carries out the restart with whichever mechanism actually works on this server.
     *
     * <p>Under a supervisor, {@code Server#restart()} would be wrong twice over: Paper's exec'd
     * child does not reliably survive the JVM it was spawned from, and if it did, the supervisor
     * would start a second server next to it. A plain shutdown plus a flag file is both simpler and
     * verifiable.
     */
    private void performRestart() {
        RestartScriptService.Status status = scripts.status();
        if (status.strategy() == RestartScriptService.Strategy.SUPERVISOR_RESTARTS_US) {
            if (!markRestartRequested()) {
                plugin.getSLF4JLogger().warn("Could not write the restart flag file, so the "
                        + "supervisor may treat this as a deliberate stop. Falling back to Paper's "
                        + "restart mechanism.");
                plugin.getServer().restart();
                return;
            }
            plugin.getSLF4JLogger().info(
                    "Stopping for a supervised restart — the supervisor will start the server again.");
            plugin.getServer().shutdown();
            return;
        }
        plugin.getServer().restart();
    }

    /** @return whether the flag the supervisor script looks for could be created */
    private boolean markRestartRequested() {
        // Only meaningful for our own generated script; systemd and Wings restart unconditionally,
        // and for them a stray flag file is harmless.
        java.nio.file.Path flag = plugin.getServer().getPluginsFolder().toPath()
                .toAbsolutePath().normalize().getParent().resolve(RESTART_FLAG_FILE);
        try {
            java.nio.file.Files.writeString(flag,
                    "Written by APM to tell the supervisor script that the next exit is a restart.\n");
            return true;
        } catch (java.io.IOException e) {
            plugin.getSLF4JLogger().error("Could not write {}: {}", flag, e.getMessage());
            return false;
        }
    }

    /** @return false if nothing was running */
    public boolean cancel() {
        if (!isCountingDown()) {
            return false;
        }
        cancelTask();
        broadcast(Msg.success("The scheduled restart was cancelled."));
        return true;
    }

    /** Stops the timer without touching the server; used when APM is disabled. */
    public void shutdown() {
        cancelTask();
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void announce(String reason, int seconds) {
        List<Component> lines = List.of(
                Msg.raw(""),
                seconds == 0
                        ? Msg.warn("<bold>The server is restarting now.</bold>")
                        : Msg.warn("<bold>The server will restart in <n> seconds.</bold>",
                                Msg.arg("n", String.valueOf(seconds))),
                reason == null || reason.isBlank()
                        ? Msg.raw("")
                        : Msg.info("Reason: <reason>", Msg.arg("reason", reason)));
        lines.forEach(this::broadcast);
    }

    private void broadcast(Component component) {
        plugin.getServer().broadcast(component);
    }
}
