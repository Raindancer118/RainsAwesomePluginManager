package de.raindancer.apm.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeFileNameTest {

    @Test
    @DisplayName("a normal download URL keeps its file name")
    void keepsPlainFileName() {
        assertThat(SafeFileName.fromUri(
                URI.create("https://cdn.modrinth.com/data/abc/LuckPerms-Bukkit-5.5.53.jar"), "fallback"))
                .isEqualTo("LuckPerms-Bukkit-5.5.53.jar");
    }

    @Test
    @DisplayName("a missing .jar extension is added")
    void addsJarExtension() {
        assertThat(SafeFileName.fromUri(URI.create("https://example.com/download"), "fallback"))
                .isEqualTo("download.jar");
    }

    @Test
    @DisplayName("a URL without a usable path falls back")
    void usesFallbackForEmptyPath() {
        assertThat(SafeFileName.fromUri(URI.create("https://example.com/"), "luckperms"))
                .isEqualTo("luckperms.jar");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../server.properties",
            "../../../etc/passwd",
            "/absolute/path/evil.jar",
            "..\\..\\windows\\system32\\evil.jar",
            "plugin.jar/../../ops.json",
    })
    @DisplayName("path traversal never survives sanitisation")
    void stripsPathTraversal(String hostile) {
        String safe = SafeFileName.fromName(hostile, "fallback");

        assertThat(safe)
                .doesNotContain("/")
                .doesNotContain("\\")
                .doesNotStartWith(".")
                .endsWith(".jar");
        // The decisive property: resolving it against a directory cannot leave that directory.
        // Compared with Path.startsWith rather than AssertJ's, which canonicalises against the
        // real filesystem and would fail on a path that does not exist.
        java.nio.file.Path root = java.nio.file.Path.of("/srv/plugins").normalize();
        assertThat(root.resolve(safe).normalize().startsWith(root))
                .as("%s must stay inside %s", safe, root)
                .isTrue();
    }

    @Test
    @DisplayName("a name of nothing but dots falls back instead of producing a hidden file")
    void refusesDotOnlyNames() {
        assertThat(SafeFileName.fromName("...", "fallback")).isEqualTo("fallback.jar");
        assertThat(SafeFileName.fromName("..", "fallback")).isEqualTo("fallback.jar");
    }

    @Test
    @DisplayName("shell and control characters are replaced")
    void replacesHostileCharacters() {
        String safe = SafeFileName.fromName("evil;rm -rf $(pwd)`.jar", "fallback");
        assertThat(safe)
                .doesNotContain(";")
                .doesNotContain("$")
                .doesNotContain("`")
                .doesNotContain(" ")
                .endsWith(".jar");
    }

    @Test
    @DisplayName("an absurdly long name is truncated but stays a .jar")
    void truncatesLongNames() {
        String safe = SafeFileName.fromName("a".repeat(500) + ".jar", "fallback");
        assertThat(safe).hasSizeLessThanOrEqualTo(120).endsWith(".jar");
    }

    @Test
    @DisplayName("null and empty input fall back")
    void handlesNullAndEmpty() {
        assertThat(SafeFileName.fromName(null, "fallback")).isEqualTo("fallback.jar");
        assertThat(SafeFileName.fromName("", "fallback")).isEqualTo("fallback.jar");
        assertThat(SafeFileName.fromName("", null)).isEqualTo("plugin.jar");
    }

    @Test
    @DisplayName("a query string is not carried into the file name")
    void ignoresQueryString() {
        String safe = SafeFileName.fromUri(
                URI.create("https://example.com/plugin.jar?token=abc&x=1"), "fallback");
        assertThat(safe).isEqualTo("plugin.jar");
    }
}
