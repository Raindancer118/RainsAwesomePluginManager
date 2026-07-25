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
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Reads and writes other plugins' YAML configuration files.
 *
 * <p>This is the most dangerous thing APM does, so the rules are strict:
 *
 * <ul>
 *   <li>Only files <em>inside</em> a plugin's own data folder are reachable, verified by resolving
 *       and normalising every path and checking it is still under that folder. A crafted file name
 *       cannot walk up into {@code server.properties} or {@code ops.json}.</li>
 *   <li>Only {@code .yml} / {@code .yaml} files are offered. Databases and binary state are not
 *       something a chest GUI should be poking at.</li>
 *   <li>Every write is preceded by a timestamped backup next to the original, so a mistake is
 *       always recoverable without a server backup.</li>
 *   <li>Values are written with their original type where possible. Turning an integer into the
 *       string {@code "5"} silently breaks plugins that read it as a number.</li>
 *   <li>Comments survive: Bukkit's YAML implementation round-trips them, and a config stripped of
 *       its documentation is a config nobody can maintain.</li>
 * </ul>
 */
public final class ConfigEditService {

    /** Largest config APM will open. Beyond this, a text editor is the right tool. */
    private static final long MAX_CONFIG_BYTES = 2L * 1024 * 1024;

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** What kind of editor a value needs. */
    public enum ValueKind {
        BOOLEAN, INTEGER, DOUBLE, STRING, LIST, SECTION, UNSUPPORTED
    }

    /**
     * A single editable entry.
     *
     * @param path  full config path, e.g. {@code economy.starting-balance}
     * @param key   the last path segment
     * @param kind  what it holds
     * @param value the current value
     * @param comment the comment block above it in the file, joined into one line
     * @param childCount number of direct children for {@link ValueKind#SECTION}
     */
    public record Entry(String path, String key, ValueKind kind, Object value,
                        String comment, int childCount) {

        /** @return the value rendered for display, truncated for very long strings */
        public String display() {
            return switch (kind) {
                case SECTION -> childCount + " entr" + (childCount == 1 ? "y" : "ies");
                case LIST -> ((List<?>) value).size() + " item(s)";
                case STRING -> {
                    String text = String.valueOf(value);
                    yield text.length() > 60 ? text.substring(0, 57) + "…" : text;
                }
                default -> String.valueOf(value);
            };
        }
    }

    /** Outcome of a write attempt. */
    public record SaveResult(boolean success, String message) {
    }

    private final java.util.function.Supplier<Path> pluginsFolder;
    private final Logger logger;

    /**
     * @param pluginsFolder supplies the server's plugins folder. A supplier rather than the folder
     *                      itself so this service needs no live {@code Server} and is testable
     *                      against a temporary directory.
     */
    public ConfigEditService(java.util.function.Supplier<Path> pluginsFolder, Logger logger) {
        this.pluginsFolder = pluginsFolder;
        this.logger = logger;
    }

    /** @return the folder a plugin stores its data in, whether or not it exists yet */
    public Path dataFolderOf(String pluginName) {
        return pluginsFolder.get().resolve(pluginName);
    }

