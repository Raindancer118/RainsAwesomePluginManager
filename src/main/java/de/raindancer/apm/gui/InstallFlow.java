package de.raindancer.apm.gui;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.InstallService;
import de.raindancer.apm.util.Msg;
import org.bukkit.entity.Player;

/**
 * The shared install conversation: resolve → maybe ask about compatibility → commit → offer restart.
 *
 * <p>Lives on its own because three different screens start it (main menu URL entry, search
 * results, plugin update) and the command layer runs the exact same sequence. Keeping it in one
 * place is what stops the GUI and the commands from drifting apart.
 */
public final class InstallFlow {

    private InstallFlow() {
    }

    /**
     * Kicks off an install for {@code query}.
     *
     * @param returnTo screen to come back to once the flow finishes
     */
    public static void start(ApmService service, MenuManager menus, ApmMenu returnTo,
                             Player player, String query) {
        player.sendMessage(Msg.info("Resolving <query>…", Msg.arg("query", query)));
        service.prepareAsync(query, result -> {
            if (!player.isOnline()) {
                if (result instanceof ApmService.PrepareResult.Ready ready) {
                    service.discard(ready.staged());
                } else if (result instanceof ApmService.PrepareResult.NeedsConfirmation pending) {
                    service.discard(pending.staged());
                }
                return;
            }
            switch (result) {
                case ApmService.PrepareResult.Failed failed -> {
                    player.sendMessage(Msg.error("<detail>", Msg.arg("detail", failed.message())));
                    menus.open(player, returnTo);
                }
                case ApmService.PrepareResult.Ready ready -> commit(service, menus, returnTo, player, ready.staged());
                case ApmService.PrepareResult.NeedsConfirmation needsConfirmation ->
                        menus.open(player, new ConfirmMenu(menus, returnTo,
                                "Install " + needsConfirmation.staged().meta().displayName() + " anyway?",
                                needsConfirmation.warning(),
                                "It may fail to load or break at runtime.",
                                "The download is verified and waiting in APM's cache.",
                                confirming -> commit(service, menus, returnTo, confirming,
                                        needsConfirmation.staged()),
                                cancelling -> {
                                    service.discard(needsConfirmation.staged());
                                    cancelling.sendMessage(Msg.warn("Install cancelled, download discarded."));
                                }));
            }
        });
    }

    private static void commit(ApmService service, MenuManager menus, ApmMenu returnTo,
                               Player player, InstallService.StagedInstall staged) {
        InstallService.InstallReport report = service.commit(staged);
        player.sendMessage(report.success()
                ? Msg.success("<detail>", Msg.arg("detail", report.message()))
                : Msg.error("<detail>", Msg.arg("detail", report.message())));

        if (report.success() && report.needsRestart()) {
            menus.open(player, new ConfirmMenu(menus, returnTo,
                    "Restart now to activate it?",
                    staged.meta().displayName() + " is in place but not running.",
                    "Everyone online will be disconnected.",
                    "You can also restart later from the main menu.",
                    confirming -> {
                        service.restarts().start(-1,
                                "activating " + staged.meta().name() + " (requested by "
                                        + confirming.getName() + ")");
                        confirming.closeInventory();
                    }));
            return;
        }
        menus.open(player, returnTo);
    }
}
