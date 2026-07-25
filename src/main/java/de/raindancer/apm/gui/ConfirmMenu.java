package de.raindancer.apm.gui;

import java.util.List;
import java.util.function.Consumer;

import de.raindancer.apm.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * A yes/no gate in front of anything irreversible or disruptive.
 *
 * <p>Deliberately asymmetric: "cancel" sits where the eye lands first and confirm is on the far
 * side, so a stray click on an unexpected screen does not restart a production server.
 */
public final class ConfirmMenu extends ApmMenu {

    private final MenuManager menus;
    private final ApmMenu parent;
    private final String question;
    private final List<String> details;
    private final Consumer<Player> onConfirm;
    private final Consumer<Player> onCancel;

    public ConfirmMenu(MenuManager menus, ApmMenu parent, String question,
                       String detail1, String detail2, String detail3,
                       Consumer<Player> onConfirm) {
        this(menus, parent, question, detail1, detail2, detail3, onConfirm, player -> {
        });
    }

    public ConfirmMenu(MenuManager menus, ApmMenu parent, String question,
                       String detail1, String detail2, String detail3,
                       Consumer<Player> onConfirm, Consumer<Player> onCancel) {
        super(3, MenuManager.title("apm", "confirm"));
        this.menus = menus;
        this.parent = parent;
        this.question = question;
        this.details = java.util.stream.Stream.of(detail1, detail2, detail3)
                .filter(detail -> detail != null && !detail.isBlank())
                .toList();
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    public ApmMenu parent() {
        return parent;
    }

    @Override
    public void render() {
        clear();

        Icon prompt = Icon.of(Material.KNOWLEDGE_BOOK)
                .title("<" + Msg.WARN + "><bold><question></bold>", Msg.arg("question", question));
        for (String detail : details) {
            prompt.lore(Msg.wrapLore(detail, 44, Msg.MUTED));
        }
        set(13, prompt.build());

        set(11, Icon.of(Material.LIME_DYE)
                .title("<" + Msg.OK + "><bold>Yes, do it</bold>")
                .build(), (player, click) -> onConfirm.accept(player));

        set(15, Icon.of(Material.RED_STAINED_GLASS_PANE)
                .title("<" + Msg.BAD + "><bold>No, go back</bold>")
                .build(), (player, click) -> {
                    onCancel.accept(player);
                    if (parent == null) {
                        player.closeInventory();
                    } else {
                        menus.open(player, parent);
                    }
                });
    }
}
