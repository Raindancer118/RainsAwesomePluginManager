package de.raindancer.apm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Remembers where each APM managed plugin came from.
 *
 * <p>Without this, {@code /apm update} would have nothing to go on and {@code /apm info} could
 * not tell an operator whether a jar was hand-dropped into the folder or installed from a
 * catalogue. Plugins that were never installed through APM simply have no record, which is a
 * meaningful answer in itself.
 */
public final class InstallDatabase {

    /**
     * @param pluginName  the plugin's declared name, used as the primary key
     * @param source      source id, e.g. {@code url} or {@code modrinth}
     * @param query       the original query, so {@code /apm update} can re-resolve it
     * @param fileName    file name inside the plugins folder
     * @param projectId   source specific project id, if any
     * @param versionId   source specific version id, if any
     * @param versionName human readable version at install time
     * @param sha512      hex digest of the installed file
     * @param installedAt when it was installed
     */
    public record Record(String pluginName,
                         String source,
                         String query,
                         String fileName,
                         String projectId,
                         String versionId,
                         String versionName,
                         String sha512,
                         Instant installedAt) {
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, Record> records = new LinkedHashMap<>();

    public InstallDatabase(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized void load() {
        records.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("installed");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            Instant installedAt;
            try {
                installedAt = Instant.parse(entry.getString("installed-at", ""));
            } catch (RuntimeException e) {
                installedAt = Instant.EPOCH;
            }
            records.put(key.toLowerCase(Locale.ROOT), new Record(
                    key,
                    entry.getString("source", "unknown"),
                    entry.getString("query", ""),
                    entry.getString("file", ""),
                    entry.getString("project-id"),
                    entry.getString("version-id"),
                    entry.getString("version"),
                    entry.getString("sha512"),
                    installedAt));
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "Written by Rain's Awesome Plugin Manager. Tracks where each managed plugin came",
                "from so that /apm update and /apm info have something to work with.",
                "Editing this by hand is safe but pointless."));
        for (Record record : records.values()) {
            String base = "installed." + record.pluginName() + ".";
            yaml.set(base + "source", record.source());
            yaml.set(base + "query", record.query());
            yaml.set(base + "file", record.fileName());
            yaml.set(base + "project-id", record.projectId());
            yaml.set(base + "version-id", record.versionId());
            yaml.set(base + "version", record.versionName());
            yaml.set(base + "sha512", record.sha512());
            yaml.set(base + "installed-at", record.installedAt().toString());
        }
        try {
            Files.createDirectories(file.getParent());
            yaml.save(file.toFile());
        } catch (IOException e) {
            logger.error("Could not write APM's install database to {}: {}", file, e.getMessage());
        }
    }

    public synchronized void put(Record record) {
        records.put(record.pluginName().toLowerCase(Locale.ROOT), record);
        save();
    }

    public synchronized Optional<Record> get(String pluginName) {
        return Optional.ofNullable(records.get(pluginName.toLowerCase(Locale.ROOT)));
    }

    public synchronized void remove(String pluginName) {
        if (records.remove(pluginName.toLowerCase(Locale.ROOT)) != null) {
            save();
        }
    }

    /** @return every tracked plugin, keyed by lower-cased plugin name */
    public synchronized Map<String, Record> all() {
        return Map.copyOf(records);
    }
}
