package de.raindancer.apm.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.ConfigEditService;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Editor for a YAML list — world names, blacklisted materials, allowed commands.
 *
 * <p>Lists are where a naive "edit the whole file as text" approach falls apart in a chest GUI, so
 * they get first-class treatment: append, remove and reorder, each as a single click.
 */
public final class ConfigListMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private final String pluginName;
    private final Path relativeFile;
    private final String listPath;

    public ConfigListMenu(ApmService service, MenuManager menus, ApmMenu parent,
                          String pluginName, Path relativeFile, String listPath) {
        super(6, MenuManager.title("apm", "list · " + trim(listPath)));
        this.service = service;
        this.menus = menus;
        this.parent = parent;
        this.pluginName = pluginName;
        this.relativeFile = relativeFile;
        this.listPath = listPath;
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
        Optional<YamlConfiguration> loaded = file.flatMap(f -> service.configs().load(f));
        if (file.isEmpty() || loaded.isEmpty()) {
            set(22, Icon.of(Material.BARRIER)
                    .title("<" + Msg.BAD + ">The file is no longer readable")
                    .build());
            drawBackOrClose(menus, size() - 1);
            return;
        }

        YamlConfiguration yaml = loaded.get();
        List<?> values = yaml.getList(listPath);
        if (values == null) {
            set(22, Icon.of(Material.BARRIER)
                    .title("<" + Msg.BAD + "><path> is no longer a list", Msg.arg("path", listPath))
                    .build());
            drawBackOrClose(menus, size() - 1);
            return;
        }

        if (values.isEmpty()) {
            set(22, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.MUTED + ">The list is empty")
                    .lore("Use the button below to add the first entry.")
                    .build());
        }

        int slot = 0;
        for (int index = 0; index < values.size() && slot < size() - 9; index++, slot++) {
            int position = index;
            Object value = values.get(index);
            boolean editable = isScalar(value);

            Icon icon = Icon.of(editable ? Material.PAPER : Material.BARRIER)
                    .title("<" + (editable ? Msg.TEXT : Msg.BAD) + "><index>. <value>",
                            Msg.arg("index", String.valueOf(index + 1)),
                            Msg.arg("value", shorten(String.valueOf(value))))
                    .blank();
            if (editable) {
                icon.action("Left click to change the text");
                icon.action("Right click to move it up");
                icon.danger("Shift + left click to remove it");
            } else {
                icon.lore("<" + Msg.BAD + ">Nested structures cannot be edited here.");
            }

            set(slot, icon.build(), (player, click) -> {
                if (!editable) {
                    player.sendMessage(Msg.warn("That entry is a nested structure — APM will not "
                            + "edit it in a chest GUI."));
                    return;
                }
                if (click.isShiftClick() && click.isLeftClick()) {
                    List<Object> updated = new ArrayList<>(values);
                    Object removed = updated.remove(position);
                    write(player, file.get(), updated,
                            "Removed '" + removed + "' from " + listPath + ".");
                    return;
                }
                if (click.isRightClick()) {
                    if (position == 0) {
                        player.sendMessage(Msg.warn("That entry is already first."));
                        return;
                    }
                    List<Object> updated = new ArrayList<>(values);
                    Object moved = updated.remove(position);
                    updated.add(position - 1, moved);
                    write(player, file.get(), updated,
                            "Moved '" + moved + "' up in " + listPath + ".");
                    return;
                }
                menus.promptForText(player, "the replacement text (current: " + value + ")",
                        input -> {
                            Optional<Path> fresh = service.configs().resolve(pluginName, relativeFile);
                            Optional<YamlConfiguration> freshYaml =
                                    fresh.flatMap(f -> service.configs().load(f));
                            if (fresh.isEmpty() || freshYaml.isEmpty()) {
                                player.sendMessage(Msg.error("The file became unreadable — "
                                        + "nothing was changed."));
                                menus.open(player, this);
                                return;
                            }
                            List<?> current = freshYaml.get().getList(listPath);
                            if (current == null || position >= current.size()) {
                                player.sendMessage(Msg.error("The list changed underneath — "
                                        + "nothing was written."));
                                menus.open(player, this);
                                return;
                            }
                            List<Object> updated = new ArrayList<>(current);
                            updated.set(position, coerce(updated.get(position), input));
                            write(player, fresh.get(), updated,
                                    "Entry " + (position + 1) + " of " + listPath + " updated.");
                            menus.open(player, this);
                        }, () -> menus.open(player, this));
            });
        }

        set(size() - 6, Icon.of(Material.LIME_DYE)
                .title("<" + Msg.OK + "><bold>Add an entry</bold>")
                .lore("Appends a new value to the end of the list.")
                .blank()
                .action("Type the new entry")
                .build(), (player, click) -> menus.promptForText(player, "the new entry",
                        input -> {
                            Optional<Path> fresh = service.configs().resolve(pluginName, relativeFile);
                            Optional<YamlConfiguration> freshYaml =
                                    fresh.flatMap(f -> service.configs().load(f));
                            if (fresh.isEmpty() || freshYaml.isEmpty()) {
                                player.sendMessage(Msg.error("The file became unreadable — "
                                        + "nothing was changed."));
                                menus.open(player, this);
                                return;
                            }
                            List<?> current = freshYaml.get().getList(listPath);
                            List<Object> updated =
                                    new ArrayList<>(current == null ? List.of() : current);
                            updated.add(coerce(updated.isEmpty() ? "" : updated.getFirst(), input));
                            write(player, fresh.get(), updated,
                                    "Added '" + input + "' to " + listPath + ".");
                            menus.open(player, this);
                        }, () -> menus.open(player, this)));

        set(size() - 5, Icon.of(Material.BOOK)
                .title("<" + Msg.MUTED + "><path>", Msg.arg("path", listPath))
                .lore("<count> entr<y> in <file>",
                        Msg.arg("count", String.valueOf(values.size())),
                        Msg.arg("y", values.size() == 1 ? "y" : "ies"),
                        Msg.arg("file", relativeFile.toString()))
                .blank()
                .lore("Every change writes a backup first.")
                .lore("Reload <plugin> afterwards to apply.", Msg.arg("plugin", pluginName))
                .build());

        drawBackOrClose(menus, size() - 1);
    }

    private void write(Player player, Path file, List<Object> values, String what) {
        Optional<YamlConfiguration> yaml = service.configs().load(file);
        if (yaml.isEmpty()) {
            player.sendMessage(Msg.error("The file became unreadable — nothing was changed."));
            return;
        }
        ConfigEditService.SaveResult result =
                service.configs().set(file, yaml.get(), listPath, values);
        player.sendMessage(result.success()
                ? Msg.success("<what> <detail>", Msg.arg("what", what),
                        Msg.arg("detail", result.message()))
                : Msg.error("<detail>", Msg.arg("detail", result.message())));
        refresh();
    }

    /**
     * Keeps a list homogeneous: if the existing entries are numbers, a typed value that parses as
     * a number is stored as one rather than as a string.
     */
    private static Object coerce(Object existing, String input) {
        String trimmed = input.trim();
        if (existing instanceof Integer || existing instanceof Long) {
            try {
                return Integer.valueOf(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        if (existing instanceof Double || existing instanceof Float) {
            try {
                return Double.valueOf(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        if (existing instanceof Boolean) {
            if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
                return Boolean.valueOf(trimmed);
            }
            return trimmed;
        }
        return trimmed;
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character;
    }

    private static String shorten(String text) {
        return text.length() <= 40 ? text : text.substring(0, 37) + "…";
    }

    private static String trim(String path) {
        return path.length() <= 22 ? path : "…" + path.substring(path.length() - 21);
    }
}
