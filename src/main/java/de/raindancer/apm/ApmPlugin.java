package de.raindancer.apm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.raindancer.apm.command.ApmCommand;
import de.raindancer.apm.command.ChatPromptListener;
import de.raindancer.apm.core.ApmConfig;
import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.ConfigEditService;
import de.raindancer.apm.core.InstallDatabase;
import de.raindancer.apm.core.InstallService;
import de.raindancer.apm.core.PendingActions;
import de.raindancer.apm.core.PluginLifecycleService;
import de.raindancer.apm.core.PluginRegistry;
import de.raindancer.apm.core.RestartService;
import de.raindancer.apm.gui.MenuManager;
import de.raindancer.apm.util.Banner;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Rain's Awesome Plugin Manager — {@code apt} for a Paper server.
 *
 * <p>Wires the pieces together and owns the two things that must not leak: the worker thread used
 * for network I/O, and the shutdown hook that finishes deferred file operations after Paper has
 * closed the plugin class loaders.
 */
public final class ApmPlugin extends JavaPlugin {

    /** The one permission that gates everything. Defaults to op only. */
    public static final String PERMISSION = "apm.admin";

    private volatile ApmConfig config;
    private ExecutorService worker;
    private PendingActions pending;
    private Thread shutdownHook;
    private MenuManager menus;
    private RestartService restarts;
    private String userAgent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = ApmConfig.from(getConfig());
        this.userAgent = "RainsAwesomePluginManager/" + getPluginMeta().getVersion()
                + " (Paper " + serverVersion() + "; +https://github.com/Raindancer118)";

