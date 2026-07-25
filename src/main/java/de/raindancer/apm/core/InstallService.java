package de.raindancer.apm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.raindancer.apm.source.PluginSource;
import de.raindancer.apm.source.ResolvedDownload;
import de.raindancer.apm.util.Downloader;
import de.raindancer.apm.version.CompatibilityCheck;
import de.raindancer.apm.version.McVersion;
import org.slf4j.Logger;

/**
 * Installs and removes plugin jars.
 *
 * <p>The work is deliberately split in two halves. {@link #prepare} does everything that touches
 * the network and the file system scratch area and is called from a worker thread.
 * {@link #commit} does everything that touches the server and is called from the main thread.
 * Nothing lands in the plugins folder until the jar has been downloaded, checksum verified,
 * parsed and version checked, so a failed install cannot leave a half-written jar where Paper
 * would try to load it on the next start.
 */
public final class InstallService {

    /**
     * A validated jar in APM's cache, ready to be moved into place.
     *
     * @param cachedJar     the verified download
     * @param meta          descriptor read from that jar
     * @param compatibility how it relates to the running server
     * @param resolved      what the source told us
     * @param replaces      the plugin this install would overwrite, if any
     * @param query         the original user query
     */
    public record StagedInstall(Path cachedJar,
                                JarPluginMeta meta,
                                CompatibilityCheck compatibility,
                                ResolvedDownload resolved,
                                Optional<ManagedPlugin> replaces,
                                String query) {

        public boolean isUpdate() {
            return replaces.isPresent();
        }
    }

    /**
     * @param success      whether the jar is now in the plugins folder
     * @param message      user facing summary
     * @param needsRestart whether a restart is required before the plugin actually runs
     */
    public record InstallReport(boolean success, String message, boolean needsRestart) {
    }

    private final PluginRegistry registry;
    private final PluginLifecycleService lifecycle;
    private final InstallDatabase database;
    private final PendingActions pending;
    private final Path cacheFolder;
    private final Logger logger;

    public InstallService(PluginRegistry registry,
                          PluginLifecycleService lifecycle,
                          InstallDatabase database,
                          PendingActions pending,
                          Path cacheFolder,
                          Logger logger) {
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.database = database;
        this.pending = pending;
        this.cacheFolder = cacheFolder;
        this.logger = logger;
    }

    /**
     * Resolves, downloads and validates a plugin. Blocking — call off the main thread.
     *
     * @param sources       sources to try, in order
     * @param query         the user's query
     * @param serverVersion the running server's Minecraft version
     * @param downloader    configured downloader
     * @throws Downloader.DownloadException when the plugin cannot be obtained or is not usable
     */
    public StagedInstall prepare(List<PluginSource> sources,
                                 String query,
                                 String serverVersion,
                                 Downloader downloader) throws Downloader.DownloadException {
        PluginSource source = sources.stream()
                .filter(candidate -> candidate.handles(query))
                .findFirst()
                .orElseThrow(() -> new Downloader.DownloadException(
                        "No source knows how to handle '" + query + "'. Pass a https:// URL to a "
                                + "jar, or a Modrinth project slug."));

        ResolvedDownload resolved = source.resolve(query, serverVersion);

        Path cached = cacheFolder.resolve(resolved.fileName());
        Downloader.Result download = downloader.download(resolved.uri(), cached, resolved.sha512());

        JarPluginMeta meta;
        try {
            meta = PluginJarInspector.inspect(download.file());
        } catch (PluginJarInspector.InspectionException e) {
            deleteQuietly(download.file());
            throw new Downloader.DownloadException(
                    "The downloaded file is not a usable plugin: " + e.getMessage(), e);
        }

        CompatibilityCheck compatibility =
                CompatibilityCheck.against(meta.apiVersion(), McVersion.of(serverVersion));

        Optional<ManagedPlugin> replaces = registry.find(meta.name());
        return new StagedInstall(download.file(), meta, compatibility, resolved, replaces, query);
    }

