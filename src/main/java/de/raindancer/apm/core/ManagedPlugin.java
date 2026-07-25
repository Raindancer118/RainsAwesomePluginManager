package de.raindancer.apm.core;

import java.nio.file.Path;
import java.util.Optional;

import org.bukkit.plugin.Plugin;

/**
 * A plugin as APM sees it: the jar on disk plus, when it exists, the live {@link Plugin} instance.
 *
 * <p>Bukkit only knows about plugins it managed to load. A package manager also has to talk about
 * jars that are present but not loaded (installed after startup) and jars that were deliberately
 * parked (persistently disabled), which is why this type exists alongside {@link Plugin}.
 *
 * @param name    plugin name
 * @param version plugin version, may be null for badly formed jars
 * @param jar     the jar backing it, or null if the plugin was loaded from somewhere APM cannot see
 * @param state   what APM can currently do with it
 * @param live    the loaded instance, if the server has one
 * @param meta    the descriptor read from the jar, if the jar is readable
 */
public record ManagedPlugin(String name,
                            String version,
                            Path jar,
                            State state,
                            Optional<Plugin> live,
                            Optional<JarPluginMeta> meta) {

    public enum State {
        /** Loaded and running. */
        ENABLED("enabled"),
        /** Loaded but switched off at runtime; comes back on the next restart. */
        DISABLED("disabled (runtime)"),
        /** Jar is present but the server never loaded it — a restart will pick it up. */
        NOT_LOADED("installed, awaiting restart"),
        /** Jar was parked as {@code .jar.apm-disabled}; it will not load until re-enabled. */
        PARKED("disabled (persistent)");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** @return whether the jar backing this plugin is on disk and known to APM */
    public boolean hasJar() {
        return jar != null;
    }

    public String displayName() {
        return version == null || version.isBlank() ? name : name + " v" + version;
    }
}
