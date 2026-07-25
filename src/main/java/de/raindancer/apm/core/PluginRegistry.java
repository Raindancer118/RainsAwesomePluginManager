package de.raindancer.apm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * The union of "what the server has loaded" and "what is lying in the plugins folder".
 *
 * <p>Every view is rebuilt on demand rather than cached, because the folder can change underneath
 * us at any time and a stale package list is worse than a slightly slower one. A full scan is a
 * handful of zip header reads over a directory that rarely holds more than a few dozen files.
 *
 * <p>Note that {@link Plugin} instances are never retained beyond the lifetime of a single
 * snapshot — holding on to them across a disable would leak the plugin's entire class loader.
 */
public final class PluginRegistry {

    /** Suffix APM parks jars under so that Paper's loader ignores them. */
    public static final String PARKED_SUFFIX = ".apm-disabled";

    private final Server server;
    private final Path pluginsFolder;
    private final Logger logger;

    public PluginRegistry(Server server, Logger logger) {
        this.server = server;
        this.pluginsFolder = server.getPluginsFolder().toPath().toAbsolutePath().normalize();
        this.logger = logger;
    }

    public Path pluginsFolder() {
        return pluginsFolder;
    }

    /** @return every plugin APM knows about, sorted by name */
    public List<ManagedPlugin> snapshot() {
        Map<String, ManagedPlugin> byKey = new LinkedHashMap<>();

        // 1. Everything the server loaded. These are authoritative for name, version and state.
        for (Plugin plugin : server.getPluginManager().getPlugins()) {
            String name = plugin.getName();
            Path jar = locateJarFor(name);
            byKey.put(name.toLowerCase(Locale.ROOT), new ManagedPlugin(
                    name,
                    plugin.getPluginMeta().getVersion(),
                    jar,
                    plugin.isEnabled() ? ManagedPlugin.State.ENABLED : ManagedPlugin.State.DISABLED,
                    Optional.of(plugin),
                    jar == null ? Optional.empty() : PluginJarInspector.inspectQuietly(jar)));
        }

        // 2. Jars on disk the server did not load, plus parked jars.
        for (Path jar : listCandidateJars()) {
            Optional<JarPluginMeta> meta = PluginJarInspector.inspectQuietly(jar);
            if (meta.isEmpty()) {
                continue;
            }
            JarPluginMeta descriptor = meta.get();
            String key = descriptor.name().toLowerCase(Locale.ROOT);
            boolean parked = jar.getFileName().toString().endsWith(PARKED_SUFFIX);
            ManagedPlugin existing = byKey.get(key);
            if (existing != null) {
                // A loaded plugin whose jar we could not identify by file name earlier.
                if (!existing.hasJar() && !parked) {
                    byKey.put(key, new ManagedPlugin(existing.name(), existing.version(), jar,
                            existing.state(), existing.live(), meta));
                }
                continue;
            }
            byKey.put(key, new ManagedPlugin(
                    descriptor.name(),
                    descriptor.version(),
                    jar,
                    parked ? ManagedPlugin.State.PARKED : ManagedPlugin.State.NOT_LOADED,
                    Optional.empty(),
                    meta));
        }

        List<ManagedPlugin> result = new ArrayList<>(byKey.values());
        result.sort(Comparator.comparing(ManagedPlugin::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /** Case-insensitive lookup by plugin name. */
    public Optional<ManagedPlugin> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return snapshot().stream()
                .filter(plugin -> plugin.name().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    /** @return plugin names for tab completion */
    public List<String> names() {
        return snapshot().stream().map(ManagedPlugin::name).toList();
    }

    /** @return every plugin that hard-depends on {@code name} and is currently loaded */
    public List<ManagedPlugin> dependentsOf(String name) {
        return snapshot().stream()
                .filter(candidate -> !candidate.name().equalsIgnoreCase(name))
                .filter(candidate -> candidate.meta()
                        .map(meta -> meta.dependencies().stream().anyMatch(name::equalsIgnoreCase))
                        .orElse(false))
                .toList();
    }

    /**
     * Finds the jar a loaded plugin came from.
     *
     * <p>Paper exposes no public API for this, so APM reads every jar's descriptor and matches on
     * the declared plugin name. That is exact — the name in the descriptor is the name the server
     * registered the plugin under.
     */
    private Path locateJarFor(String pluginName) {
        for (Path jar : listCandidateJars()) {
            if (jar.getFileName().toString().endsWith(PARKED_SUFFIX)) {
                continue;
            }
            Optional<JarPluginMeta> meta = PluginJarInspector.inspectQuietly(jar);
            if (meta.isPresent() && meta.get().name().equalsIgnoreCase(pluginName)) {
                return jar;
            }
        }
        return null;
    }

    private List<Path> listCandidateJars() {
        if (!Files.isDirectory(pluginsFolder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(pluginsFolder)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return fileName.endsWith(".jar")
                                || fileName.endsWith(".jar" + PARKED_SUFFIX);
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.error("Could not list the plugins folder {}: {}", pluginsFolder, e.getMessage());
            return List.of();
        }
    }

    /** @return the parked counterpart of an active jar path */
    public static Path parkedPathFor(Path jar) {
        return jar.resolveSibling(jar.getFileName() + PARKED_SUFFIX);
    }

    /** @return the active counterpart of a parked jar path */
    public static Path activePathFor(Path parked) {
        String fileName = parked.getFileName().toString();
        return parked.resolveSibling(
                fileName.substring(0, fileName.length() - PARKED_SUFFIX.length()));
    }
}
