package de.raindancer.apm.gui;

import java.util.HashMap;
import java.util.Map;

import de.raindancer.apm.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Base class for every APM screen.
 *
 * <p>Using {@link InventoryHolder} as the identity of a menu — rather than comparing inventory
 * titles, which is the usual shortcut — means the click listener can recover the exact menu object
 * a click belongs to, and it makes spoofing impossible: a player cannot construct an inventory
 * whose holder is one of our menu instances.
 *
 * <p>Menus hold no {@link Player} reference beyond the open call and are discarded when closed, so
 * they cannot keep a disconnected player's data alive.
 */
public abstract class ApmMenu implements InventoryHolder {

    /** What a click on a slot does. */
    public interface SlotAction {
        void run(Player player, ClickType click);
    }

    private final Map<Integer, SlotAction> actions = new HashMap<>();
    private final int rows;
    private final Component title;
    private Inventory inventory;

    protected ApmMenu(int rows, Component title) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
    }

    /**
     * The backing inventory, created on first use.
     *
     * <p>Deliberately not built in the constructor: {@code createInventory} would have to be handed
     * a {@code this} whose subclass fields are not assigned yet, and a subclass that renders from
     * the holder during construction would see nulls.
     */
    @Override
    public final Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, rows * 9, title);
        }
        return inventory;
    }

    public final int rows() {
        return rows;
    }

    public final int size() {
        return rows * 9;
    }

    /** Rebuilds the whole screen. Called on open and after any action that changes state. */
    public abstract void render();

    /** @return the screen to return to when the player clicks "back", or null for none */
    public ApmMenu parent() {
        return null;
    }

    /** Draws the menu and shows it to the player. */
    public final void open(Player player) {
        render();
        player.openInventory(getInventory());
    }

    /** Re-draws the menu in place; the player keeps looking at the same window. */
    public final void refresh() {
        render();
    }

    /** Dispatches a click. Returns silently for decorative slots. */
    public final void click(Player player, int slot, ClickType type) {
        SlotAction action = actions.get(slot);
        if (action != null) {
            action.run(player, type);
        }
    }

    // --- drawing helpers -------------------------------------------------------------------

    protected final void clear() {
        getInventory().clear();
        actions.clear();
    }

    protected final void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < size()) {
            getInventory().setItem(slot, item);
        }
    }

    protected final void set(int slot, ItemStack item, SlotAction action) {
        if (slot < 0 || slot >= size()) {
            return;
        }
        getInventory().setItem(slot, item);
        actions.put(slot, action);
    }

    /** Fills the bottom row with a subtle divider so content and navigation stay visually apart. */
    protected final void fillFooterBackground() {
        ItemStack filler = Icon.of(Material.GRAY_STAINED_GLASS_PANE).title(" ").build();
        for (int slot = size() - 9; slot < size(); slot++) {
            getInventory().setItem(slot, filler);
        }
    }

    /** Draws the standard "back" button, or a close button when this is the root screen. */
    protected final void drawBackOrClose(MenuManager menus, int slot) {
        ApmMenu parent = parent();
        if (parent == null) {
            set(slot, Icon.of(Material.BARRIER)
                    .title("<" + Msg.BAD + ">Close")
                    .build(), (player, click) -> player.closeInventory());
            return;
        }
        set(slot, Icon.of(Material.ARROW)
                .title("<" + Msg.ACCENT + ">Back")
                .build(), (player, click) -> menus.open(player, parent));
    }
}
