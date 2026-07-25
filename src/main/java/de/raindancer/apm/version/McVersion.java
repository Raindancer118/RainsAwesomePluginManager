package de.raindancer.apm.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A comparable Minecraft / Paper API version.
 *
 * <p>Handles both the historic {@code 1.21.4} scheme and the calendar scheme Mojang moved to
 * ({@code 26.1.2}). Both are dot separated numeric segments, so a plain segment-wise numeric
 * comparison orders them correctly: {@code 1.21} sorts before {@code 26.1}, which is exactly
 * what we want when deciding whether an older plugin runs on a newer server.
 *
 * <p>Trailing qualifiers such as {@code -pre3}, {@code -rc1} or {@code -R0.1-SNAPSHOT} are
 * stripped; a version carrying a qualifier is considered slightly older than the plain release
 * of the same number, mirroring how Mojang ships pre-releases before the final build.
 */
public final class McVersion implements Comparable<McVersion> {

    private final List<Integer> segments;
    private final boolean preRelease;
    private final String raw;

    private McVersion(String raw, List<Integer> segments, boolean preRelease) {
        this.raw = raw;
        this.segments = List.copyOf(segments);
        this.preRelease = preRelease;
    }

    /**
     * Parses a version string, ignoring anything that is not a leading numeric segment chain.
     *
     * @return empty if the string carries no numeric segment at all
     */
    public static Optional<McVersion> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        // Cut off everything from the first character that can start a qualifier.
        int cut = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                cut = i;
                break;
            }
        }
        String numericPart = trimmed.substring(0, cut);
        String qualifier = trimmed.substring(cut).toLowerCase(java.util.Locale.ROOT);

        List<Integer> parsed = new ArrayList<>();
        for (String segment : numericPart.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Integer.parseInt(segment));
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        boolean pre = qualifier.startsWith("-pre") || qualifier.startsWith("-rc")
                || qualifier.startsWith("_pre") || qualifier.startsWith("-snapshot");
        return Optional.of(new McVersion(trimmed, parsed, pre));
    }

    /**
     * Parses a version string or throws — for values that are known to be well formed
     * (server version, test fixtures).
     */
    public static McVersion of(String input) {
        return parse(input).orElseThrow(
                () -> new IllegalArgumentException("Not a version string: " + input));
    }

    /** @return {@code true} if this version is at most {@code other} */
    public boolean isAtMost(McVersion other) {
        return compareTo(other) <= 0;
    }

    /** @return the {@code major.minor} prefix, e.g. {@code 26.1} for {@code 26.1.2} */
    public String majorMinor() {
        if (segments.size() == 1) {
            return String.valueOf(segments.getFirst());
        }
        return segments.get(0) + "." + segments.get(1);
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(McVersion other) {
        int max = Math.max(segments.size(), other.segments.size());
        for (int i = 0; i < max; i++) {
            int a = i < segments.size() ? segments.get(i) : 0;
            int b = i < other.segments.size() ? other.segments.get(i) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        if (preRelease == other.preRelease) {
            return 0;
        }
        return preRelease ? -1 : 1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof McVersion other
                && segments.equals(other.segments)
                && preRelease == other.preRelease;
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments, preRelease);
    }

    @Override
    public String toString() {
        return raw;
    }
}
