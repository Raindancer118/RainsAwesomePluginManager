package de.raindancer.apm.gui;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.RestartScriptService;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * The restart screen, which exists because "restart" is not a single yes/no question.
 *
 * <p>Whether {@link org.bukkit.Server#restart()} brings the server back depends on a start script
 * outside APM's control. Rather than letting an operator click a button labelled "Restart" that
 * silently means "shut down and stay down", this screen states the verdict up front and offers to
 * fix the cause by writing a script that reproduces the current launch command.
 */
public final class RestartMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;

    public RestartMenu(ApmService service, MenuManager menus, ApmMenu parent) {
        super(4, MenuManager.title("apm", "restart"));
        this.service = service;
        this.menus = menus;
        this.parent = parent;
    }

    @Override
    public ApmMenu parent() {
        return parent;
    }

    @Override
    public void render() {
        clear();
        fillFooterBackground();

        RestartScriptService.Status status = service.restartScripts().status();
        boolean counting = service.restarts().isCountingDown();

        // Status panel: what would actually happen.
        Icon panel = Icon.of(switch (status.verdict()) {
                    case SUPERVISED, READY -> Material.LIME_STAINED_GLASS_PANE;
                    case MISSING -> Material.RED_STAINED_GLASS_PANE;
                    case NOT_EXECUTABLE -> Material.YELLOW_STAINED_GLASS_PANE;
                })
                .title(status.willRestart()
                        ? "<" + Msg.OK + "><bold>A restart will come back up</bold>"
                        : "<" + Msg.BAD + "><bold>A restart would NOT come back up</bold>")
                .lore(Msg.wrapLore(status.detail(), 44, status.willRestart() ? Msg.MUTED : Msg.WARN));
        if (!status.willRestart()) {
            panel.blank();
            panel.lore("Paper does not relaunch the JVM itself — it runs");
            panel.lore("the script named by settings.restart-script in");
            panel.lore("spigot.yml. Without it, restart means shutdown.");
        }
        set(4, panel.build());

        // Fix the cause. Offered whenever we are not already supervised — a plain script counts as
        // "ready" for Paper but is the setup that failed in practice, so the upgrade stays on offer.
        if (status.verdict() != RestartScriptService.Verdict.SUPERVISED) {
            boolean canGenerate = status.canGenerate();
            set(11, Icon.of(canGenerate ? Material.ANVIL : Material.BARRIER)
                    .title((canGenerate ? "<" + Msg.ACCENT + ">" : "<" + Msg.MUTED + ">")
                            + "<bold>Create the supervisor script</bold>")
                    .lore(canGenerate
                            ? "APM writes <path> reproducing exactly"
                            : "This JVM does not expose its own command",
                            Msg.arg("path", status.configuredPath()))
                    .lore(canGenerate
                            ? "how this server was launched — same java"
                            : "line, so APM cannot reconstruct the launch")
                    .lore(canGenerate
                            ? "binary, same flags, same jar."
                            : "command. Write the script by hand.")
                    .lore(canGenerate ? "It relaunches the server itself when it" : "")
                    .lore(canGenerate ? "exits, which Paper's own child process" : "")
                    .lore(canGenerate ? "does not reliably survive." : "")
                    .lore(canGenerate ? "An existing file is backed up first." : "")
                    .lore(canGenerate ? "<" + Msg.WARN + ">Start the server through it once." : "")
                    .blank()
                    .action(canGenerate ? "Write it now" : "Not possible here")
                    .build(), (player, click) -> {
                        if (!canGenerate) {
                            player.sendMessage(Msg.error("APM cannot read this JVM's command line, "
                                    + "so it cannot generate a working script. Create <path> by hand.",
                                    Msg.arg("path", status.configuredPath())));
                            return;
                        }
                        RestartScriptService.GenerateResult result =
                                service.restartScripts().generate(true);
                        player.sendMessage(result.success()
                                ? Msg.success("<detail>", Msg.arg("detail", result.message()))
                                : Msg.error("<detail>", Msg.arg("detail", result.message())));
                        refresh();
                    });
        }

        // Restart / cancel.
        if (counting) {
            set(15, Icon.of(Material.LIME_DYE)
                    .title("<" + Msg.OK + "><bold>Cancel the countdown</bold>")
                    .lore("A restart is currently counting down.")
                    .blank()
                    .action("Stop it")
                    .build(), (player, click) -> {
                        service.restarts().cancel();
                        refresh();
                    });
        } else {
            set(15, Icon.of(Material.REDSTONE_TORCH)
                    .title((status.willRestart() ? "<" + Msg.WARN + ">" : "<" + Msg.BAD + ">")
                            + "<bold>" + (status.willRestart() ? "Restart now" : "Shut down now")
                            + "</bold>")
                    .lore("Everyone online is disconnected after a")
                    .lore("<seconds> second countdown.",
                            Msg.arg("seconds",
                                    String.valueOf(service.restarts().configuredCountdown())))
                    .lore(status.willRestart()
                            ? ""
                            : "<" + Msg.BAD + ">The server will stay down until you")
                    .lore(status.willRestart() ? "" : "<" + Msg.BAD + ">start it again by hand.")
                    .blank()
                    .danger(status.willRestart() ? "Restart the server" : "Shut the server down")
                    .build(), (player, click) -> menus.open(player, new ConfirmMenu(menus, this,
                            status.willRestart() ? "Restart the server?" : "Shut the server down?",
                            "Everyone online will be disconnected.",
                            status.detail(),
                            status.willRestart() ? null
                                    : "Nobody can bring it back from in-game.",
                            confirming -> {
                                service.restarts().start(-1,
                                        "requested by " + confirming.getName());
                                confirming.closeInventory();
                            })));
        }

        set(size() - 5, Icon.of(Material.PAPER)
                .title("<" + Msg.MUTED + ">settings.restart-script = <path>",
                        Msg.arg("path", status.configuredPath()))
                .lore("Regenerate the script after changing your JVM")
                .lore("flags: /apm restartscript")
                .build());

        drawBackOrClose(menus, size() - 1);
    }
}
