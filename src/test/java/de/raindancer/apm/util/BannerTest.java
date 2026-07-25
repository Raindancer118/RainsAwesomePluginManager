package de.raindancer.apm.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BannerTest {

    @Test
    @DisplayName("the banner reports the real numbers it was given")
    void reportsGivenFacts() {
        List<Component> lines = Banner.build("1.0.0", "26.1.2", 7, 0, true);
        String rendered = render(lines);

        assertThat(rendered)
                .contains("Rain's Awesome Plugin Manager")
                .contains("1.0.0")
                .contains("26.1.2")
                .contains("7 discovered")
                .contains("ready");
    }

    @Test
    @DisplayName("a failed self-check is stated, not hidden behind the art")
    void reportsFailure() {
        String rendered = render(Banner.build("1.0.0", "26.1.2", 3, 0, false));
        assertThat(rendered).contains("self-check failed").doesNotContain("ready");
    }

    @Test
    @DisplayName("pending operations only appear when there are some")
    void mentionsPendingOnlyWhenPresent() {
        assertThat(render(Banner.build("1.0.0", "26.1.2", 3, 0, true)))
                .doesNotContain("waiting for shutdown");
        assertThat(render(Banner.build("1.0.0", "26.1.2", 3, 2, true)))
                .contains("2 file operations waiting for shutdown");
    }

    @Test
    @DisplayName("a version string containing a MiniMessage tag cannot break the layout")
    void escapesHostileVersion() {
        // A malformed version must not be parsed as formatting — MiniMessage would otherwise throw
        // or swallow the rest of the line.
        List<Component> lines = Banner.build("<red>1.0", "26.1.2", 1, 0, true);
        assertThat(render(lines)).contains("<red>1.0");
    }

    @Test
    @DisplayName("every line is short enough for an 80-column console")
    void staysWithinEightyColumns() {
        for (Component line : Banner.build("1.0.0", "26.1.2", 128, 4, true)) {
            assertThat(Msg.plain(line).length())
                    .as("line: %s", Msg.plain(line))
                    .isLessThanOrEqualTo(80);
        }
    }

    private static String render(List<Component> lines) {
        return lines.stream().map(Msg::plain).reduce("", (a, b) -> a + "\n" + b);
    }
}
