package de.raindancer.apm.gui;

import java.util.List;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.PendingActions;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * The deferred file operation queue, with the option to cancel individual entries.
 *
 * <p>Being able to see this matters: "I removed the plugin but the jar is still there" is otherwise
 * indistinguishable from a bug, when in fact it is a file lock that resolves itself on shutdown.
 */
public final class PendingMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;

    public PendingMenu(ApmService service, MenuManager menus, ApmMenu parent) {
        super(4, MenuManager.title("apm", "pending operations"));
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

        List<PendingActions.Action> actions = service.pending().snapshot();
        if (actions.isEmpty()) {
            set(13, Icon.of(Material.LIME_STAINED_GLASS_PANE)
                    .title("<" + Msg.OK + ">Nothing pending")
                    .lore("Every file operation APM was asked to")
                    .lore("do has already been carried out.")
                    .build());
        }

        int slot = 0;
        for (PendingActions.Action action : actions) {
            if (slot >= size() - 9) {
                break;
            }
            Material material = action.kind() == PendingActions.Kind.DELETE
                    ? Material.TNT
                    : Material.ANVIL;
            set(slot++, Icon.of(material)
                    .title("<" + Msg.WARN + "><what>", Msg.arg("what", action.describe()))
                    .lore("Reason: <reason>", Msg.arg("reason", action.reason()))
                    .lore("Runs when the server shuts down.")
                    .blank()
                    .danger("Cancel this operation")
                    .build(), (player, click) -> {
                        if (service.pending().cancelFor(action.source())) {
                            player.sendMessage(Msg.success("Cancelled: <what>",
                                    Msg.arg("what", action.describe())));
                        } else {
                            player.sendMessage(Msg.warn("That operation was already gone."));
                        }
                        refresh();
                    });
        }

        set(size() - 5, Icon.of(Material.PAPER)
                .title("<" + Msg.MUTED + "><count> operation<s> queued",
                        Msg.arg("count", String.valueOf(actions.size())),
                        Msg.arg("s", actions.size() == 1 ? "" : "s"))
                .lore("Java cannot delete or rename a jar whose")
                .lore("classes are still loaded, so APM waits for")
                .lore("shutdown instead of failing loudly.")
                .build());

        drawBackOrClose(menus, size() - 1);
    }
}
