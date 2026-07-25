package de.raindancer.apm.gui;

import java.nio.file.Path;
import java.util.List;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * The YAML files a plugin keeps in its data folder, ready to be opened for editing.
 *
 * <p>Most plugins have exactly one {@code config.yml}, which is why that file is pulled to the
 * front and highlighted.
 */
public final class ConfigFileMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private final String pluginName;

    public ConfigFileMenu(ApmService service, MenuManager menus, ApmMenu parent, String pluginName) {
        super(4, MenuManager.title("apm", pluginName + " · config"));
        this.service = service;
        this.menus = menus;
        this.parent = parent;
        this.pluginName = pluginName;
    }

    @Override
    public ApmMenu parent() {
        return parent;
    }

    @Override
    public void render() {
        clear();
        fillFooterBackground();

        List<Path> files = service.configs().listConfigFiles(pluginName);
        if (files.isEmpty()) {
            set(13, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.MUTED + ">No YAML files")
                    .lore("plugins/<name>/ holds no .yml file APM", Msg.arg("name", pluginName))
                    .lore("can edit. Either the plugin stores its")
                    .lore("settings elsewhere, or it has not written")
                    .lore("its defaults yet — enable it once first.")
                    .build());
            drawBackOrClose(menus, size() - 1);
            return;
        }

        // config.yml first, then everything else alphabetically.
        List<Path> ordered = files.stream()
                .sorted(java.util.Comparator
                        .comparing((Path path) -> !path.toString().equalsIgnoreCase("config.yml"))
                        .thenComparing(Path::toString))
                .toList();

        int slot = 0;
        for (Path relative : ordered) {
            if (slot >= size() - 9) {
                break;
            }
            boolean isMain = relative.toString().equalsIgnoreCase("config.yml");
            set(slot++, Icon.of(isMain ? Material.WRITABLE_BOOK : Material.PAPER)
                    .title("<" + (isMain ? Msg.ACCENT : Msg.TEXT) + "><bold><file></bold>",
                            Msg.arg("file", relative.toString()))
                    .lore(isMain ? "The plugin's main configuration." : "")
                    .lore("Comments are preserved and a timestamped")
                    .lore("backup is written before every change.")
                    .blank()
                    .action("Open")
                    .build(), (player, click) -> menus.open(player,
                            new ConfigSectionMenu(service, menus, this, pluginName, relative, "")));
        }

        set(size() - 5, Icon.of(Material.BOOK)
                .title("<" + Msg.MUTED + "><count> file<s> in plugins/<name>/",
                        Msg.arg("count", String.valueOf(files.size())),
                        Msg.arg("s", files.size() == 1 ? "" : "s"),
                        Msg.arg("name", pluginName))
                .lore("Most plugins only read their config at")
                .lore("startup, so use <" + Msg.ACCENT + ">Reload</" + Msg.MUTED + "> on the plugin")
                .lore("afterwards to make changes take effect.")
                .build());

        drawBackOrClose(menus, size() - 1);
    }
}
