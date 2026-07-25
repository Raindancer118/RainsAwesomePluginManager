package de.raindancer.apm.core;

import java.util.List;
import java.util.Optional;

/**
 * The plugin descriptor as read straight out of a jar file, without loading any of its classes.
 *
 * @param name         plugin name as other plugins depend on it
 * @param version      plugin version string
 * @param mainClass    fully qualified main class
 * @param apiVersion   declared {@code api-version}, or null when absent
 * @param authors      declared authors
 * @param description  declared description, or null
 * @param website      declared website, or null
 * @param dependencies hard dependencies this plugin needs
 * @param descriptor   which descriptor file the data came from
 */
public record JarPluginMeta(String name,
                            String version,
                            String mainClass,
                            String apiVersion,
                            List<String> authors,
                            String description,
                            String website,
                            List<String> dependencies,
                            Descriptor descriptor) {

    public enum Descriptor {
        /** Modern Paper descriptor. */
        PAPER_PLUGIN_YML("paper-plugin.yml"),
        /** Legacy Bukkit descriptor, still fully supported. */
        PLUGIN_YML("plugin.yml");

        private final String fileName;

        Descriptor(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    public JarPluginMeta {
        authors = authors == null ? List.of() : List.copyOf(authors);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public Optional<String> apiVersionOptional() {
        return Optional.ofNullable(apiVersion);
    }

    /** @return {@code name} plus {@code version}, e.g. {@code EssentialsX v2.20.1} */
    public String displayName() {
        return version == null || version.isBlank() ? name : name + " v" + version;
    }
}
