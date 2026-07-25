package de.raindancer.apm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginJarInspectorTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("a modern paper-plugin.yml is read completely")
    void readsPaperPluginYml() throws Exception {
        Path jar = jarWith("paper-plugin.yml", """
                name: TestPlugin
                version: 2.1.0
                main: com.example.Test
                api-version: '1.21'
                authors: [Alice, Bob]
                description: Does testing things.
                website: https://example.com
                dependencies:
                  server:
                    Vault:
                      required: true
                    PlaceholderAPI:
                      required: false
                """);

        JarPluginMeta meta = PluginJarInspector.inspect(jar);

        assertThat(meta.name()).isEqualTo("TestPlugin");
        assertThat(meta.version()).isEqualTo("2.1.0");
        assertThat(meta.mainClass()).isEqualTo("com.example.Test");
        assertThat(meta.apiVersion()).isEqualTo("1.21");
        assertThat(meta.authors()).containsExactly("Alice", "Bob");
        assertThat(meta.descriptor()).isEqualTo(JarPluginMeta.Descriptor.PAPER_PLUGIN_YML);
        assertThat(meta.displayName()).isEqualTo("TestPlugin v2.1.0");
        // Only the hard dependency counts — an optional one must not block anything.
        assertThat(meta.dependencies()).containsExactly("Vault");
    }

    @Test
    @DisplayName("a legacy plugin.yml is read, including its depend list")
    void readsLegacyPluginYml() throws Exception {
        Path jar = jarWith("plugin.yml", """
                name: OldPlugin
                version: 1.0
                main: com.example.Old
                api-version: 1.13
                author: Solo
                depend: [Vault, WorldEdit]
                """);

        JarPluginMeta meta = PluginJarInspector.inspect(jar);

        assertThat(meta.name()).isEqualTo("OldPlugin");
        assertThat(meta.descriptor()).isEqualTo(JarPluginMeta.Descriptor.PLUGIN_YML);
        assertThat(meta.authors()).containsExactly("Solo");
        assertThat(meta.dependencies()).containsExactly("Vault", "WorldEdit");
    }

    @Test
    @DisplayName("paper-plugin.yml wins when a jar carries both descriptors")
    void paperDescriptorWins() throws Exception {
        Path jar = temp.resolve("both.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(zip, "plugin.yml", "name: Legacy\nversion: 1\nmain: a.B\n");
            write(zip, "paper-plugin.yml", "name: Modern\nversion: 2\nmain: a.B\n");
        }
        assertThat(PluginJarInspector.inspect(jar).name()).isEqualTo("Modern");
    }

    @Test
    @DisplayName("a jar without any descriptor is rejected with an explanation")
    void rejectsJarWithoutDescriptor() throws Exception {
        Path jar = jarWith("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        assertThatThrownBy(() -> PluginJarInspector.inspect(jar))
                .isInstanceOf(PluginJarInspector.InspectionException.class)
                .hasMessageContaining("not a Bukkit/Paper plugin");
    }

    @Test
    @DisplayName("a file that is not a zip at all is rejected")
    void rejectsNonZip() throws Exception {
        Path notAJar = temp.resolve("evil.jar");
        Files.writeString(notAJar, "this is just text, not an archive");
        assertThatThrownBy(() -> PluginJarInspector.inspect(notAJar))
                .isInstanceOf(PluginJarInspector.InspectionException.class);
    }

    @Test
    @DisplayName("a descriptor without a name is rejected")
    void rejectsDescriptorWithoutName() throws Exception {
        Path jar = jarWith("plugin.yml", "version: 1.0\nmain: com.example.X\n");
        assertThatThrownBy(() -> PluginJarInspector.inspect(jar))
                .isInstanceOf(PluginJarInspector.InspectionException.class)
                .hasMessageContaining("no plugin name");
    }

    @Test
    @DisplayName("a descriptor without a main class is rejected")
    void rejectsDescriptorWithoutMain() throws Exception {
        Path jar = jarWith("plugin.yml", "name: X\nversion: 1.0\n");
        assertThatThrownBy(() -> PluginJarInspector.inspect(jar))
                .isInstanceOf(PluginJarInspector.InspectionException.class)
                .hasMessageContaining("no main class");
    }

    @Test
    @DisplayName("broken YAML is reported as broken YAML")
    void rejectsBrokenYaml() throws Exception {
        Path jar = jarWith("plugin.yml", "name: [unclosed\n  main: nope\n");
        assertThatThrownBy(() -> PluginJarInspector.inspect(jar))
                .isInstanceOf(PluginJarInspector.InspectionException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    @DisplayName("a descriptor that tries to instantiate a Java class is refused")
    void refusesUnsafeYamlTags() throws Exception {
        // SafeConstructor must reject this rather than construct arbitrary objects.
        Path jar = jarWith("plugin.yml", """
                name: Evil
                version: 1.0
                main: com.example.Evil
                payload: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://example.com"]]
                """);
        assertThatThrownBy(() -> PluginJarInspector.inspect(jar))
                .isInstanceOf(PluginJarInspector.InspectionException.class);
    }

    @Test
    @DisplayName("inspectQuietly swallows failures instead of throwing")
    void quietInspectionReturnsEmpty() throws Exception {
        Path notAJar = temp.resolve("nope.jar");
        Files.writeString(notAJar, "nonsense");
        assertThat(PluginJarInspector.inspectQuietly(notAJar)).isEmpty();
    }

    @Test
    @DisplayName("a missing api-version surfaces as null rather than an empty string")
    void missingApiVersionIsNull() throws Exception {
        Path jar = jarWith("plugin.yml", "name: X\nversion: 1\nmain: a.B\n");
        JarPluginMeta meta = PluginJarInspector.inspect(jar);
        assertThat(meta.apiVersion()).isNull();
        assertThat(meta.apiVersionOptional()).isEmpty();
    }

    private Path jarWith(String entryName, String content) throws IOException {
        Path jar = temp.resolve(entryName.replace('/', '_') + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(zip, entryName, content);
        }
        return jar;
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
