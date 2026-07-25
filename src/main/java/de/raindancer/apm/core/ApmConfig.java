package de.raindancer.apm.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed snapshot of {@code config.yml}. Immutable — reloading the config builds a new instance so
 * that a command running on another thread never observes a half applied configuration.
 */
public record ApmConfig(boolean requireHttps,
                        Set<String> allowedHosts,
                        long maxDownloadBytes,
                        int connectTimeoutSeconds,
                        int readTimeoutSeconds,
                        boolean allowIncompatible,
                        boolean attemptHotLoad,
                        int restartCountdownSeconds,
                        boolean keepDownloadCache,
                        String modrinthLoaderFilter) {

    public static ApmConfig from(FileConfiguration config) {
        List<String> hosts = config.getStringList("security.allowed-hosts");
        return new ApmConfig(
                config.getBoolean("security.require-https", true),
                hosts.stream()
                        .map(host -> host.toLowerCase(Locale.ROOT).trim())
                        .filter(host -> !host.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()),
                Math.max(1024L, config.getLong("security.max-download-size-mb", 200) * 1024L * 1024L),
                Math.max(1, config.getInt("download.connect-timeout-seconds", 15)),
                Math.max(1, config.getInt("download.read-timeout-seconds", 120)),
                config.getBoolean("install.allow-incompatible", false),
                config.getBoolean("install.attempt-hot-load", true),
                Math.max(0, config.getInt("restart.countdown-seconds", 10)),
                config.getBoolean("download.keep-cache", false),
                config.getString("modrinth.loader", "paper"));
    }

    /**
     * @return {@code true} when downloads from {@code host} are permitted. An empty allow list
     *         means "any host", which is the default — the operator already needs an admin
     *         permission to run {@code /apm install}.
     */
    public boolean isHostAllowed(String host) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream()
                .anyMatch(allowed -> normalised.equals(allowed) || normalised.endsWith("." + allowed));
    }
}
