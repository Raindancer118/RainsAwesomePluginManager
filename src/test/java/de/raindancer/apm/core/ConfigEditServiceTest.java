package de.raindancer.apm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class ConfigEditServiceTest {

    @TempDir
    Path plugins;

    private ConfigEditService service;
    private Path dataFolder;

    @BeforeEach
    void setUp() throws IOException {
        service = new ConfigEditService(() -> plugins, NOPLogger.NOP_LOGGER);
        dataFolder = plugins.resolve("TestPlugin");
        Files.createDirectories(dataFolder);
    }

    private Path writeConfig(String content) throws IOException {
        Path file = dataFolder.resolve("config.yml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("YAML files in the data folder are listed, other files are not")
    void listsOnlyYaml() throws Exception {
        writeConfig("a: 1\n");
        Files.writeString(dataFolder.resolve("messages.yaml"), "hi: there\n");
        Files.writeString(dataFolder.resolve("data.db"), "binary-ish");
        Files.writeString(dataFolder.resolve("notes.txt"), "text");

        assertThat(service.listConfigFiles("TestPlugin"))
                .extracting(Path::toString)
                .containsExactlyInAnyOrder("config.yml", "messages.yaml");
    }

    @Test
    @DisplayName("a plugin without a data folder yields an empty list rather than an error")
    void handlesMissingDataFolder() {
        assertThat(service.listConfigFiles("NotInstalled")).isEmpty();
    }

    @Test
    @DisplayName("a path that escapes the data folder is refused")
    void refusesPathTraversal() throws Exception {
        // A real file outside the plugin's folder, to prove the refusal is not just "file missing".
        Files.writeString(plugins.resolve("server-ish.yml"), "secret: true\n");

        assertThat(service.resolve("TestPlugin", Path.of("..", "server-ish.yml"))).isEmpty();
        assertThat(service.resolve("TestPlugin", Path.of("../../etc/passwd"))).isEmpty();
    }

    @Test
    @DisplayName("a legitimate relative path inside the folder resolves")
    void resolvesInsideFolder() throws Exception {
        writeConfig("a: 1\n");
        assertThat(service.resolve("TestPlugin", Path.of("config.yml"))).isPresent();
    }

    @Test
    @DisplayName("entries are typed so the GUI can pick the right editor")
    void classifiesValueTypes() throws Exception {
        Path file = writeConfig("""
                enabled: true
                max-players: 20
                multiplier: 1.5
                message: hello
                worlds:
                  - world
                  - world_nether
                economy:
                  start: 100
                  currency: coins
                """);

        YamlConfiguration yaml = service.load(file).orElseThrow();
        List<ConfigEditService.Entry> entries = service.entriesOf(yaml, "");

        assertThat(entries).extracting(ConfigEditService.Entry::key, ConfigEditService.Entry::kind)
                .contains(
                        org.assertj.core.api.Assertions.tuple("enabled", ConfigEditService.ValueKind.BOOLEAN),
                        org.assertj.core.api.Assertions.tuple("max-players", ConfigEditService.ValueKind.INTEGER),
                        org.assertj.core.api.Assertions.tuple("multiplier", ConfigEditService.ValueKind.DOUBLE),
                        org.assertj.core.api.Assertions.tuple("message", ConfigEditService.ValueKind.STRING),
                        org.assertj.core.api.Assertions.tuple("worlds", ConfigEditService.ValueKind.LIST),
                        org.assertj.core.api.Assertions.tuple("economy", ConfigEditService.ValueKind.SECTION));

        ConfigEditService.Entry section = entries.stream()
                .filter(entry -> entry.key().equals("economy")).findFirst().orElseThrow();
        assertThat(section.childCount()).isEqualTo(2);
        assertThat(section.display()).isEqualTo("2 entries");
    }

    @Test
    @DisplayName("nested sections are navigable by path")
    void navigatesNestedSections() throws Exception {
        Path file = writeConfig("""
                economy:
                  start: 100
                  currency: coins
                """);
        YamlConfiguration yaml = service.load(file).orElseThrow();

        assertThat(service.entriesOf(yaml, "economy"))
                .extracting(ConfigEditService.Entry::path)
                .containsExactlyInAnyOrder("economy.start", "economy.currency");
    }

    @Test
    @DisplayName("the comment above a key is surfaced so the GUI can explain it")
    void readsComments() throws Exception {
        Path file = writeConfig("""
                # How many players may join at once.
                # Raising this needs more RAM.
                max-players: 20
                """);
        YamlConfiguration yaml = service.load(file).orElseThrow();

        ConfigEditService.Entry entry = service.entriesOf(yaml, "").getFirst();
        assertThat(entry.comment())
                .contains("How many players may join")
                .contains("more RAM");
    }

    @Test
    @DisplayName("saving keeps the comments in the file")
    void savePreservesComments() throws Exception {
        Path file = writeConfig("""
                # This comment must survive an edit.
                max-players: 20
                message: hello
                """);
        YamlConfiguration yaml = service.load(file).orElseThrow();

        ConfigEditService.SaveResult result = service.set(file, yaml, "max-players", 42);

        assertThat(result.success()).isTrue();
        String written = Files.readString(file);
        assertThat(written)
                .contains("This comment must survive an edit")
                .contains("max-players: 42")
                .contains("message: hello");
    }

    @Test
    @DisplayName("a backup is written before the file is changed")
    void writesBackupBeforeSaving() throws Exception {
        Path file = writeConfig("max-players: 20\n");
        YamlConfiguration yaml = service.load(file).orElseThrow();

        service.set(file, yaml, "max-players", 99);

        try (var entries = Files.list(dataFolder)) {
            List<Path> backups = entries
                    .filter(path -> path.getFileName().toString().contains(".apm-backup-"))
                    .toList();
            assertThat(backups).hasSize(1);
            // The backup holds the value from before the edit — that is the whole point.
            assertThat(Files.readString(backups.getFirst())).contains("max-players: 20");
        }
        assertThat(Files.readString(file)).contains("max-players: 99");
    }

    @Test
    @DisplayName("values keep their type instead of turning into strings")
    void writesTypedValues() throws Exception {
        Path file = writeConfig("max-players: 20\nenabled: false\n");
        YamlConfiguration yaml = service.load(file).orElseThrow();

        service.set(file, yaml, "max-players", 42);
        service.set(file, service.load(file).orElseThrow(), "enabled", true);

        String written = Files.readString(file);
        // Quoted values would be read back as strings by the owning plugin.
        assertThat(written).contains("max-players: 42").doesNotContain("max-players: '42'");
        assertThat(written).contains("enabled: true").doesNotContain("enabled: 'true'");
    }

    @Test
    @DisplayName("a list is written back as a list")
    void writesLists() throws Exception {
        Path file = writeConfig("worlds:\n  - world\n");
        YamlConfiguration yaml = service.load(file).orElseThrow();

        service.set(file, yaml, "worlds", List.of("world", "world_nether", "creative"));

        YamlConfiguration reloaded = service.load(file).orElseThrow();
        assertThat(reloaded.getStringList("worlds"))
                .containsExactly("world", "world_nether", "creative");
    }

    @Test
    @DisplayName("broken YAML is refused rather than opened and rewritten empty")
    void refusesBrokenYaml() throws Exception {
        Path file = writeConfig("this: [is not: closed\n  nor: valid\n");
        assertThat(service.load(file)).isEmpty();
    }

    @Test
    @DisplayName("typed input is parsed, wrong input is rejected instead of coerced")
    void parsesInputStrictly() {
        assertThat(service.parseAs(ConfigEditService.ValueKind.INTEGER, "42")).contains(42);
        assertThat(service.parseAs(ConfigEditService.ValueKind.INTEGER, "4.2")).isEmpty();
        assertThat(service.parseAs(ConfigEditService.ValueKind.INTEGER, "lots")).isEmpty();

        assertThat(service.parseAs(ConfigEditService.ValueKind.DOUBLE, "1.5")).contains(1.5);
        assertThat(service.parseAs(ConfigEditService.ValueKind.DOUBLE, "nope")).isEmpty();

        assertThat(service.parseAs(ConfigEditService.ValueKind.BOOLEAN, "yes")).contains(true);
        assertThat(service.parseAs(ConfigEditService.ValueKind.BOOLEAN, "OFF")).contains(false);
        assertThat(service.parseAs(ConfigEditService.ValueKind.BOOLEAN, "maybe")).isEmpty();

        assertThat(service.parseAs(ConfigEditService.ValueKind.STRING, "  hi  ")).contains("hi");

        // Structures cannot be typed into a chat prompt.
        assertThat(service.parseAs(ConfigEditService.ValueKind.LIST, "[a, b]")).isEmpty();
        assertThat(service.parseAs(ConfigEditService.ValueKind.SECTION, "{}")).isEmpty();
    }

    @Test
    @DisplayName("setting a value to null removes the key")
    void nullRemovesKey() throws Exception {
        Path file = writeConfig("keep: 1\ndrop: 2\n");
        YamlConfiguration yaml = service.load(file).orElseThrow();

        service.set(file, yaml, "drop", null);

        YamlConfiguration reloaded = service.load(file).orElseThrow();
        assertThat(reloaded.contains("drop")).isFalse();
        assertThat(reloaded.getInt("keep")).isEqualTo(1);
    }

    @Test
    @DisplayName("a file above the size cap is not offered for editing")
    void skipsOversizedFiles() throws Exception {
        Path big = dataFolder.resolve("huge.yml");
        Files.writeString(big, "key: " + "x".repeat(3 * 1024 * 1024) + "\n");

        assertThat(service.listConfigFiles("TestPlugin")).noneMatch(
                path -> path.toString().equals("huge.yml"));
        assertThat(service.resolve("TestPlugin", Path.of("huge.yml"))).isEmpty();
    }

    @Test
    @DisplayName("entriesOf on a path that is not a section yields nothing rather than throwing")
    void handlesNonSectionPath() throws Exception {
        Path file = writeConfig("scalar: 5\n");
        YamlConfiguration yaml = service.load(file).orElseThrow();
        assertThat(service.entriesOf(yaml, "scalar")).isEmpty();
        assertThat(service.entriesOf(yaml, "does.not.exist")).isEmpty();
    }

    @Test
    @DisplayName("a long string value is truncated for display but not in the file")
    void truncatesOnlyForDisplay() throws Exception {
        String longValue = "y".repeat(200);
        Path file = writeConfig("motd: " + longValue + "\n");
        YamlConfiguration yaml = service.load(file).orElseThrow();

        ConfigEditService.Entry entry = service.entriesOf(yaml, "").getFirst();
        assertThat(entry.display()).hasSizeLessThanOrEqualTo(60).endsWith("…");
        assertThat(Optional.of(entry.value()).map(String::valueOf).orElseThrow())
                .isEqualTo(longValue);
    }
}
