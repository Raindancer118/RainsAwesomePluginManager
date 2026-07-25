package de.raindancer.apm.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompatibilityCheckTest {

    private static final McVersion SERVER = McVersion.of("26.1.2");

    @Test
    @DisplayName("a plugin built for an older API is compatible")
    void olderApiIsCompatible() {
        CompatibilityCheck check = CompatibilityCheck.against("1.21", SERVER);
        assertThat(check.isCompatible()).isTrue();
        assertThat(check.verdict()).isEqualTo(CompatibilityCheck.Verdict.COMPATIBLE);
        assertThat(check.detail()).contains("1.21").contains("26.1.2");
    }

    @Test
    @DisplayName("a plugin built for exactly this API is compatible")
    void sameApiIsCompatible() {
        assertThat(CompatibilityCheck.against("26.1", SERVER).isCompatible()).isTrue();
    }

    @Test
    @DisplayName("a plugin built for a newer API is flagged, not installed silently")
    void newerApiIsTooNew() {
        CompatibilityCheck check = CompatibilityCheck.against("26.2", SERVER);
        assertThat(check.isCompatible()).isFalse();
        assertThat(check.verdict()).isEqualTo(CompatibilityCheck.Verdict.TOO_NEW);
        assertThat(check.detail()).contains("newer Minecraft version");
    }

    @Test
    @DisplayName("a missing api-version is reported as unknown and treated as incompatible")
    void missingApiVersionIsUnknown() {
        CompatibilityCheck check = CompatibilityCheck.against(null, SERVER);
        assertThat(check.verdict()).isEqualTo(CompatibilityCheck.Verdict.UNKNOWN);
        assertThat(check.isCompatible()).isFalse();
        assertThat(check.declaredApi()).isEmpty();
    }

    @Test
    @DisplayName("an unparseable api-version is unknown rather than assumed fine")
    void garbageApiVersionIsUnknown() {
        assertThat(CompatibilityCheck.against("latest", SERVER).verdict())
                .isEqualTo(CompatibilityCheck.Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("the old and the new versioning scheme are compared correctly")
    void schemesAreComparedAcrossTheBoundary() {
        // A plugin for the last 1.x release on a calendar-versioned server: fine.
        assertThat(CompatibilityCheck.against("1.21", McVersion.of("26.1.2")).isCompatible()).isTrue();
        // A plugin for a calendar version on an old 1.x server: not fine.
        assertThat(CompatibilityCheck.against("26.1", McVersion.of("1.21.4")).isCompatible()).isFalse();
    }
}
