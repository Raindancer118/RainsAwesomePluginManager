package de.raindancer.apm.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.InstallDatabase;
import de.raindancer.apm.core.InstallService;
import de.raindancer.apm.core.ManagedPlugin;
import de.raindancer.apm.core.PluginLifecycleService;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Everything you can do to a single plugin, on one screen.
 *
 * <p>The plugin is looked up by name on every render rather than captured, because a disable or an
 * update replaces the {@link ManagedPlugin} snapshot — and holding the old one would show a stale
 * state and, worse, keep a dead plugin instance reachable.
 */
public final class PluginDetailMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private final String pluginName;

    public PluginDetailMenu(ApmService service, MenuManager menus, ApmMenu parent, String pluginName) {
        super(5, MenuManager.title("apm", pluginName));
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

        Optional<ManagedPlugin> lookup = service.find(pluginName);
        if (lookup.isEmpty()) {
            set(22, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.BAD + "><name> is gone", Msg.arg("name", pluginName))
                    .lore("It was removed or renamed while this")
                    .lore("screen was open.")
                    .build());
            drawBackOrClose(menus, size() - 1);
            return;
        }
        ManagedPlugin managed = lookup.get();

        set(4, infoIcon(managed).build());

        boolean isSelf = managed.name().equalsIgnoreCase("APM");
        boolean running = managed.state() == ManagedPlugin.State.ENABLED;

        // Enable
        set(19, Icon.of(running ? Material.GRAY_DYE : Material.LIME_DYE)
                .title((running ? "<" + Msg.MUTED + ">" : "<" + Msg.OK + ">") + "<bold>Enable</bold>")
                .lore(running ? "Already running." : "Turn it on now.")
                .lore(managed.state() == ManagedPlugin.State.NOT_LOADED
                        ? "APM will try a runtime load first."
                        : "")
                .blank()
                .action(running ? "Nothing to do" : "Enable <name>", Msg.arg("name", managed.name()))
                .build(), (player, click) -> {
                    if (running) {
                        return;
                    }
                    report(player, service.enable(managed));
                });

        // Disable (runtime)
        set(20, Icon.of(Material.LEVER)
                .title((isSelf ? "<" + Msg.MUTED + ">" : "<" + Msg.WARN + ">") + "<bold>Disable</bold>")
                .lore(isSelf ? "APM will not disable itself." : "Stop its tasks and listeners now.")
                .lore("Comes back on the next server start.")
                .lore("<" + Msg.WARN + ">Most plugins cannot be enabled again")
                .lore("<" + Msg.WARN + ">without a restart — they close their")
                .lore("<" + Msg.WARN + ">thread pools when disabled.")
                .blank()
                .action(isSelf ? "Blocked" : "Disable for this session")
                .build(), (player, click) -> {
                    if (isSelf) {
                        player.sendMessage(Msg.error("APM will not disable itself."));
                        return;
                    }
                    report(player, service.disable(managed, false));
                });

        // Disable (persistent)
        set(21, Icon.of(Material.RED_STAINED_GLASS_PANE)
                .title((isSelf ? "<" + Msg.MUTED + ">" : "<" + Msg.BAD + ">")
                        + "<bold>Disable permanently</bold>")
                .lore("Also parks the jar as .apm-disabled so")
                .lore("the server stops loading it entirely.")
                .lore("Reversible from this same screen.")
                .blank()
                .danger(isSelf ? "Blocked" : "Park <name>", Msg.arg("name", managed.name()))
                .build(), (player, click) -> {
                    if (isSelf) {
                        player.sendMessage(Msg.error("APM will not disable itself."));
                        return;
                    }
                    menus.open(player, new ConfirmMenu(menus, this,
                            "Park " + managed.name() + "?",
                            "Its jar is renamed so the server ignores it.",
                            "It will stay off until you enable it here again.",
                            null,
                            confirming -> {
                                report(confirming, service.disable(managed, true));
                                menus.open(confirming, this);
                            }));
                });

        // Reload
        set(22, Icon.of(Material.CLOCK)
                .title((managed.live().isPresent() && !isSelf ? "<" + Msg.ACCENT + ">" : "<" + Msg.MUTED + ">")
                        + "<bold>Reload</bold>")
                .lore("Disables and enables it again, re-running")
                .lore("its startup logic — which for most plugins")
                .lore("means their config is read fresh.")
                .lore("<" + Msg.WARN + ">This does not load a changed jar.")
                .lore("<" + Msg.WARN + ">Plugins that close resources on")
                .lore("<" + Msg.WARN + ">disable will stay down until a restart.")
                .blank()
                .action(managed.live().isPresent() && !isSelf ? "Reload now" : "Not available")
                .build(), (player, click) -> {
                    if (isSelf) {
                        player.sendMessage(Msg.error(
                                "APM cannot reload itself — use the config reload in the main menu."));
                        return;
                    }
                    report(player, service.reload(managed));
                });

        // Update
        Optional<InstallDatabase.Record> tracked = service.database().get(managed.name());
        set(23, Icon.of(tracked.isPresent() ? Material.ENDER_EYE : Material.GRAY_DYE)
                .title((tracked.isPresent() ? "<" + Msg.ACCENT + ">" : "<" + Msg.MUTED + ">")
                        + "<bold>Update</bold>")
                .lore(tracked.map(record -> "Re-runs the original query:").orElse("Not tracked by APM."))
                .lore(tracked.map(InstallDatabase.Record::query).orElse("Install it once through APM"))
                .lore(tracked.isPresent() ? "" : "to make updates possible.")
                .blank()
                .action(tracked.isPresent() ? "Check for a newer build" : "Not available")
                .build(), (player, click) -> {
                    if (tracked.isEmpty()) {
                        player.sendMessage(Msg.warn("<name> was not installed through APM, so APM "
                                + "does not know where to fetch updates from.",
                                Msg.arg("name", managed.name())));
                        return;
                    }
                    player.sendMessage(Msg.info("Checking for a newer build of <name>…",
                            Msg.arg("name", managed.name())));
                    service.updateAsync(managed, result -> handleUpdate(player, result));
                });

        // Edit configuration
        int configFiles = service.configs().listConfigFiles(managed.name()).size();
        set(28, Icon.of(configFiles > 0 ? Material.WRITABLE_BOOK : Material.GRAY_DYE)
                .title((configFiles > 0 ? "<" + Msg.ACCENT + ">" : "<" + Msg.MUTED + ">")
                        + "<bold>Edit configuration</bold>")
                .lore(configFiles > 0
                        ? "<count> YAML file<s> in plugins/<name>/"
                        : "No YAML file found in plugins/<name>/.",
                        Msg.arg("count", String.valueOf(configFiles)),
                        Msg.arg("s", configFiles == 1 ? "" : "s"),
                        Msg.arg("name", managed.name()))
                .lore(configFiles > 0
                        ? "Browse and change values in place."
                        : "Enable the plugin once so it writes its defaults.")
                .lore(configFiles > 0 ? "Comments are kept, backups are automatic." : "")
                .blank()
                .action(configFiles > 0 ? "Open the config browser" : "Nothing to edit")
                .amount(Math.max(1, configFiles))
                .build(), (player, click) -> {
                    if (configFiles == 0) {
                        player.sendMessage(Msg.warn("<name> has no editable YAML file yet.",
                                Msg.arg("name", managed.name())));
                        return;
                    }
                    menus.open(player, new ConfigFileMenu(service, menus, this, managed.name()));
                });

        // Remove
        set(30, Icon.of(Material.TNT)
                .title((isSelf ? "<" + Msg.MUTED + ">" : "<" + Msg.BAD + ">") + "<bold>Uninstall</bold>")
                .lore("Deletes the jar, keeps the config folder.")
                .blank()
                .danger(isSelf ? "Blocked" : "Uninstall <name>", Msg.arg("name", managed.name()))
                .build(), (player, click) -> {
                    if (isSelf) {
                        player.sendMessage(Msg.error("APM will not uninstall itself."));
                        return;
                    }
                    menus.open(player, new ConfirmMenu(menus, this,
                            "Uninstall " + managed.displayName() + "?",
                            "Its jar is deleted. The config folder stays.",
                            "Loaded classes remain until the next restart.",
                            null,
                            confirming -> {
                                InstallService.InstallReport removal = service.remove(managed, false);
                                sendReport(confirming, removal.success(), removal.message());
                                menus.open(confirming, parent);
                            }));
                });

        // Purge
        set(32, Icon.of(Material.BUCKET)
                .title((isSelf ? "<" + Msg.MUTED + ">" : "<" + Msg.BAD + ">") + "<bold>Purge</bold>")
                .lore("Deletes the jar <" + Msg.BAD + ">and</" + Msg.MUTED + "> its whole data folder,")
                .lore("including every config and database file")
                .lore("the plugin ever wrote.")
                .lore("<" + Msg.BAD + ">This cannot be undone.")
                .blank()
                .danger(isSelf ? "Blocked" : "Purge <name> completely", Msg.arg("name", managed.name()))
                .build(), (player, click) -> {
                    if (isSelf) {
                        player.sendMessage(Msg.error("APM will not purge itself."));
                        return;
                    }
                    menus.open(player, new ConfirmMenu(menus, this,
                            "Purge " + managed.displayName() + "?",
                            "The jar AND plugins/" + managed.name() + "/ will be deleted.",
                            "Every setting and stored record is lost.",
                            "There is no undo and no backup.",
                            confirming -> menus.open(confirming, new ConfirmMenu(menus, parent,
                                    "Really purge " + managed.name() + "? Last chance.",
                                    "Confirming a second time deletes the data folder.",
                                    null, null,
                                    reallyConfirming -> {
                                        InstallService.InstallReport removal =
                                                service.remove(managed, true);
                                        sendReport(reallyConfirming, removal.success(), removal.message());
                                        menus.open(reallyConfirming, parent);
                                    }))));
                });

        drawBackOrClose(menus, size() - 1);
    }

    private Icon infoIcon(ManagedPlugin managed) {
        Icon icon = Icon.of(Material.BOOK)
                .title("<" + Msg.ACCENT + "><bold><name></bold>", Msg.arg("name", managed.name()))
                .lore("Version <version>",
                        Msg.arg("version", managed.version() == null ? "unknown" : managed.version()))
                .lore("State: <state>", Msg.arg("state", managed.state().label()))
                .lore(List.of(PluginListMenu.compatibilityLore(service.compatibilityOf(managed))));

        managed.meta().ifPresent(meta -> {
            icon.lore("Descriptor: <file>", Msg.arg("file", meta.descriptor().fileName()));
            if (!meta.authors().isEmpty()) {
                icon.lore("By <authors>", Msg.arg("authors", String.join(", ", meta.authors())));
            }
            if (!meta.dependencies().isEmpty()) {
                icon.lore("Needs <deps>", Msg.arg("deps", String.join(", ", meta.dependencies())));
            }
            icon.lore(Msg.wrapLore(meta.description(), 44, Msg.MUTED));
        });

        if (managed.hasJar()) {
            icon.lore("File: <file>", Msg.arg("file", managed.jar().getFileName().toString()));
        }

        service.database().get(managed.name()).ifPresent(record -> {
            icon.blank();
            icon.lore("Installed by APM from <source>", Msg.arg("source", record.source()));
            icon.lore("Query: <query>", Msg.arg("query", record.query()));
            icon.lore("<ago> ago", Msg.arg("ago", humanise(record.installedAt())));
        });

        List<ManagedPlugin> dependents = service.registry().dependentsOf(managed.name());
        if (!dependents.isEmpty()) {
            icon.blank();
            icon.lore("<" + Msg.WARN + ">Required by <names>",
                    Msg.arg("names", dependents.stream().map(ManagedPlugin::name)
                            .reduce((a, b) -> a + ", " + b).orElse("")));
        }
        return icon;
    }

    private void handleUpdate(Player player, ApmService.PrepareResult result) {
        if (!player.isOnline()) {
            if (result instanceof ApmService.PrepareResult.Ready ready) {
                service.discard(ready.staged());
            } else if (result instanceof ApmService.PrepareResult.NeedsConfirmation needs) {
                service.discard(needs.staged());
            }
            return;
        }
        switch (result) {
            case ApmService.PrepareResult.Failed failed ->
                    player.sendMessage(Msg.error("<detail>", Msg.arg("detail", failed.message())));
            case ApmService.PrepareResult.Ready ready -> {
                Optional<ManagedPlugin> current = service.find(pluginName);
                String currentVersion = current.map(ManagedPlugin::version).orElse(null);
                String candidateVersion = ready.staged().meta().version();
                if (currentVersion != null && currentVersion.equals(candidateVersion)) {
                    service.discard(ready.staged());
                    player.sendMessage(Msg.success("<name> is already at the newest build (<version>).",
                            Msg.arg("name", pluginName), Msg.arg("version", currentVersion)));
                    return;
                }
                menus.open(player, new ConfirmMenu(menus, this,
                        "Update " + pluginName + "?",
                        (currentVersion == null ? "unknown" : currentVersion) + " → "
                                + (candidateVersion == null ? "unknown" : candidateVersion),
                        "The new jar is downloaded and verified.",
                        "A restart is needed for it to take effect.",
                        confirming -> {
                            InstallService.InstallReport report = service.commit(ready.staged());
                            sendReport(confirming, report.success(), report.message());
                            menus.open(confirming, this);
                        },
                        cancelling -> service.discard(ready.staged())));
            }
            case ApmService.PrepareResult.NeedsConfirmation needs ->
                    menus.open(player, new ConfirmMenu(menus, this,
                            "The newest build is not compatible. Install anyway?",
                            needs.warning(), "It may fail to load.", null,
                            confirming -> {
                                InstallService.InstallReport report = service.commit(needs.staged());
                                sendReport(confirming, report.success(), report.message());
                                menus.open(confirming, this);
                            },
                            cancelling -> service.discard(needs.staged())));
        }
    }

    private void report(Player player, PluginLifecycleService.Outcome outcome) {
        sendReport(player, outcome.success(), outcome.message());
        refresh();
    }

    private static void sendReport(Player player, boolean success, String message) {
        player.sendMessage(success
                ? Msg.success("<detail>", Msg.arg("detail", message))
                : Msg.error("<detail>", Msg.arg("detail", message)));
    }

    private static String humanise(Instant instant) {
        Duration age = Duration.between(instant, Instant.now());
        if (age.toDays() > 0) {
            return age.toDays() + "d";
        }
        if (age.toHours() > 0) {
            return age.toHours() + "h";
        }
        return Math.max(1, age.toMinutes()) + "m";
    }
}
