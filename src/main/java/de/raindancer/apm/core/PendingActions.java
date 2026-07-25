package de.raindancer.apm.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Queue of file operations that could not be carried out immediately.
 *
 * <p>Deleting or renaming a jar whose classes are still loaded fails on Windows, and is merely
 * unwise on Linux. APM therefore always tries the operation right away and, if it fails, records
 * it here. The queue is drained three times: on server start (before anything could have opened
 * the file this session), on server stop, and finally from a JVM shutdown hook after the plugin
 * class loaders have been closed.
 *
 * <p>The queue is persisted so a hard crash between "user ran /apm remove" and "server restarted"
 * cannot leave a plugin half removed.
 */
public final class PendingActions {

    public enum Kind {
        /** Delete a jar. */
        DELETE,
        /** Rename a jar, used for persistent enable/disable. */
        RENAME
    }

    /**
     * @param kind   what to do
     * @param source absolute path to act on
     * @param target absolute destination for {@link Kind#RENAME}, null otherwise
     * @param reason human readable note, shown by {@code /apm pending}
     */
    public record Action(Kind kind, Path source, Path target, String reason) {

        public String describe() {
            return switch (kind) {
                case DELETE -> "delete " + source.getFileName();
                case RENAME -> "rename " + source.getFileName() + " -> "
                        + (target == null ? "?" : target.getFileName());
            };
        }
    }

    private final Path stateFile;
    private final Logger logger;
    private final List<Action> actions = new ArrayList<>();

    public PendingActions(Path stateFile, Logger logger) {
        this.stateFile = stateFile;
        this.logger = logger;
    }

    /** Loads persisted actions from disk. Never throws — a broken state file must not block startup. */
    public synchronized void load() {
        actions.clear();
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("actions");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            try {
                Kind kind = Kind.valueOf(entry.getString("kind", "").toUpperCase(Locale.ROOT));
                String source = entry.getString("source");
                if (source == null) {
                    continue;
                }
                String target = entry.getString("target");
                actions.add(new Action(kind, Path.of(source),
                        target == null ? null : Path.of(target),
                        entry.getString("reason", "")));
            } catch (IllegalArgumentException e) {
                logger.warn("Discarding unreadable pending action '{}': {}", key, e.getMessage());
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        Map<String, Object> serialised = new LinkedHashMap<>();
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", action.kind().name());
            entry.put("source", action.source().toString());
            if (action.target() != null) {
                entry.put("target", action.target().toString());
            }
            entry.put("reason", action.reason());
            serialised.put(String.valueOf(i), entry);
        }
        yaml.createSection("actions", serialised);
        try {
            Files.createDirectories(stateFile.getParent());
            yaml.save(stateFile.toFile());
        } catch (IOException e) {
            logger.error("Could not persist APM's pending action queue to {}: {}",
                    stateFile, e.getMessage());
        }
    }

    /**
     * Attempts {@code action} immediately and queues it for later if that fails.
     *
     * @return {@code true} if it was carried out right now
     */
    public boolean tryNowOrDefer(Action action) {
        if (apply(action)) {
            return true;
        }
        synchronized (this) {
            actions.removeIf(existing -> existing.source().equals(action.source()));
            actions.add(action);
            save();
        }
        logger.info("Deferred to shutdown: {} ({})", action.describe(), action.reason());
        return false;
    }

    /** Runs every queued action, dropping the ones that succeed. */
    public synchronized void drain() {
        if (actions.isEmpty()) {
            return;
        }
        List<Action> remaining = new ArrayList<>();
        for (Action action : actions) {
            if (!apply(action)) {
                remaining.add(action);
            }
        }
        int done = actions.size() - remaining.size();
        actions.clear();
        actions.addAll(remaining);
        save();
        if (done > 0) {
            logger.info("Applied {} deferred file operation(s).", done);
        }
        if (!remaining.isEmpty()) {
            logger.warn("{} deferred file operation(s) still pending — they will be retried "
                    + "on the next start.", remaining.size());
        }
    }

    /** @return an immutable snapshot for {@code /apm pending} */
    public synchronized List<Action> snapshot() {
        return List.copyOf(actions);
    }

    /** Drops a queued action, used when the user reverses their decision before a restart. */
    public synchronized boolean cancelFor(Path source) {
        boolean removed = actions.removeIf(action -> action.source().equals(source));
        if (removed) {
            save();
        }
        return removed;
    }

    private boolean apply(Action action) {
        try {
            switch (action.kind()) {
                case DELETE -> {
                    if (!Files.exists(action.source())) {
                        return true;
                    }
                    Files.delete(action.source());
                }
                case RENAME -> {
                    if (!Files.exists(action.source())) {
                        // Already renamed, or the file vanished — nothing left to do.
                        return true;
                    }
                    if (action.target() == null) {
                        return true;
                    }
                    Files.move(action.source(), action.target(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Installs the last-resort drain as a JVM shutdown hook.
     *
     * @return the registered hook so it can be removed again on a clean disable
     */
    public Thread installShutdownHook() {
        Thread hook = new Thread(this::drain, "apm-pending-actions");
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /** Convenience for callers that still work with {@link File}. */
    public static Path of(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }
}
