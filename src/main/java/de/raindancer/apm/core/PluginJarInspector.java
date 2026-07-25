package de.raindancer.apm.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a plugin descriptor out of a jar file without loading a single class from it.
 *
 * <p>This runs against jars downloaded from the internet, so it is deliberately paranoid:
 * the YAML is parsed with {@link SafeConstructor} (no arbitrary object instantiation), the
 * descriptor size is capped, and every failure mode surfaces as {@link InspectionException}
 * rather than an unchecked crash.
 */
public final class PluginJarInspector {

    /** A descriptor larger than this is not a plugin descriptor, it is an attack or a mistake. */
    private static final int MAX_DESCRIPTOR_BYTES = 512 * 1024;

    private PluginJarInspector() {
    }

    /** Thrown when a file is not a usable plugin jar. The message is user facing. */
    public static class InspectionException extends Exception {
        public InspectionException(String message) {
            super(message);
        }

        public InspectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Reads the descriptor of the given jar.
     *
     * @throws InspectionException if the file is not a zip, holds no descriptor, or the
     *                             descriptor is missing mandatory fields
     */
    public static JarPluginMeta inspect(Path jar) throws InspectionException {
        Objects.requireNonNull(jar, "jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // paper-plugin.yml wins when both are present, matching Paper's own precedence.
            for (JarPluginMeta.Descriptor descriptor : JarPluginMeta.Descriptor.values()) {
                ZipEntry entry = zip.getEntry(descriptor.fileName());
                if (entry == null) {
                    continue;
                }
                if (entry.getSize() > MAX_DESCRIPTOR_BYTES) {
                    throw new InspectionException(
                            descriptor.fileName() + " is implausibly large (" + entry.getSize()
                                    + " bytes) — refusing to parse it.");
                }
                try (InputStream in = zip.getInputStream(entry);
                     Reader reader = new InputStreamReader(boundedStream(in), StandardCharsets.UTF_8)) {
                    return parse(reader, descriptor);
                }
            }
            throw new InspectionException(
                    "Neither paper-plugin.yml nor plugin.yml found — this jar is not a Bukkit/Paper plugin.");
        } catch (ZipException e) {
            throw new InspectionException("Not a valid jar/zip archive: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new InspectionException("Could not read the jar: " + e.getMessage(), e);
        }
    }

    /** Same as {@link #inspect(Path)} but swallows the failure. */
    public static Optional<JarPluginMeta> inspectQuietly(Path jar) {
        try {
            return Optional.of(inspect(jar));
        } catch (InspectionException e) {
            return Optional.empty();
        }
    }

    private static InputStream boundedStream(InputStream in) {
        // Guards against a zip bomb: a tiny compressed entry claiming a huge uncompressed size.
        return new java.io.FilterInputStream(in) {
            private long read;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0 && ++read > MAX_DESCRIPTOR_BYTES) {
                    throw new IOException("Descriptor exceeds " + MAX_DESCRIPTOR_BYTES + " bytes");
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                if (n > 0 && (read += n) > MAX_DESCRIPTOR_BYTES) {
                    throw new IOException("Descriptor exceeds " + MAX_DESCRIPTOR_BYTES + " bytes");
                }
                return n;
            }
        };
    }

    static JarPluginMeta parse(Reader reader, JarPluginMeta.Descriptor descriptor)
            throws InspectionException {
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(64);
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (YAMLException e) {
            throw new InspectionException(
                    descriptor.fileName() + " is not valid YAML: " + e.getMessage(), e);
        }

        if (!(loaded instanceof Map<?, ?> root)) {
            throw new InspectionException(descriptor.fileName() + " does not contain a YAML mapping.");
        }

        String name = string(root, "name");
        if (name == null || name.isBlank()) {
            throw new InspectionException(descriptor.fileName() + " declares no plugin name.");
        }
        String main = string(root, "main");
        if (main == null || main.isBlank()) {
            throw new InspectionException(descriptor.fileName() + " declares no main class.");
        }

        return new JarPluginMeta(
                name.trim(),
                string(root, "version"),
                main.trim(),
                string(root, "api-version"),
                authors(root),
                string(root, "description"),
                string(root, "website"),
                dependencies(root, descriptor),
                descriptor);
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> authors(Map<?, ?> root) {
        List<String> authors = new ArrayList<>();
        String single = string(root, "author");
        if (single != null && !single.isBlank()) {
            authors.add(single);
        }
        if (root.get("authors") instanceof List<?> list) {
            for (Object author : list) {
                if (author != null) {
                    authors.add(String.valueOf(author));
                }
            }
        }
        return authors;
    }

    private static List<String> dependencies(Map<?, ?> root, JarPluginMeta.Descriptor descriptor) {
        List<String> deps = new ArrayList<>();
        if (descriptor == JarPluginMeta.Descriptor.PLUGIN_YML) {
            // Legacy: `depend: [A, B]`
            if (root.get("depend") instanceof List<?> list) {
                list.stream().filter(Objects::nonNull).map(String::valueOf).forEach(deps::add);
            }
            return deps;
        }

        // Modern: `dependencies.server.<Name>.required: true`
        if (root.get("dependencies") instanceof Map<?, ?> dependencies) {
            for (Object bootstrapOrServer : dependencies.values()) {
                if (!(bootstrapOrServer instanceof Map<?, ?> entries)) {
                    continue;
                }
                for (Map.Entry<?, ?> entry : entries.entrySet()) {
                    boolean required = !(entry.getValue() instanceof Map<?, ?> spec)
                            || !Boolean.FALSE.equals(spec.get("required"));
                    if (required && entry.getKey() != null) {
                        String dep = String.valueOf(entry.getKey());
                        if (!deps.contains(dep)) {
                            deps.add(dep);
                        }
                    }
                }
            }
        }
        return deps;
    }
}