        // One thread is deliberate: installs are rare, and serialising them removes any chance of
        // two downloads racing for the same target file name.
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "apm-worker");
            thread.setDaemon(true);
            return thread;
        });

        Path dataFolder = getDataFolder().toPath();
        Path cacheFolder = cacheFolder();
        try {
            Files.createDirectories(cacheFolder);
        } catch (IOException e) {
            getSLF4JLogger().error("Could not create APM's cache folder {} — installs will fail.",
                    cacheFolder, e);
        }

        this.pending = new PendingActions(dataFolder.resolve("pending-actions.yml"), getSLF4JLogger());
        pending.load();
        // Drain immediately: nothing this session has opened these jars yet, so a delete that was
        // impossible before the last shutdown very likely succeeds now.
        pending.drain();
        this.shutdownHook = pending.installShutdownHook();

        InstallDatabase database = new InstallDatabase(
                dataFolder.resolve("installed.yml"), getSLF4JLogger());
        database.load();

        PluginRegistry registry = new PluginRegistry(getServer(), getSLF4JLogger());
        PluginLifecycleService lifecycle =
                new PluginLifecycleService(getServer(), registry, pending, getSLF4JLogger());
        InstallService installs = new InstallService(
                registry, lifecycle, database, pending, cacheFolder, getSLF4JLogger());
        this.restarts = new RestartService(this, config.restartCountdownSeconds());
        ConfigEditService configs = new ConfigEditService(registry::pluginsFolder, getSLF4JLogger());

        ApmService service = new ApmService(
                this, registry, lifecycle, installs, database, pending, restarts, configs);

        this.menus = new MenuManager(this);
        menus.register();
        getServer().getPluginManager().registerEvents(new ChatPromptListener(menus), this);

        ApmCommand command = new ApmCommand(this, service, menus);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        "apm",
                        "Manage this server's plugins — install, update, enable, disable, remove.",
                        java.util.List.of("plugins-manager", "pluginmanager"),
                        command));

        // A self-check that actually proves the plugin works, rather than just that it loaded.
        runStartupSelfCheck(registry, service);
    }

    @Override
    public void onDisable() {
        if (restarts != null) {
            restarts.shutdown();
        }
        if (menus != null) {
            menus.shutdown();
        }
        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    getSLF4JLogger().warn("APM's worker thread did not stop within 5 seconds.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (pending != null) {
            // Try once here, where the log is still usable; the shutdown hook is the last resort
            // and runs after Paper has closed the plugin class loaders.
            pending.drain();
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                // Already removed from the JVM's list, so run the final attempt inline.
                pending.drain();
            } catch (IllegalStateException ignored) {
                // Shutdown already in progress — the hook is running or has run.
            }
        }
        if (pending != null) {
            Banner.printShutdown(getComponentLogger(), pending.snapshot().size());
        }
    }

    /**
     * Verifies at startup that APM's own machinery actually works: it must be able to read the
     * plugins folder, parse its own descriptor out of its own jar, and find itself in the registry.
     * A failure here is logged loudly because it means every later command would misbehave.
     *
     * <p>The result feeds the banner, so the operator sees the verdict rather than a claim.
     */
    private void runStartupSelfCheck(PluginRegistry registry, ApmService service) {
        boolean healthy = false;
        int discovered = 0;
        try {
            var snapshot = registry.snapshot();
            discovered = snapshot.size();
            var self = snapshot.stream()
                    .filter(managed -> managed.name().equals(getName()))
                    .findFirst();

            if (self.isEmpty()) {
                getSLF4JLogger().error("Self-check FAILED: APM cannot see itself in the plugin "
                        + "registry. Plugin discovery is broken; commands will not work correctly.");
            } else if (self.get().meta().isEmpty()) {
                getSLF4JLogger().error("Self-check FAILED: APM could not read its own descriptor "
                        + "from its own jar. Jar inspection is broken.");
            } else {
                if (!service.compatibilityOf(self.get()).isCompatible()) {
                    getSLF4JLogger().warn("Self-check: APM's own api-version does not satisfy this "
                            + "server — the version comparison may be misjudging other plugins too.");
                }
                healthy = true;
            }
        } catch (RuntimeException e) {
            getSLF4JLogger().error("Self-check FAILED with an exception — APM is not healthy.", e);
        }

        Banner.print(getComponentLogger(), getPluginMeta().getVersion(), serverVersion(),
                discovered, pending.snapshot().size(), healthy);
    }

    // --- shared services -------------------------------------------------------------------

    /** The current configuration snapshot. Safe to read from any thread. */
    public ApmConfig config() {
        return config;
    }

    /** Re-reads {@code config.yml} and swaps the snapshot atomically. */
    public void reloadApmConfig() {
        reloadConfig();
        this.config = ApmConfig.from(getConfig());
    }

    public String userAgent() {
        return userAgent;
    }

    public Path cacheFolder() {
        return getDataFolder().toPath().resolve("cache");
    }

    /** @return the running server's Minecraft version, e.g. {@code 26.1.2} */
    public String serverVersion() {
        return Bukkit.getMinecraftVersion();
    }

    /** Runs {@code task} on APM's worker thread. */
    public void runAsync(Runnable task) {
        if (worker == null || worker.isShutdown()) {
            getSLF4JLogger().warn("Ignoring a background task because APM is shutting down.");
            return;
        }
        worker.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                getSLF4JLogger().error("A background task failed", e);
            }
        });
    }

    /**
     * Runs {@code task} on the main server thread, or immediately when already there.
     *
     * <p>Every callback that touches the plugin manager or an inventory goes through here.
     */
    public void runOnMain(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        if (getServer().isPrimaryThread()) {
            task.run();
            return;
        }
        getServer().getScheduler().runTask(this, task);
    }

    /**
     * The single authorisation check.
     *
     * <p>Operators and the console pass; everyone else needs {@code apm.admin} granted explicitly.
     * The permission's default is {@code op}, so a fresh install is op-only without any setup.
     */
    public boolean hasAdminPermission(CommandSender sender) {
        if (!(sender instanceof Player)) {
            // Console, RCON and command blocks are already trusted by the server itself.
            return true;
        }
        return sender.isOp() || sender.hasPermission(PERMISSION);
    }
}
