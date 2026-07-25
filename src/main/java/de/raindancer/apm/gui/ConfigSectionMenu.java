package de.raindancer.apm.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.ConfigEditService;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * One level of a YAML document: sections to descend into, values to change.
 *
 * <p>Interaction follows what the value actually is, which is the whole point of parsing the file
 * instead of showing raw text: booleans toggle on click, numbers and strings open a chat prompt
 * pre-filled by the current value, lists get their own screen, sections open a child menu.
 *
 * <p>The file is re-read on every render rather than held open, so an edit made in a text editor
 * or by the plugin itself is never silently overwritten by a stale in-memory copy.
 */
public final class ConfigSectionMenu extends ApmMenu {

    private static final int PER_PAGE = 45;

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private final String pluginName;
    private final Path relativeFile;
    private final String sectionPath;
    private int page;

    public ConfigSectionMenu(ApmService service, MenuManager menus, ApmMenu parent,
                             String pluginName, Path relativeFile, String sectionPath) {
        super(6, MenuManager.title("apm", sectionPath.isEmpty()
                ? relativeFile.toString()
                : trim(sectionPath)));
        this.service = service;
        this.menus = menus;
        this.parent = parent;
        this.pluginName = pluginName;
        this.relativeFile = relativeFile;
        this.sectionPath = sectionPath;
    }

    @Override
    public ApmMenu parent() {
        return parent;
    }

    @Override
    public void render() {
        clear();
        fillFooterBackground();

        Optional<Path> file = service.configs().resolve(pluginName, relativeFile);
        if (file.isEmpty()) {
            showProblem("The file is gone", "It was deleted or moved while this screen was open.");
            return;
        }
        Optional<YamlConfiguration> loaded = service.configs().load(file.get());
        if (loaded.isEmpty()) {
            showProblem("Not valid YAML",
                    "APM will not open an editor on a file it cannot parse — saving would wipe it.");
            return;
        }

        YamlConfiguration yaml = loaded.get();
        List<ConfigEditService.Entry> all = service.configs().entriesOf(yaml, sectionPath);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        List<ConfigEditService.Entry> visible =
                all.subList(page * PER_PAGE, Math.min(all.size(), page * PER_PAGE + PER_PAGE));

        if (all.isEmpty()) {
            set(22, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.MUTED + ">Nothing here")
                    .lore(sectionPath.isEmpty()
                            ? "The file is empty."
                            : "This section has no entries.")
                    .build());
        }

        int slot = 0;
        for (ConfigEditService.Entry entry : visible) {
            set(slot++, iconFor(entry).build(),
                    (player, click) -> handleClick(player, file.get(), yaml, entry));
        }

        if (page > 0) {
            set(size() - 9, Icon.of(Material.ARROW).title("<" + Msg.ACCENT + ">Previous page").build(),
                    (player, click) -> {
                        page--;
                        refresh();
                    });
        }
        if (page < pages - 1) {
            set(size() - 3, Icon.of(Material.ARROW).title("<" + Msg.ACCENT + ">Next page").build(),
                    (player, click) -> {
                        page++;
                        refresh();
                    });
        }

        set(size() - 5, Icon.of(Material.BOOK)
                .title("<" + Msg.MUTED + "><path>",
                        Msg.arg("path", sectionPath.isEmpty()
                                ? relativeFile + " (root)"
                                : relativeFile + " › " + sectionPath))
                .lore("<count> entr<y> · page <page>/<pages>",
                        Msg.arg("count", String.valueOf(all.size())),
                        Msg.arg("y", all.size() == 1 ? "y" : "ies"),
                        Msg.arg("page", String.valueOf(page + 1)),
                        Msg.arg("pages", String.valueOf(pages)))
                .blank()
                .lore("Every save writes a backup next to the file.")
                .lore("Reload the plugin afterwards to apply.")
                .build());

