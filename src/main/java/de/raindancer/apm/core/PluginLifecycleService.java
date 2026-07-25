package de.raindancer.apm.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.slf4j.Logger;

/**
 * Enable, disable, hot-load and reload operations against the running server.
 *
 * <p>Two honest caveats drive the design here.
 *
 * <ol>
 *   <li>Runtime <em>loading</em> of a plugin is not something Paper promises to support. APM
 *       always attempts it, catches {@link Throwable} — an unsupported operation surfaces as an
 *       exception, not a return value — and reports plainly when a restart is required instead
 *       of pretending the install already took effect.</li>
 *   <li>Runtime <em>unloading</em> is genuinely impossible: Java cannot retract loaded classes.
 *       {@code disablePlugin} stops a plugin's tasks and listeners, but its classes stay resident.
 *       APM therefore never claims to have unloaded anything; removing a jar always means the
 *       plugin is gone <em>after</em> the next restart.</li>
 * </ol>
 *
 * <p>All methods must run on the main server thread.
 */
public final class PluginLifecycleService {

    /**
     * @param success  whether the requested change took effect now
     * @param message  user facing explanation
     * @param needsRestart whether a restart is required to complete the operation
     */
    public record Outcome(boolean success, String message, boolean needsRestart) {

        public static Outcome ok(String message) {
            return new Outcome(true, message, false);
        }

        public static Outcome restart(String message) {
            return new Outcome(true, message, true);
        }

        public static Outcome fail(String message) {
            return new Outcome(false, message, false);
        }
    }

    /**
     * The explanation an operator needs when a plugin will not come back up.
     *
     * <p>This is not a defect in APM and not usually one in the other plugin either: many plugins
     * shut their thread pools, database connections and caches down in {@code onDisable} and never
     * expected {@code onEnable} to be called a second time on the same instance. Java offers no way
     * to give them a fresh one without a restart. Verified in practice against LuckPerms, which
     * throws {@code RejectedExecutionException} from a closed executor.
     */
    private static final String REENABLE_HINT =
            "Most plugins close their thread pools and database connections when disabled and "
                    + "cannot be started a second time within the same server session. Restart the "
                    + "server to bring it back up.";

    private final Server server;
    private final PluginRegistry registry;
    private final PendingActions pending;
    private final Logger logger;

    public PluginLifecycleService(Server server, PluginRegistry registry,
                                  PendingActions pending, Logger logger) {
        this.server = server;
        this.registry = registry;
        this.pending = pending;
        this.logger = logger;
    }

    /**
     * Switches a plugin on.
     *
     * <p>Un-parks the jar first when needed, then either enables the already loaded instance or
     * attempts a hot load.
     */
    public Outcome enable(ManagedPlugin plugin) {
        PluginManager manager = server.getPluginManager();

        if (plugin.state() == ManagedPlugin.State.ENABLED) {
            return Outcome.fail(plugin.name() + " is already enabled.");
        }

        if (plugin.state() == ManagedPlugin.State.PARKED) {
            Path parked = plugin.jar();
            Path active = PluginRegistry.activePathFor(parked);
            pending.cancelFor(active);
            if (!pending.tryNowOrDefer(new PendingActions.Action(
                    PendingActions.Kind.RENAME, parked, active, "un-parking " + plugin.name()))) {
                return Outcome.restart(plugin.name() + " will be un-parked during shutdown and "
                        + "loaded on the next start.");
            }
            return hotLoad(active)
                    .map(loaded -> Outcome.ok(plugin.name() + " was un-parked and enabled."))
                    .orElseGet(() -> Outcome.restart(plugin.name()
                            + " was un-parked. It will load on the next server restart."));
        }

        if (plugin.live().isPresent()) {
            Plugin live = plugin.live().get();
            try {
                manager.enablePlugin(live);
            } catch (Throwable t) {
                logger.error("Enabling {} failed", plugin.name(), t);
                return Outcome.fail("Enabling " + plugin.name() + " failed: " + describe(t)
                        + " " + REENABLE_HINT);
            }
            return live.isEnabled()
                    ? Outcome.ok(plugin.name() + " is now enabled.")
                    : new Outcome(false, plugin.name() + " refused to start again. " + REENABLE_HINT,
                            true);
        }

        // NOT_LOADED: the jar is there but the server never loaded it.
        if (!plugin.hasJar()) {
            return Outcome.fail("APM cannot find a jar for " + plugin.name() + ".");
        }
        return hotLoad(plugin.jar())
                .map(loaded -> Outcome.ok(plugin.name() + " was loaded and enabled."))
                .orElseGet(() -> Outcome.restart(plugin.name()
                        + " could not be loaded at runtime. Restart the server to activate it."));
    }