    /**
     * Lists the YAML files of a plugin, relative to its data folder, at most two levels deep.
     *
     * <p>Depth is capped because plugins that store thousands of per-player YAML files exist, and
     * a menu is not a file manager.
     */
    public List<Path> listConfigFiles(String pluginName) {
        Path root = dataFolderOf(pluginName);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(ConfigEditService::isYaml)
                    .filter(path -> sizeOf(path) <= MAX_CONFIG_BYTES)
                    .map(root::relativize)
                    .sorted(java.util.Comparator.comparing(Path::toString))
                    .limit(200)
                    .toList();
        } catch (IOException e) {
            logger.warn("Could not list config files of {}: {}", pluginName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolves a relative file name inside a plugin's data folder, refusing anything that escapes.
     *
     * @return empty when the path is outside the folder, missing, not YAML, or too large
     */
    public Optional<Path> resolve(String pluginName, Path relative) {
        Path root = dataFolderOf(pluginName).toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            logger.warn("Refused a config path outside {}: {}", root, relative);
            return Optional.empty();
        }
        if (!Files.isRegularFile(resolved) || !isYaml(resolved)
                || sizeOf(resolved) > MAX_CONFIG_BYTES) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /**
     * Loads a config file.
     *
     * @return empty when the file is not valid YAML — better to say so than to offer an editor that
     *         would rewrite the file as an empty document
     */
    public Optional<YamlConfiguration> load(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
            return Optional.of(yaml);
        } catch (IOException | InvalidConfigurationException e) {
            logger.warn("Could not parse {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lists the direct children of a section.
     *
     * @param path the section path, or empty string for the document root
     */
    public List<Entry> entriesOf(YamlConfiguration yaml, String path) {
        ConfigurationSection section = path.isEmpty()
                ? yaml
                : yaml.getConfigurationSection(path);
        if (section == null) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            Object value = section.get(key);
            ValueKind kind = kindOf(section, key, value);
            int children = kind == ValueKind.SECTION && value instanceof ConfigurationSection child
                    ? child.getKeys(false).size()
                    : 0;
            entries.add(new Entry(fullPath, key, kind, value,
                    joinComments(yaml.getComments(fullPath)), children));
        }
        return entries;
    }

    private static ValueKind kindOf(ConfigurationSection section, String key, Object value) {
        if (value instanceof ConfigurationSection) {
            return ValueKind.SECTION;
        }
        if (section.isBoolean(key)) {
            return ValueKind.BOOLEAN;
        }
        if (section.isInt(key)) {
            return ValueKind.INTEGER;
        }
        if (section.isDouble(key) || value instanceof Float || value instanceof Long) {
            return ValueKind.DOUBLE;
        }
        if (section.isList(key)) {
            return ValueKind.LIST;
        }
        if (section.isString(key)) {
            return ValueKind.STRING;
        }
        return value == null ? ValueKind.STRING : ValueKind.UNSUPPORTED;
    }

    private static String joinComments(List<String> comments) {
        if (comments == null || comments.isEmpty()) {
            return "";
        }
        return String.join(" ", comments).trim();
    }

    /**
     * Parses user typed text into the type the config path already holds.
     *
     * @return empty when the text does not fit that type — the caller then tells the user why
     *         rather than silently storing a string where a number belongs
     */
    public Optional<Object> parseAs(ValueKind kind, String input) {
        String trimmed = input.trim();
        try {
            return switch (kind) {
                case BOOLEAN -> switch (trimmed.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "on", "1" -> Optional.of(Boolean.TRUE);
                    case "false", "no", "off", "0" -> Optional.of(Boolean.FALSE);
                    default -> Optional.empty();
                };
                case INTEGER -> Optional.of(Integer.valueOf(trimmed));
                case DOUBLE -> Optional.of(Double.valueOf(trimmed));
                case STRING -> Optional.of(trimmed);
                case LIST, SECTION, UNSUPPORTED -> Optional.empty();
            };
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Writes a value and saves the file, taking a backup first.
     *
     * @param newValue the value to store, or null to remove the key
     */
    public SaveResult set(Path file, YamlConfiguration yaml, String path, Object newValue) {
        Optional<Path> backup = backup(file);
        if (backup.isEmpty()) {
            return new SaveResult(false, "Could not create a backup, so nothing was changed.");
        }
        yaml.set(path, newValue);
        try {
            yaml.save(file.toFile());
        } catch (IOException e) {
            logger.error("Could not save {}", file, e);
            restore(backup.get(), file);
            return new SaveResult(false, "Saving failed (" + e.getMessage()
                    + "). The original file was restored from the backup.");
        }
        return new SaveResult(true, "Saved. A backup was written to "
                + backup.get().getFileName() + ".");
    }

    /** Copies {@code file} next to itself with a timestamp suffix. */
    private Optional<Path> backup(Path file) {
        Path target = file.resolveSibling(file.getFileName()
                + ".apm-backup-" + LocalDateTime.now().format(BACKUP_STAMP));
        try {
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch (IOException e) {
            logger.error("Could not back up {}", file, e);
            return Optional.empty();
        }
    }

    private void restore(Path backup, Path file) {
        try {
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Could not restore {} from {} — the file may be truncated!", file, backup, e);
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }
}
