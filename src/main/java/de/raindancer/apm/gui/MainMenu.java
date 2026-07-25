package de.raindancer.apm.gui;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.ManagedPlugin;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * APM's root screen: one entry point per feature area.
 */
public final class MainMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;

    public MainMenu(ApmService service, MenuManager menus) {
        super(5, MenuManager.title("apm", "Plugin Manager"));
        this.service = service;
        this.menus = menus;
    }

    @Override
    public void render() {
        clear();
        fillFooterBackground();

        var plugins = service.list();
        long enabled = plugins.stream()
                .filter(plugin -> plugin.state() == ManagedPlugin.State.ENABLED).count();
        long off = plugins.size() - enabled;
        int pendingCount = service.pending().snapshot().size();

        set(11, Icon.of(Material.CHEST)
                .title("<" + Msg.ACCENT + "><bold>Installed plugins</bold>")
                .lore("<count> plugin<s> known to APM", Msg.arg("count", String.valueOf(plugins.size())),
                        Msg.arg("s", plugins.size() == 1 ? "" : "s"))
                .lore("<" + Msg.OK + "><on> running</" + Msg.OK + ">, <" + Msg.MUTED + "><off> not",
                        Msg.arg("on", String.valueOf(enabled)), Msg.arg("off", String.valueOf(off)))
                .blank()
                .action("Browse, enable, disable, reload, remove")
                .build(), (player, click) -> menus.open(player, new PluginListMenu(service, menus, this, 0)));

        set(13, Icon.of(Material.SPYGLASS)
                .title("<" + Msg.ACCENT + "><bold>Search for plugins</bold>")
                .lore("Search the Modrinth catalogue for")
                .lore("plugins that fit Minecraft <version>",
                        Msg.arg("version", service.serverVersion()))
                .blank()
                .action("Type a search term and install from the results")
                .build(), (player, click) -> menus.promptForText(player, "a search term",
                        term -> service.searchAsync(term, (hits, error) -> {
                            if (error != null) {
                                player.sendMessage(Msg.error("Search failed: <detail>",
                                        Msg.arg("detail", error)));
                                menus.open(player, this);
                                return;
                            }
                            if (hits.isEmpty()) {
                                player.sendMessage(Msg.warn("Nothing found for <term>.",
                                        Msg.arg("term", term)));
                                menus.open(player, this);
                                return;
                            }
                            menus.open(player, new SearchResultsMenu(service, menus, this, term, hits));
                        }),
                        () -> menus.open(player, this)));

        set(15, Icon.of(Material.HOPPER)
                .title("<" + Msg.ACCENT + "><bold>Install from URL</bold>")
                .lore("Paste a direct link to a plugin jar.")
                .lore("APM verifies the download, reads its")
                .lore("descriptor and checks the API version")
                .lore("before anything touches the plugins folder.")
                .blank()
                .action("Type an https:// link to a .jar")
                .build(), (player, click) -> menus.promptForText(player, "an https:// link to a .jar",
                        url -> InstallFlow.start(service, menus, this, player, url),
                        () -> menus.open(player, this)));

        set(29, Icon.of(Material.CLOCK)
                .title((pendingCount > 0 ? "<" + Msg.WARN + ">" : "<" + Msg.TEXT + ">")
                        + "<bold>Pending file operations</bold>")
                .lore(pendingCount == 0
                        ? "Nothing is waiting."
                        : "<count> operation<s> will run at shutdown.")
                .lore("Jars whose classes are still loaded")
                .lore("cannot be deleted or renamed right away.")
                .blank()
                .action("Review the queue")
                .amount(Math.max(1, pendingCount))
                .build(), (player, click) -> menus.open(player, new PendingMenu(service, menus, this)));

        set(31, Icon.of(Material.REDSTONE_TORCH)
                .title("<" + Msg.WARN + "><bold>Restart the server</bold>")
                .lore(service.restarts().isCountingDown()
                        ? "A restart is already counting down."
                        : "Needed to activate newly installed")
                .lore(service.restarts().isCountingDown()
                        ? "Click to cancel it."
                        : "or updated plugins.")
                .blank()
                .danger(service.restarts().isCountingDown()
                        ? "Cancel the countdown"
                        : "Start a countdown and restart")
                .build(), (player, click) -> {
                    if (service.restarts().isCountingDown()) {
                        service.restarts().cancel();
                        refresh();
                        return;
                    }
                    menus.open(player, new ConfirmMenu(menus, this,
                            "Restart the server?",
                            "Everyone online will be disconnected.",
                            "The server only comes back up if it was",
                            "started through a restart wrapper script.",
                            confirmingPlayer -> {
                                service.restarts().start(-1, "requested by " + confirmingPlayer.getName());
                                confirmingPlayer.closeInventory();
                            }));
                });

        set(33, Icon.of(Material.KNOWLEDGE_BOOK)
                .title("<" + Msg.ACCENT + "><bold>Edit plugin configs</bold>")
                .lore("Browse any plugin's YAML files and change")
                .lore("values in place — booleans toggle, numbers")
                .lore("and text open a prompt, lists get an editor.")
                .lore("Comments survive, backups are automatic.")
                .blank()
                .action("Pick a plugin to configure")
                .build(), (player, click) -> menus.open(player,
                        new ConfigPluginPickerMenu(service, menus, this, 0)));

        set(34, Icon.of(Material.WRITABLE_BOOK)
                .title("<" + Msg.ACCENT + "><bold>Reload APM's config</bold>")
                .lore("Re-reads config.yml: download limits,")
                .lore("allowed hosts, restart countdown.")
                .blank()
                .action("Reload now")
                .build(), (player, click) -> {
                    service.reloadOwnConfig();
                    player.sendMessage(Msg.success("APM's configuration was reloaded."));
                    refresh();
                });

        set(size() - 5, Icon.of(Material.PAPER)
                .title("<" + Msg.MUTED + ">Server: Minecraft <version>",
                        Msg.arg("version", service.serverVersion()))
                .lore("Every action here has a command equivalent.")
                .lore("Run <" + Msg.ACCENT + ">/apm help</" + Msg.ACCENT + "> to see them.")
                .build());

        drawBackOrClose(menus, size() - 1);
    }
}