    /**
     * Moves a prepared install into the plugins folder and tries to bring it up.
     * Must run on the main server thread.
     *
     * @param attemptHotLoad whether to try activating it without a restart
     * @param keepCache      whether the verified download stays in APM's cache folder
     */
    public InstallReport commit(StagedInstall staged, boolean attemptHotLoad, boolean keepCache) {
        Path target = registry.pluginsFolder().resolve(staged.resolved().fileName());

        // Replacing an existing install: stop the old one and get its jar out of the way first,
        // otherwise the server would find two jars declaring the same plugin name.
        StringBuilder notes = new StringBuilder();
        if (staged.replaces().isPresent()) {
            ManagedPlugin old = staged.replaces().get();
            if (old.live().isPresent() && old.live().get().isEnabled()) {
                PluginLifecycleService.Outcome disabled = lifecycle.disable(old, false);
                if (!disabled.success()) {
                    return new InstallReport(false,
                            "Could not stop the running " + old.name() + " before updating it: "
                                    + disabled.message(), false);
                }
            }
            if (old.hasJar() && !old.jar().equals(target)) {
                boolean removed = pending.tryNowOrDefer(new PendingActions.Action(
                        PendingActions.Kind.DELETE, old.jar(), null,
                        "replacing " + old.name() + " with " + staged.meta().displayName()));
                notes.append(removed
                        ? " The previous jar was removed."
                        : " The previous jar is still in use and will be removed during shutdown.");
            }
        }

        try {
            Files.createDirectories(registry.pluginsFolder());
            if (keepCache) {
                Files.copy(staged.cachedJar(), target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(staged.cachedJar(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not place {} into the plugins folder", target, e);
            return new InstallReport(false,
                    "Could not write " + target.getFileName() + " into the plugins folder: "
                            + e.getMessage(), false);
        }

        database.put(new InstallDatabase.Record(
                staged.meta().name(),
                staged.resolved().sourceId(),
                staged.query(),
                target.getFileName().toString(),
                staged.resolved().projectId().orElse(null),
                staged.resolved().versionId().orElse(null),
                Optional.ofNullable(staged.meta().version())
                        .orElse(staged.resolved().versionName().orElse(null)),
                sha512Of(staged),
                Instant.now()));

        String base = (staged.isUpdate() ? "Updated " : "Installed ") + staged.meta().displayName()
                + " (" + target.getFileName() + ")." + notes;

        if (!attemptHotLoad) {
            return new InstallReport(true,
                    base + " Restart the server to activate it.", true);
        }
        if (staged.isUpdate()) {
            // The old classes are still resident; loading the new jar now would run the plugin's
            // old code under a new name. Never worth the mess.
            return new InstallReport(true,
                    base + " A restart is required for the new version to take effect.", true);
        }
        return lifecycle.hotLoad(target).isPresent()
                ? new InstallReport(true, base + " It is loaded and enabled.", false)
                : new InstallReport(true,
                        base + " It could not be loaded at runtime — restart to activate it.", true);
    }

    /**
     * Removes a plugin's jar. Must run on the main server thread.
     *
     * @param purgeData whether the plugin's data folder is deleted as well — destructive and only
     *                  ever reached through an explicit, separately confirmed command
     */
    public InstallReport remove(ManagedPlugin plugin, boolean purgeData) {
        if (plugin.name().equalsIgnoreCase("APM")) {
            return new InstallReport(false,
                    "APM will not uninstall itself. Delete its jar by hand if you really want to.",
                    false);
        }
        if (!plugin.hasJar()) {
            return new InstallReport(false,
                    "APM cannot find a jar for " + plugin.name() + ", so there is nothing to remove.",
                    false);
        }

        StringBuilder message = new StringBuilder();
        if (plugin.live().isPresent() && plugin.live().get().isEnabled()) {
            PluginLifecycleService.Outcome disabled = lifecycle.disable(plugin, false);
            if (!disabled.success()) {
                return new InstallReport(false,
                        "Could not stop " + plugin.name() + ": " + disabled.message(), false);
            }
        }

        boolean deleted = pending.tryNowOrDefer(new PendingActions.Action(
                PendingActions.Kind.DELETE, plugin.jar(), null, "uninstalling " + plugin.name()));
        message.append(deleted
                ? "Removed " + plugin.displayName() + "."
                : "Removed " + plugin.displayName() + " — its jar is still in use and will be "
                        + "deleted during shutdown.");

        if (purgeData) {
            Path dataFolder = registry.pluginsFolder().resolve(plugin.name());
            if (Files.isDirectory(dataFolder)) {
                try {
                    deleteRecursively(dataFolder);
                    message.append(" Its data folder was deleted as well.");
                } catch (IOException e) {
                    logger.error("Could not purge data folder {}", dataFolder, e);
                    message.append(" Its data folder could NOT be deleted: ").append(e.getMessage());
                }
            } else {
                message.append(" It had no data folder.");
            }
        } else {
            message.append(" Its configuration was kept; use /apm purge to delete that too.");
        }

        database.remove(plugin.name());
        boolean stillLoaded = plugin.live().isPresent();
        if (stillLoaded) {
            message.append(" Its classes stay in memory until the server restarts.");
        }
        return new InstallReport(true, message.toString(), stillLoaded);
    }

    private String sha512Of(StagedInstall staged) {
        return staged.resolved().sha512().orElse(null);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cache leftovers are harmless; they are overwritten on the next attempt.
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
