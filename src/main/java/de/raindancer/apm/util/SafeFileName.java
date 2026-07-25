package de.raindancer.apm.util;

import java.net.URI;
import java.util.Locale;

/**
 * Turns untrusted remote names into file names that can never escape the plugins directory.
 *
 * <p>A download URL or a Modrinth file name is attacker influenced input. Feeding it straight
 * into {@code pluginsFolder.resolve(name)} would allow {@code ../../server.properties}, so every
 * name passes through here first.
 */
public final class SafeFileName {

    private static final int MAX_LENGTH = 120;

    private SafeFileName() {
    }

    /**
     * Derives a safe {@code .jar} file name from a URL, falling back to {@code fallbackBase}
     * when the URL carries no usable path segment.
     */
    public static String fromUri(URI uri, String fallbackBase) {
        String path = uri.getPath();
        String candidate = null;
        if (path != null && !path.isEmpty()) {
            int slash = path.lastIndexOf('/');
            candidate = slash >= 0 ? path.substring(slash + 1) : path;
        }
        if (candidate == null || sanitise(candidate).isEmpty()) {
            candidate = fallbackBase;
        }
        return ensureJar(sanitise(candidate), fallbackBase);
    }

    /** Sanitises an explicit file name (e.g. the one Modrinth reports for a version). */
    public static String fromName(String name, String fallbackBase) {
        return ensureJar(sanitise(name), fallbackBase);
    }

    private static String ensureJar(String sanitised, String fallbackBase) {
        String base = sanitised.isEmpty() ? sanitise(fallbackBase) : sanitised;
        if (base.isEmpty()) {
            base = "plugin";
        }
        if (!base.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            base = base + ".jar";
        }
        if (base.length() > MAX_LENGTH) {
            base = base.substring(0, MAX_LENGTH - 4) + ".jar";
        }
        return base;
    }

    /**
     * Strips every path separator, control character and shell-hostile symbol, and refuses
     * names that consist only of dots.
     */
    private static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '+';
            out.append(allowed ? c : '_');
        }
        String cleaned = out.toString();
        // Collapse leading dots so ".." and hidden files cannot be produced.
        int firstReal = 0;
        while (firstReal < cleaned.length() && cleaned.charAt(firstReal) == '.') {
            firstReal++;
        }
        cleaned = cleaned.substring(firstReal);
        return cleaned.chars().anyMatch(c -> c != '.' && c != '_') ? cleaned : "";
    }
}
