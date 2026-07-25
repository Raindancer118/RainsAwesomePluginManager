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

    private final Plugin plugin;
    private final int defaultCountdown;
    private BukkitTask task;

    public RestartService(Plugin plugin, int defaultCountdown) {
        this.plugin = plugin;
        this.defaultCountdown = defaultCountdown;
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
            plugin.getServer().restart();
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
                plugin.getServer().restart();
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
