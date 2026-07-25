package de.raindancer.apm.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import de.raindancer.apm.ApmPlugin;
import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.InstallDatabase;
import de.raindancer.apm.core.InstallService;
import de.raindancer.apm.core.ManagedPlugin;
import de.raindancer.apm.core.PendingActions;
import de.raindancer.apm.core.PluginLifecycleService;
import de.raindancer.apm.gui.MainMenu;
import de.raindancer.apm.gui.MenuManager;
import de.raindancer.apm.util.Msg;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /apm} command tree.
 *
 * <p>Implemented as a Paper {@link BasicCommand} registered through the lifecycle API — the modern
 * replacement for {@code onCommand} plus a {@code commands:} block in the descriptor. Every
 * subcommand maps one to one onto an {@link ApmService} method, which is the same set of methods
 * the GUI drives.
 *
 * <p>Destructive subcommands ({@code remove}, {@code purge}, {@code restart}) require an explicit
 * {@code --yes} flag when run from the console or a command block, because those senders cannot be
 * shown a confirmation screen.
 */
public final class ApmCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of(
            "gui", "list", "info", "search", "install", "update", "enable", "disable",
            "reload", "config", "remove", "purge", "pending", "restart", "restartscript",
            "reloadconfig", "help");

    private final ApmPlugin plugin;
    private final ApmService service;
    private final MenuManager menus;

    public ApmCommand(ApmPlugin plugin, ApmService service, MenuManager menus) {
        this.plugin = plugin;
        this.service = service;
        this.menus = menus;
    }

    @Override
    public String permission() {
        return ApmPlugin.PERMISSION;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return plugin.hasAdminPermission(sender);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!plugin.hasAdminPermission(sender)) {
            sender.sendMessage(Msg.error("You need to be an operator to use APM."));
            return;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                menus.open(player, new MainMenu(service, menus));
            } else {
                help(sender);
            }
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "gui" -> gui(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, rest);
            case "search" -> search(sender, rest);
            case "install", "add" -> install(sender, rest);
            case "update", "upgrade" -> update(sender, rest);
            case "enable" -> enable(sender, rest);
            case "disable" -> disable(sender, rest);
            case "reload" -> reload(sender, rest);
            case "config" -> config(sender, rest);
            case "remove", "uninstall" -> remove(sender, rest, false);
            case "purge" -> remove(sender, rest, true);
            case "pending" -> pending(sender, rest);
            case "restart" -> restart(sender, rest);
            case "restartscript" -> restartScript(sender, rest);
            case "reloadconfig" -> reloadConfig(sender);
            case "help", "?" -> help(sender);
            default -> {
                sender.sendMessage(Msg.error("Unknown subcommand '<sub>'.", Msg.arg("sub", sub)));
                help(sender);
            }
        }
    }

    @Override
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!plugin.hasAdminPermission(source.getSender())) {
            return List.of();
        }
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(partial)).toList();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String partial = args[args.length - 1].toLowerCase(Locale.ROOT);

        return switch (sub) {
            case "info", "enable", "disable", "reload", "config", "remove", "purge", "update" -> args.length == 2
                    ? service.list().stream()
                            .map(ManagedPlugin::name)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                            .toList()
                    : flagSuggestions(sub, partial);
            case "pending" -> args.length == 2
                    ? List.of("list", "apply").stream().filter(o -> o.startsWith(partial)).toList()
                    : List.of();
            case "install" -> args.length == 2
                    ? List.of("https://", "modrinth:")
                    : List.of();
            case "restart" -> args.length == 2 ? List.of("--yes", "cancel", "0", "10", "30") : List.of();
            case "restartscript" -> args.length == 2
                    ? List.of("status", "create").stream().filter(o -> o.startsWith(partial)).toList()
                    : List.of();
            default -> List.of();
        };
    }

    private static List<String> flagSuggestions(String sub, String partial) {
        List<String> flags = new ArrayList<>();
        if (sub.equals("disable")) {
            flags.add("--permanent");
        }
        if (sub.equals("remove") || sub.equals("purge") || sub.equals("restart")) {
            flags.add("--yes");
        }
        return flags.stream().filter(flag -> flag.startsWith(partial)).toList();
    }

    // --- subcommands -----------------------------------------------------------------------

    private void gui(CommandSender sender) {
        if (sender instanceof Player player) {
            menus.open(player, new MainMenu(service, menus));
            return;
        }
        sender.sendMessage(Msg.error("The GUI needs a player. From the console, use the "
                + "subcommands — every menu action has one."));
    }

    private void list(CommandSender sender) {
        List<ManagedPlugin> all = service.list();
        sender.sendMessage(Msg.info("<count> plugin<s> on Minecraft <version>:",
                Msg.arg("count", String.valueOf(all.size())),
                Msg.arg("s", all.size() == 1 ? "" : "s"),
                Msg.arg("version", service.serverVersion())));

        for (ManagedPlugin managed : all) {
            String colour = switch (managed.state()) {
                case ENABLED -> Msg.OK;
                case DISABLED -> Msg.MUTED;
                case NOT_LOADED -> Msg.WARN;
                case PARKED -> Msg.BAD;
            };
            Component line = Msg.raw("  <" + colour + ">● </" + colour + "><" + Msg.TEXT + "><name></"
                            + Msg.TEXT + "> <" + Msg.MUTED + "><version> — <state>",
                    Msg.arg("name", managed.name()),
                    Msg.arg("version", managed.version() == null ? "?" : managed.version()),
                    Msg.arg("state", managed.state().label()))
                    .hoverEvent(HoverEvent.showText(Msg.raw("<" + Msg.ACCENT
                            + ">Click for details")))
                    .clickEvent(ClickEvent.runCommand("/apm info " + managed.name()));
            sender.sendMessage(line);
        }
        if (sender instanceof Player) {
            sender.sendMessage(clickable("Open the GUI", "/apm gui"));
        }
    }

    private void info(CommandSender sender, String[] args) {
        Optional<ManagedPlugin> lookup = requirePlugin(sender, args, "info");
        if (lookup.isEmpty()) {
            return;
        }
        ManagedPlugin managed = lookup.get();

        if (sender instanceof Player player) {
            menus.open(player, new de.raindancer.apm.gui.PluginDetailMenu(
                    service, menus, new MainMenu(service, menus), managed.name()));
            return;
        }

        sender.sendMessage(Msg.info("<name>", Msg.arg("name", managed.displayName())));
        sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">State: <state>",
                Msg.arg("state", managed.state().label())));
        sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">Compatibility: <detail>",
                Msg.arg("detail", service.compatibilityOf(managed).detail())));
        managed.meta().ifPresent(meta -> {
            sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">Descriptor: <file>, main <main>",
                    Msg.arg("file", meta.descriptor().fileName()),
                    Msg.arg("main", meta.mainClass())));
            if (!meta.authors().isEmpty()) {
                sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">Authors: <authors>",
                        Msg.arg("authors", String.join(", ", meta.authors()))));
            }
            if (!meta.dependencies().isEmpty()) {
                sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">Depends on: <deps>",
                        Msg.arg("deps", String.join(", ", meta.dependencies()))));
            }
        });
        if (managed.hasJar()) {
            sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">File: <file>",
                    Msg.arg("file", managed.jar().getFileName().toString())));
        }
        service.database().get(managed.name()).ifPresent(record -> sender.sendMessage(
                Msg.raw("  <" + Msg.MUTED + ">Installed from <source> via '<query>'",
                        Msg.arg("source", record.source()), Msg.arg("query", record.query()))));

        List<ManagedPlugin> dependents = service.registry().dependentsOf(managed.name());
        if (!dependents.isEmpty()) {
            sender.sendMessage(Msg.warn("Required by: <names>",
                    Msg.arg("names", dependents.stream().map(ManagedPlugin::name)
                            .reduce((a, b) -> a + ", " + b).orElse(""))));
        }
    }

    private void search(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Msg.error("Usage: /apm search <term>"));
            return;
        }
        String term = String.join(" ", args);
        sender.sendMessage(Msg.info("Searching for <term>…", Msg.arg("term", term)));

        service.searchAsync(term, (hits, error) -> {
            if (error != null) {
                sender.sendMessage(Msg.error("Search failed: <detail>", Msg.arg("detail", error)));
                return;
            }
            if (hits.isEmpty()) {
                sender.sendMessage(Msg.warn("Nothing found for <term>.", Msg.arg("term", term)));
                return;
            }
            if (sender instanceof Player player) {
                menus.open(player, new de.raindancer.apm.gui.SearchResultsMenu(
                        service, menus, new MainMenu(service, menus), term, hits));
                return;
            }
            sender.sendMessage(Msg.info("<count> result<s>:",
                    Msg.arg("count", String.valueOf(hits.size())),
                    Msg.arg("s", hits.size() == 1 ? "" : "s")));
            hits.forEach(hit -> sender.sendMessage(Msg.raw(
                    "  <" + Msg.ACCENT + "><title></" + Msg.ACCENT + "> <" + Msg.MUTED
                            + ">(modrinth:<slug>) — <description>",
                    Msg.arg("title", hit.title()),
                    Msg.arg("slug", hit.slug()),
                    Msg.arg("description", hit.description()))));
        });
    }

    private void install(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Msg.error("Usage: /apm install <url|slug> — e.g. "
                    + "/apm install modrinth:luckperms"));
            return;
        }
        String query = args[0];
        sender.sendMessage(Msg.info("Resolving <query>…", Msg.arg("query", query)));

        service.prepareAsync(query, result -> {
            switch (result) {
                case ApmService.PrepareResult.Failed failed ->
                        sender.sendMessage(Msg.error("<detail>", Msg.arg("detail", failed.message())));
                case ApmService.PrepareResult.Ready ready -> commit(sender, ready.staged());
                case ApmService.PrepareResult.NeedsConfirmation needs -> {
                    if (sender instanceof Player player) {
                        menus.open(player, new de.raindancer.apm.gui.ConfirmMenu(menus,
                                new MainMenu(service, menus),
                                "Install " + needs.staged().meta().displayName() + " anyway?",
                                needs.warning(), "It may fail to load or break at runtime.", null,
                                confirming -> commit(confirming, needs.staged()),
                                cancelling -> service.discard(needs.staged())));
                        return;
                    }
                    sender.sendMessage(Msg.warn("<detail>", Msg.arg("detail", needs.warning())));
                    sender.sendMessage(Msg.warn("Set install.allow-incompatible to true in APM's "
                            + "config.yml to install it anyway. The download was discarded."));
                    service.discard(needs.staged());
                }
            }
        });
    }

    private void commit(CommandSender sender, InstallService.StagedInstall staged) {
        InstallService.InstallReport report = service.commit(staged);
        sender.sendMessage(report.success()
                ? Msg.success("<detail>", Msg.arg("detail", report.message()))
                : Msg.error("<detail>", Msg.arg("detail", report.message())));
        if (report.success() && report.needsRestart()) {
            sender.sendMessage(clickable("Restart the server now", "/apm restart --yes"));
        }
    }

    private void update(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Msg.error("Usage: /apm update <plugin>"));
            return;
        }
        if (args[0].equalsIgnoreCase("--all")) {
            updateAll(sender);
            return;
        }
        Optional<ManagedPlugin> lookup = requirePlugin(sender, args, "update");
        if (lookup.isEmpty()) {
            return;
        }
        ManagedPlugin managed = lookup.get();
        sender.sendMessage(Msg.info("Checking for a newer build of <name>…",
                Msg.arg("name", managed.name())));

        service.updateAsync(managed, result -> {
            switch (result) {
                case ApmService.PrepareResult.Failed failed ->
                        sender.sendMessage(Msg.error("<detail>", Msg.arg("detail", failed.message())));
                case ApmService.PrepareResult.Ready ready -> {
                    String candidate = ready.staged().meta().version();
                    if (managed.version() != null && managed.version().equals(candidate)) {
                        service.discard(ready.staged());
                        sender.sendMessage(Msg.success("<name> is already at <version>.",
                                Msg.arg("name", managed.name()),
                                Msg.arg("version", managed.version())));
                        return;
                    }
                    commit(sender, ready.staged());
                }
                case ApmService.PrepareResult.NeedsConfirmation needs -> {
                    service.discard(needs.staged());
                    sender.sendMessage(Msg.warn("The newest build of <name> is not compatible: "
                            + "<detail> Nothing was changed.",
                            Msg.arg("name", managed.name()), Msg.arg("detail", needs.warning())));
                }
            }
        });
    }

    private void updateAll(CommandSender sender) {
        var tracked = service.database().all();
        if (tracked.isEmpty()) {
            sender.sendMessage(Msg.warn("APM is not tracking any plugin yet, so there is nothing "
                    + "to update. Install something with /apm install first."));
            return;
        }
        sender.sendMessage(Msg.info("Checking <count> tracked plugin<s>…",
                Msg.arg("count", String.valueOf(tracked.size())),
                Msg.arg("s", tracked.size() == 1 ? "" : "s")));
        for (InstallDatabase.Record record : tracked.values()) {
            service.find(record.pluginName()).ifPresent(managed ->
                    update(sender, new String[]{managed.name()}));
        }
    }

    private void enable(CommandSender sender, String[] args) {
        requirePlugin(sender, args, "enable").ifPresent(managed ->
                report(sender, service.enable(managed)));
    }

    private void disable(CommandSender sender, String[] args) {
        Optional<ManagedPlugin> lookup = requirePlugin(sender, args, "disable");
        if (lookup.isEmpty()) {
            return;
        }
        boolean persistent = hasFlag(args, "--permanent") || hasFlag(args, "-p");
        report(sender, service.disable(lookup.get(), persistent));
    }

    private void reload(CommandSender sender, String[] args) {
        requirePlugin(sender, args, "reload").ifPresent(managed ->
                report(sender, service.reload(managed)));
    }

    /**
     * Opens the config browser, or prints a value when a path is given.
     *
     * <p>Writing from the console is deliberately not offered: a typo there has no confirmation
     * step and no undo beyond the automatic backup, and the GUI is right there.
     */
    private void config(CommandSender sender, String[] args) {
        Optional<ManagedPlugin> lookup = requirePlugin(sender, args, "config");
        if (lookup.isEmpty()) {
            return;
        }
        ManagedPlugin managed = lookup.get();
        List<java.nio.file.Path> files = service.configs().listConfigFiles(managed.name());

        if (files.isEmpty()) {
            sender.sendMessage(Msg.warn("<name> has no editable YAML file in plugins/<name>/ yet. "
                    + "Enable it once so it writes its defaults.",
                    Msg.arg("name", managed.name())));
            return;
        }

        if (sender instanceof Player player) {
            menus.open(player, new de.raindancer.apm.gui.ConfigFileMenu(
                    service, menus, new MainMenu(service, menus), managed.name()));
            return;
        }

        sender.sendMessage(Msg.info("<count> editable file<s> in plugins/<name>/:",
                Msg.arg("count", String.valueOf(files.size())),
                Msg.arg("s", files.size() == 1 ? "" : "s"),
                Msg.arg("name", managed.name())));
        files.forEach(file -> sender.sendMessage(Msg.raw("  <" + Msg.MUTED + ">• <file>",
                Msg.arg("file", file.toString()))));
        sender.sendMessage(Msg.info("Editing needs the GUI — run /apm config <name> as a player.",
                Msg.arg("name", managed.name())));
    }

    private void remove(CommandSender sender, String[] args, boolean purge) {
        String verb = purge ? "purge" : "remove";
        Optional<ManagedPlugin> lookup = requirePlugin(sender, args, verb);
        if (lookup.isEmpty()) {
            return;
        }
        ManagedPlugin managed = lookup.get();

        if (!hasFlag(args, "--yes") && !hasFlag(args, "-y")) {
            if (sender instanceof Player player) {
                menus.open(player, new de.raindancer.apm.gui.PluginDetailMenu(
                        service, menus, new MainMenu(service, menus), managed.name()));
                player.sendMessage(Msg.warn("Confirm the <verb> in the menu, or re-run the command "
                        + "with <" + Msg.ACCENT + ">--yes</" + Msg.ACCENT + ">.",
                        Msg.arg("verb", verb)));
                return;
            }
            sender.sendMessage(Msg.warn(purge
                    ? "This deletes the jar AND plugins/<name>/ with every config in it. "
                            + "Re-run with --yes to confirm."
                    : "This deletes the jar of <name>. Re-run with --yes to confirm.",
                    Msg.arg("name", managed.name())));
            return;
        }

        InstallService.InstallReport report = service.remove(managed, purge);
        sender.sendMessage(report.success()
                ? Msg.success("<detail>", Msg.arg("detail", report.message()))
                : Msg.error("<detail>", Msg.arg("detail", report.message())));
    }

    private void pending(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("apply")) {
            service.pending().drain();
            sender.sendMessage(Msg.info("Retried every queued operation. <count> still pending.",
                    Msg.arg("count", String.valueOf(service.pending().snapshot().size()))));
            return;
        }
        List<PendingActions.Action> actions = service.pending().snapshot();
        if (actions.isEmpty()) {
            sender.sendMessage(Msg.success("Nothing is queued — every file operation is done."));
            return;
        }
        sender.sendMessage(Msg.warn("<count> operation<s> will run at shutdown:",
                Msg.arg("count", String.valueOf(actions.size())),
                Msg.arg("s", actions.size() == 1 ? "" : "s")));
        actions.forEach(action -> sender.sendMessage(Msg.raw(
                "  <" + Msg.MUTED + ">• <what> — <reason>",
                Msg.arg("what", action.describe()), Msg.arg("reason", action.reason()))));
    }

    private void restart(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            sender.sendMessage(service.restarts().cancel()
                    ? Msg.success("The countdown was cancelled.")
                    : Msg.warn("No restart was scheduled."));
            return;
        }

        int seconds = -1;
        for (String arg : args) {
            if (arg.matches("\\d+")) {
                seconds = Integer.parseInt(arg);
                break;
            }
        }

        var scriptStatus = service.restartScripts().status();

        if (!hasFlag(args, "--yes") && !hasFlag(args, "-y")) {
            if (sender instanceof Player player) {
                // The restart screen states whether this would really restart and offers to fix it.
                menus.open(player, new de.raindancer.apm.gui.RestartMenu(
                        service, menus, new MainMenu(service, menus)));
                return;
            }
            sender.sendMessage(Msg.warn("This disconnects every player. Re-run with --yes to confirm."));
            if (!scriptStatus.willRestart()) {
                sender.sendMessage(Msg.error("<detail>", Msg.arg("detail", scriptStatus.detail())));
                sender.sendMessage(Msg.info("Run /apm restartscript first to have APM write one from "
                        + "this server's own launch command."));
            }
            return;
        }

        // Confirmed, but still worth stating plainly what is about to happen.
        if (!scriptStatus.willRestart()) {
            sender.sendMessage(Msg.error("<detail> Continuing anyway because --yes was given.",
                    Msg.arg("detail", scriptStatus.detail())));
        }
        sender.sendMessage(service.restarts().start(seconds, "requested by " + sender.getName())
                ? Msg.success("Restart scheduled.")
                : Msg.warn("A restart is already counting down. Use /apm restart cancel to stop it."));
    }

    /**
     * Reports whether a restart would really restart, and writes the missing script on request.
     *
     * <p>Exists because {@code Server#restart()} shuts the server down when the configured start
     * script is absent — a footgun an admin should not have to discover the hard way.
     */
    private void restartScript(CommandSender sender, String[] args) {
        var status = service.restartScripts().status();

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(status.willRestart()
                    ? Msg.success("<detail>", Msg.arg("detail", status.detail()))
                    : Msg.error("<detail>", Msg.arg("detail", status.detail())));
            if (!status.willRestart()) {
                sender.sendMessage(status.canGenerate()
                        ? Msg.info("Run /apm restartscript create to have APM write one that "
                                + "reproduces this server's launch command.")
                        : Msg.warn("This JVM does not expose its own command line, so APM cannot "
                                + "generate the script — write it by hand."));
            }
            return;
        }

        if (!args[0].equalsIgnoreCase("create")) {
            sender.sendMessage(Msg.error("Usage: /apm restartscript [status|create]"));
            return;
        }

        var result = service.restartScripts().generate(true);
        sender.sendMessage(result.success()
                ? Msg.success("<detail>", Msg.arg("detail", result.message()))
                : Msg.error("<detail>", Msg.arg("detail", result.message())));
    }

    private void reloadConfig(CommandSender sender) {
        service.reloadOwnConfig();
        sender.sendMessage(Msg.success("APM's configuration was reloaded."));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Msg.raw(""));
        sender.sendMessage(Msg.raw("<gradient:" + Msg.ACCENT + ":" + Msg.ACCENT_DIM
                + "><bold>Rain's Awesome Plugin Manager</bold></gradient> <" + Msg.MUTED + "><version>",
                Msg.arg("version", plugin.getPluginMeta().getVersion())));
        sender.sendMessage(Msg.raw("<" + Msg.MUTED
                + ">Every one of these has a button in <" + Msg.ACCENT + ">/apm gui</" + Msg.ACCENT + ">."));
        sender.sendMessage(Msg.raw(""));

        helpLine(sender, "/apm gui", "open the graphical interface");
        helpLine(sender, "/apm list", "every plugin and its state");
        helpLine(sender, "/apm info <plugin>", "details, dependencies, compatibility");
        helpLine(sender, "/apm search <term>", "search the Modrinth catalogue");
        helpLine(sender, "/apm install <url|slug>", "download, verify and install");
        helpLine(sender, "/apm update <plugin|--all>", "fetch a newer build of a tracked plugin");
        helpLine(sender, "/apm enable <plugin>", "switch a plugin on");
        helpLine(sender, "/apm disable <plugin> [--permanent]", "switch it off, optionally for good");
        helpLine(sender, "/apm reload <plugin>", "disable and enable it again");
        helpLine(sender, "/apm config <plugin>", "browse and edit its config.yml in a GUI");
        helpLine(sender, "/apm remove <plugin> --yes", "delete its jar, keep its config");
        helpLine(sender, "/apm purge <plugin> --yes", "delete its jar and all its data");
        helpLine(sender, "/apm pending [apply]", "review deferred file operations");
        helpLine(sender, "/apm restart [seconds|cancel] --yes", "restart with a countdown");
        helpLine(sender, "/apm restartscript [status|create]",
                "check, or create, the script Paper needs to actually restart");
        helpLine(sender, "/apm reloadconfig", "re-read APM's own config.yml");
        sender.sendMessage(Msg.raw(""));
    }

    private static void helpLine(CommandSender sender, String usage, String description) {
        sender.sendMessage(Msg.raw("  <" + Msg.ACCENT + "><usage></" + Msg.ACCENT + ">"
                        + "<newline>      <" + Msg.MUTED + "><description>",
                Msg.arg("usage", usage), Msg.arg("description", description)));
    }

    private static Component clickable(String label, String command) {
        return Msg.raw("  <" + Msg.ACCENT + ">[<label>]", Msg.arg("label", label))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Msg.raw("<" + Msg.MUTED + "><cmd>",
                        Msg.arg("cmd", command))));
    }

    private void report(CommandSender sender, PluginLifecycleService.Outcome outcome) {
        sender.sendMessage(outcome.success()
                ? Msg.success("<detail>", Msg.arg("detail", outcome.message()))
                : Msg.error("<detail>", Msg.arg("detail", outcome.message())));
        if (outcome.success() && outcome.needsRestart() && sender instanceof Player) {
            sender.sendMessage(clickable("Restart the server now", "/apm restart --yes"));
        }
    }

    /** Resolves {@code args[0]} to a plugin, reporting a helpful error when it cannot. */
    private Optional<ManagedPlugin> requirePlugin(CommandSender sender, String[] args, String verb) {
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                name = arg;
                break;
            }
        }
        if (name == null) {
            sender.sendMessage(Msg.error("Usage: /apm <verb> <plugin>", Msg.arg("verb", verb)));
            return Optional.empty();
        }
        Optional<ManagedPlugin> found = service.find(name);
        if (found.isEmpty()) {
            sender.sendMessage(Msg.error("No plugin called '<name>'. Run /apm list to see what "
                    + "APM knows about.", Msg.arg("name", name)));
        }
        return found;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }
}
