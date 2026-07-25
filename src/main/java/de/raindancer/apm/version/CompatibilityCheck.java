package de.raindancer.apm.version;

import java.util.Optional;

/**
 * Result of checking a plugin jar's declared {@code api-version} against the running server.
 *
 * @param verdict     machine readable outcome
 * @param declaredApi the {@code api-version} found in the jar, if any
 * @param serverApi   the version of the server APM is running on
 * @param detail      human readable explanation, ready to be shown to the user
 */
public record CompatibilityCheck(Verdict verdict,
                                 Optional<McVersion> declaredApi,
                                 McVersion serverApi,
                                 String detail) {

    public enum Verdict {
        /** Declared API version is at or below the server version — safe to install. */
        COMPATIBLE,
        /** No {@code api-version} declared. Paper refuses to load such plugins outright. */
        UNKNOWN,
        /** Plugin targets a newer server than this one. */
        TOO_NEW
    }

    public boolean isCompatible() {
        return verdict == Verdict.COMPATIBLE;
    }

    /**
     * Compares a declared {@code api-version} against the running server version.
     *
     * @param declaredApiVersion the raw {@code api-version} string from the jar, may be null
     * @param serverVersion      the running server's Minecraft version
     */
    public static CompatibilityCheck against(String declaredApiVersion, McVersion serverVersion) {
        Optional<McVersion> declared = McVersion.parse(declaredApiVersion);
        if (declared.isEmpty()) {
            return new CompatibilityCheck(Verdict.UNKNOWN, Optional.empty(), serverVersion,
                    "The jar declares no usable api-version. Modern Paper refuses to load such "
                            + "plugins, so this is almost certainly a legacy or broken build.");
        }

        McVersion api = declared.get();
        if (api.isAtMost(serverVersion)) {
            return new CompatibilityCheck(Verdict.COMPATIBLE, declared, serverVersion,
                    "Targets API " + api.majorMinor() + ", server runs " + serverVersion.raw() + ".");
        }
        return new CompatibilityCheck(Verdict.TOO_NEW, declared, serverVersion,
                "Targets API " + api.majorMinor() + " but this server runs " + serverVersion.raw()
                        + " — the plugin is built for a newer Minecraft version.");
    }
}
