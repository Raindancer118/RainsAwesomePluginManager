package de.raindancer.apm.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import de.raindancer.apm.ApmPlugin;
import de.raindancer.apm.source.DirectUrlSource;
import de.raindancer.apm.source.ModrinthSource;
import de.raindancer.apm.source.PluginSource;
import de.raindancer.apm.util.Downloader;
import de.raindancer.apm.version.CompatibilityCheck;

/**
 * The single entry point both the command layer and the GUI go through.
 *
 * <p>Everything a user can do exists exactly once, here. That is what makes "every command has a
 * GUI equivalent" true by construction rather than by discipline: the menus and the commands are
 * two thin front ends over the same methods.
 *
 * <p>Threading contract: methods named {@code …Async} return immediately and deliver their result
 * to the callback <em>on the main thread</em>. Everything else must be called from the main thread.
 */
public final class ApmService {

    /** Outcome of a staged install awaiting a compatibility decision. */
    public sealed interface PrepareResult {

        /** The jar is fine, or the operator allowed incompatible installs. */
        record Ready(InstallService.StagedInstall staged) implements PrepareResult {
        }

        /** The jar is not compatible; the caller must confirm before it is installed. */
        record NeedsConfirmation(InstallService.StagedInstall staged, String warning)
                implements PrepareResult {
        }

        /** Nothing was installed. */
        record Failed(String message) implements PrepareResult {
        }
    }

    private final ApmPlugin plugin;
    private final PluginRegistry registry;
    private final PluginLifecycleService lifecycle;
    private final InstallService installs;
    private final InstallDatabase database;
    private final PendingActions pending;
    private final RestartService restarts;
    private final ConfigEditService configs;

    public ApmService(ApmPlugin plugin,
                      PluginRegistry registry,
                      PluginLifecycleService lifecycle,
                      InstallService installs,
                      InstallDatabase database,
                      PendingActions pending,
                      RestartService restarts,
                      ConfigEditService configs) {
        this.plugin = plugin;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.installs = installs;
        this.database = database;
        this.pending = pending;
        this.restarts = restarts;
        this.configs = configs;
    }

    /** Editor for other plugins' YAML configuration files. */
    public ConfigEditService configs() {
        return configs;
    }

    // --- read side -------------------------------------------------------------------------

    public List<ManagedPlugin> list() {
        return registry.snapshot();
    }

    public Optional<ManagedPlugin> find(String name) {
        return registry.find(name);
    }

    public PluginRegistry registry() {
        return registry;
    }

    public InstallDatabase database() {
        return database;
    }

    public PendingActions pending() {
        return pending;
    }

    public RestartService restarts() {
        return restarts;
    }

    public String serverVersion() {
        return plugin.serverVersion();
    }

    /** @return how a plugin's declared API version relates to this server */
    public CompatibilityCheck compatibilityOf(ManagedPlugin managed) {
        return CompatibilityCheck.against(
                managed.meta().map(JarPluginMeta::apiVersion).orElse(null),
                de.raindancer.apm.version.McVersion.of(serverVersion()));
    }

    // --- lifecycle -------------------------------------------------------------------------

    public PluginLifecycleService.Outcome enable(ManagedPlugin managed) {
        return lifecycle.enable(managed);
    }

    public PluginLifecycleService.Outcome disable(ManagedPlugin managed, boolean persistent) {
        return lifecycle.disable(managed, persistent);
    }

    public PluginLifecycleService.Outcome reload(ManagedPlugin managed) {
        return lifecycle.reload(managed);
    }

    public InstallService.InstallReport remove(ManagedPlugin managed, boolean purgeData) {
        return installs.remove(managed, purgeData);
    }

    // --- install / update ------------------------------------------------------------------

    /**
     * Resolves and downloads a plugin off the main thread, then reports back on the main thread.
     *
     * <p>Nothing has been written to the plugins folder when the callback runs — that only happens
     * in {@link #commit}.
     */
    public void prepareAsync(String query, Consumer<PrepareResult> callback) {
        ApmConfig config = plugin.config();
        Downloader downloader = new Downloader(config, plugin.userAgent());
        List<PluginSource> sources = List.of(
                new DirectUrlSource(downloader),
                new ModrinthSource(downloader, config.modrinthLoaderFilter()));

        plugin.runAsync(() -> {
            PrepareResult result;
            try {
                InstallService.StagedInstall staged =
                        installs.prepare(sources, query, serverVersion(), downloader);
                if (staged.compatibility().isCompatible() || config.allowIncompatible()) {
                    result = new PrepareResult.Ready(staged);
                } else {
                    result = new PrepareResult.NeedsConfirmation(staged,
                            staged.compatibility().detail());
                }
            } catch (Downloader.DownloadException e) {
                result = new PrepareResult.Failed(e.getMessage());
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Unexpected failure while preparing '{}'", query, e);
                result = new PrepareResult.Failed("Unexpected failure: " + e);
            }
            PrepareResult delivered = result;
            plugin.runOnMain(() -> callback.accept(delivered));
        });
    }

    /** Moves a staged install into place. Main thread only. */
    public InstallService.InstallReport commit(InstallService.StagedInstall staged) {
        ApmConfig config = plugin.config();
        return installs.commit(staged, config.attemptHotLoad(), config.keepDownloadCache());
    }

    /** Discards a staged install the user declined. Main thread; the delete is trivial. */
    public void discard(InstallService.StagedInstall staged) {
        try {
            java.nio.file.Files.deleteIfExists(staged.cachedJar());
        } catch (java.io.IOException e) {
            plugin.getSLF4JLogger().warn("Could not delete the discarded download {}: {}",
                    staged.cachedJar(), e.getMessage());
        }
    }

    /**
     * Re-runs the original install query for a tracked plugin.
     *
     * @param callback receives the result on the main thread
     */
    public void updateAsync(ManagedPlugin managed, Consumer<PrepareResult> callback) {
        Optional<InstallDatabase.Record> record = database.get(managed.name());
        if (record.isEmpty()) {
            callback.accept(new PrepareResult.Failed(managed.name()
                    + " was not installed through APM, so APM does not know where to update it from. "
                    + "Install it once with /apm install <url-or-slug> to start tracking it."));
            return;
        }
        prepareAsync(record.get().query(), callback);
    }

    // --- search ----------------------------------------------------------------------------

    /**
     * Searches the plugin catalogue. Delivers results on the main thread.
     *
     * @param callback receives the hits, or an empty list plus a non-null error message
     */
    public void searchAsync(String query, java.util.function.BiConsumer<List<PluginSource.SearchResult>, String> callback) {
        ApmConfig config = plugin.config();
        Downloader downloader = new Downloader(config, plugin.userAgent());
        ModrinthSource modrinth = new ModrinthSource(downloader, config.modrinthLoaderFilter());

        plugin.runAsync(() -> {
            List<PluginSource.SearchResult> hits = List.of();
            String error = null;
            try {
                hits = modrinth.search(query, serverVersion());
            } catch (Downloader.DownloadException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Search for '{}' failed", query, e);
                error = "Unexpected failure: " + e;
            }
            List<PluginSource.SearchResult> delivered = hits;
            String deliveredError = error;
            plugin.runOnMain(() -> callback.accept(delivered, deliveredError));
        });
    }

    // --- housekeeping ----------------------------------------------------------------------

    /** Re-reads {@code config.yml}. */
    public void reloadOwnConfig() {
        plugin.reloadApmConfig();
    }

    /** @return the folder APM downloads into */
    public Path cacheFolder() {
        return plugin.cacheFolder();
    }
}
