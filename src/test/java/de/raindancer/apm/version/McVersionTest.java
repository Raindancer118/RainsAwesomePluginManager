package de.raindancer.apm.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class McVersionTest {

    @Test
    @DisplayName("the classic 1.x scheme sorts numerically, not lexicographically")
    void classicSchemeSortsNumerically() {
        // The trap: as strings, "1.9" > "1.21". As versions it must not be.
        assertThat(McVersion.of("1.9")).isLessThan(McVersion.of("1.21"));
        assertThat(McVersion.of("1.21.4")).isLessThan(McVersion.of("1.21.11"));
    }

    @Test
    @DisplayName("the calendar scheme sorts after every 1.x version")
    void calendarSchemeSortsAfterClassic() {
        // Mojang moved from 1.21.11 to 26.1, so a plugin built for 1.21 must count as older.
        assertThat(McVersion.of("1.21.11")).isLessThan(McVersion.of("26.1"));
        assertThat(McVersion.of("26.1")).isLessThan(McVersion.of("26.1.2"));
        assertThat(McVersion.of("26.1.2")).isLessThan(McVersion.of("26.2"));
    }

    @ParameterizedTest
    @CsvSource({
            "1.21, 1.21.0",
            "26.1, 26.1.0.0",
    })
    @DisplayName("missing trailing segments count as zero")
    void missingSegmentsAreZero(String shorter, String longer) {
        assertThat(McVersion.of(shorter)).isEqualByComparingTo(McVersion.of(longer));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.21.4-R0.1-SNAPSHOT",
            "26.1.2.build.74-stable",
            "1.21.4 ",
    })
    @DisplayName("qualifiers and build suffixes are ignored")
    void qualifiersAreStripped(String raw) {
        assertThat(McVersion.parse(raw)).isPresent();
        assertThat(McVersion.of(raw).majorMinor()).matches("\\d+\\.\\d+");
    }

    @Test
    @DisplayName("a pre-release is older than the matching final release")
    void preReleaseIsOlder() {
        assertThat(McVersion.of("1.21.11-pre3")).isLessThan(McVersion.of("1.21.11"));
        assertThat(McVersion.of("26.2-rc1")).isLessThan(McVersion.of("26.2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "latest", "abc", "-1"})
    @DisplayName("non-numeric input is rejected instead of guessed at")
    void garbageIsRejected(String raw) {
        assertThat(McVersion.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("null is rejected without throwing")
    void nullIsRejected() {
        assertThat(McVersion.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("majorMinor drops the patch level")
    void majorMinorDropsPatch() {
        assertThat(McVersion.of("26.1.2").majorMinor()).isEqualTo("26.1");
        assertThat(McVersion.of("1.21.4").majorMinor()).isEqualTo("1.21");
        assertThat(McVersion.of("26").majorMinor()).isEqualTo("26");
    }

    @Test
    @DisplayName("isAtMost is inclusive")
    void isAtMostIsInclusive() {
        assertThat(McVersion.of("26.1").isAtMost(McVersion.of("26.1"))).isTrue();
        assertThat(McVersion.of("26.1").isAtMost(McVersion.of("26.2"))).isTrue();
        assertThat(McVersion.of("26.2").isAtMost(McVersion.of("26.1"))).isFalse();
    }
}