        drawBackOrClose(menus, size() - 1);
    }

    private Icon iconFor(ConfigEditService.Entry entry) {
        Material material = switch (entry.kind()) {
            case SECTION -> Material.CHEST;
            case BOOLEAN -> Boolean.TRUE.equals(entry.value())
                    ? Material.LIME_DYE : Material.GRAY_DYE;
            case INTEGER, DOUBLE -> Material.COMPASS;
            case LIST -> Material.HOPPER;
            case STRING -> Material.PAPER;
            case UNSUPPORTED -> Material.BARRIER;
        };
        String colour = switch (entry.kind()) {
            case SECTION -> Msg.ACCENT;
            case BOOLEAN -> Boolean.TRUE.equals(entry.value()) ? Msg.OK : Msg.MUTED;
            case UNSUPPORTED -> Msg.BAD;
            default -> Msg.TEXT;
        };

        Icon icon = Icon.of(material)
                .title("<" + colour + "><bold><key></bold>", Msg.arg("key", entry.key()))
                .lore("<" + Msg.TEXT + "><value>", Msg.arg("value", entry.display()));

        if (!entry.comment().isBlank()) {
            icon.blank();
            icon.lore(Msg.wrapLore(entry.comment(), 44, Msg.MUTED));
        }
        icon.blank();

        switch (entry.kind()) {
            case SECTION -> icon.action("Open this section");
            case BOOLEAN -> icon.action("Click to toggle to <target>",
                    Msg.arg("target", Boolean.TRUE.equals(entry.value()) ? "false" : "true"));
            case INTEGER -> icon.action("Click to type a new whole number");
            case DOUBLE -> icon.action("Click to type a new number");
            case STRING -> icon.action("Click to type new text");
            case LIST -> icon.action("Click to edit the list");
            case UNSUPPORTED -> icon.lore("<" + Msg.BAD + ">APM cannot edit this value type safely.");
        }
        return icon;
    }

    private void handleClick(Player player, Path file, YamlConfiguration yaml,
                             ConfigEditService.Entry entry) {
        switch (entry.kind()) {
            case SECTION -> menus.open(player, new ConfigSectionMenu(
                    service, menus, this, pluginName, relativeFile, entry.path()));

            case LIST -> menus.open(player, new ConfigListMenu(
                    service, menus, this, pluginName, relativeFile, entry.path()));

            case BOOLEAN -> {
                boolean flipped = !Boolean.TRUE.equals(entry.value());
                apply(player, file, yaml, entry.path(), flipped);
            }

            case INTEGER, DOUBLE, STRING -> {
                player.sendMessage(Msg.info("<path> is currently <" + Msg.ACCENT + "><value>",
                        Msg.arg("path", entry.path()), Msg.arg("value", String.valueOf(entry.value()))));
                menus.promptForText(player, describePrompt(entry.kind()), input -> {
                    Optional<Object> parsed = service.configs().parseAs(entry.kind(), input);
                    if (parsed.isEmpty()) {
                        player.sendMessage(Msg.error("'<input>' is not a valid <kind>. "
                                + "Nothing was changed.",
                                Msg.arg("input", input),
                                Msg.arg("kind", entry.kind().name().toLowerCase(java.util.Locale.ROOT))));
                        menus.open(player, this);
                        return;
                    }
                    // Re-read before writing: the prompt gave the player time to change things.
                    Optional<YamlConfiguration> fresh = service.configs().load(file);
                    if (fresh.isEmpty()) {
                        player.sendMessage(Msg.error("The file became unreadable — nothing was changed."));
                        menus.open(player, this);
                        return;
                    }
                    apply(player, file, fresh.get(), entry.path(), parsed.get());
                    menus.open(player, this);
                }, () -> menus.open(player, this));
            }

            case UNSUPPORTED -> player.sendMessage(Msg.warn(
                    "APM cannot safely edit <path> — its value is a type a chest GUI has no editor for.",
                    Msg.arg("path", entry.path())));
        }
    }

    private void apply(Player player, Path file, YamlConfiguration yaml, String path, Object value) {
        ConfigEditService.SaveResult result = service.configs().set(file, yaml, path, value);
        player.sendMessage(result.success()
                ? Msg.success("<path> = <value>. <detail>",
                        Msg.arg("path", path), Msg.arg("value", String.valueOf(value)),
                        Msg.arg("detail", result.message()))
                : Msg.error("<detail>", Msg.arg("detail", result.message())));
        if (result.success()) {
            player.sendMessage(Msg.info("Reload <plugin> to apply it — or restart the server if it "
                            + "does not survive a reload.", Msg.arg("plugin", pluginName)));
        }
        refresh();
    }

    private static String describePrompt(ConfigEditService.ValueKind kind) {
        return switch (kind) {
            case INTEGER -> "a whole number";
            case DOUBLE -> "a number";
            default -> "the new text";
        };
    }

    private void showProblem(String title, String detail) {
        set(22, Icon.of(Material.BARRIER)
                .title("<" + Msg.BAD + "><title>", Msg.arg("title", title))
                .lore(Msg.wrapLore(detail, 44, Msg.MUTED))
                .build());
        drawBackOrClose(menus, size() - 1);
    }

    /** Inventory titles have no scrolling, so a deep path is shown from its tail. */
    private static String trim(String path) {
        return path.length() <= 24 ? path : "…" + path.substring(path.length() - 23);
    }
}