    /**
     * Switches a plugin off.
     *
     * @param persistent when true the jar is also parked so it stays off across restarts
     */
    public Outcome disable(ManagedPlugin plugin, boolean persistent) {
        if (plugin.name().equalsIgnoreCase("APM")) {
            return Outcome.fail("APM will not disable itself — you would lose the only way to "
                    + "turn it back on without editing files by hand.");
        }

        List<ManagedPlugin> dependents = registry.dependentsOf(plugin.name()).stream()
                .filter(dependent -> dependent.state() == ManagedPlugin.State.ENABLED)
                .toList();

        StringBuilder message = new StringBuilder();
        if (plugin.live().isPresent() && plugin.live().get().isEnabled()) {
            try {
                server.getPluginManager().disablePlugin(plugin.live().get());
            } catch (Throwable t) {
                logger.error("Disabling {} failed", plugin.name(), t);
                return Outcome.fail("Disabling " + plugin.name() + " failed: " + describe(t));
            }
            message.append(plugin.name()).append(" was disabled.");
        } else if (plugin.state() == ManagedPlugin.State.PARKED) {
            return Outcome.fail(plugin.name() + " is already disabled persistently.");
        } else {
            message.append(plugin.name()).append(" was not running.");
        }

        if (persistent && plugin.hasJar()
                && !plugin.jar().getFileName().toString().endsWith(PluginRegistry.PARKED_SUFFIX)) {
            Path parked = PluginRegistry.parkedPathFor(plugin.jar());
            boolean now = pending.tryNowOrDefer(new PendingActions.Action(
                    PendingActions.Kind.RENAME, plugin.jar(), parked, "parking " + plugin.name()));
            message.append(now
                    ? " Its jar is parked, so it stays off across restarts."
                    : " Its jar is in use and will be parked during shutdown.");
        }

        if (!dependents.isEmpty()) {
            message.append(" Heads up: ")
                    .append(dependents.stream().map(ManagedPlugin::name).reduce((a, b) -> a + ", " + b).orElse(""))
                    .append(" depend(s) on it and may now misbehave.");
        }
        return Outcome.ok(message.toString());
    }

    /**
     * Disables and re-enables a plugin.
     *
     * <p>Two things this is not. It is not a class reload — the plugin's code stays as it was when
     * the server loaded it, so a new jar needs a restart. And it is not reliable for every plugin:
     * see {@link #REENABLE_HINT}. It works well for plugins whose {@code onEnable} only reads
     * config; it fails for plugins that tear down executors on disable. APM attempts it and reports
     * what actually happened.
     */
    public Outcome reload(ManagedPlugin plugin) {
        if (plugin.name().equalsIgnoreCase("APM")) {
            return Outcome.fail("APM cannot reload itself. Use /apm reloadconfig for its settings, "
                    + "or restart the server.");
        }
        if (plugin.live().isEmpty()) {
            return Outcome.fail(plugin.name() + " is not loaded, so there is nothing to reload. "
                    + "Use /apm enable " + plugin.name() + " instead.");
        }

        Plugin live = plugin.live().get();
        PluginManager manager = server.getPluginManager();
        try {
            if (live.isEnabled()) {
                manager.disablePlugin(live);
            }
            manager.enablePlugin(live);
        } catch (Throwable t) {
            logger.error("Reloading {} failed", plugin.name(), t);
            return new Outcome(false, "Reloading " + plugin.name() + " failed: " + describe(t)
                    + " It is now stopped. " + REENABLE_HINT, true);
        }

        return live.isEnabled()
                ? Outcome.ok(plugin.name() + " was disabled and enabled again. Note that this "
                    + "re-runs its startup logic; it does not load a changed jar.")
                : new Outcome(false, plugin.name() + " did not come back up and is now stopped. "
                        + REENABLE_HINT, true);
    }

    /**
     * Best-effort runtime load of a jar that is sitting in the plugins folder.
     *
     * <p>This necessarily runs on the main thread: {@code loadPlugin} and {@code enablePlugin} are
     * main-thread-only API, and a plugin's {@code onEnable} routinely opens a database or reads
     * config files. The server therefore <em>stops ticking</em> for as long as that takes — for a
     * heavyweight plugin that is seconds, long enough for Paper's watchdog to print a thread dump.
     * That dump is a symptom of this call, not a bug, so it is announced before it can happen
     * instead of leaving an operator to decode a stack trace.
     *
     * @return the loaded plugin, or empty when the server would not have it
     */
    public Optional<Plugin> hotLoad(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        logger.info("Loading {} at runtime. The server will pause until the plugin has started; "
                        + "if that takes over 10 seconds Paper's watchdog logs a thread dump, which "
                        + "in this situation is expected and harmless.",
                jar.getFileName());
        long startedAt = System.nanoTime();
        try {
            Plugin loaded = server.getPluginManager().loadPlugin(jar.toFile());
            if (loaded == null) {
                return Optional.empty();
            }
            server.getPluginManager().enablePlugin(loaded);
            if (loaded.isEnabled()) {
                logger.info("{} started in {} ms.", loaded.getName(),
                        (System.nanoTime() - startedAt) / 1_000_000L);
                return Optional.of(loaded);
            }
            return Optional.empty();
        } catch (Throwable t) {
            // Paper does not guarantee runtime loading; UnsupportedOperationException and
            // assorted linkage errors are expected outcomes here, not bugs.
            logger.info("Runtime load of {} was not possible ({}). A restart will pick it up.",
                    jar.getFileName(), describe(t));
            return Optional.empty();
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
