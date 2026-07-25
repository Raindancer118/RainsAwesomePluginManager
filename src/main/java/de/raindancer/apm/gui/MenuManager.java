package de.raindancer.apm.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import de.raindancer.apm.ApmPlugin;
import de.raindancer.apm.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Owns every open APM screen and the chat prompts they use for free-text input.
 *
 * <p>Two jobs that both need central state:
 *
 * <ul>
 *   <li><b>Click routing.</b> Every {@link InventoryClickEvent} in an APM menu is cancelled
 *       unconditionally before anything else happens, so no item can ever be taken out of a
 *       screen even if a menu's own click handler throws.</li>
 *   <li><b>Chat prompts.</b> Minecraft has no text field in a chest GUI. Asking for a search term
 *       or a download URL therefore means closing the menu, catching the player's next chat
 *       message, and swallowing it so it never reaches public chat.</li>
 * </ul>
 */
public final class MenuManager implements Listener {

    /** A pending free-text request. */
    private record Prompt(Consumer<String> onInput, Runnable onCancel, String hint) {
    }

    private final ApmPlugin plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public MenuManager(ApmPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        prompts.clear();
        // Close any APM screen still open so nobody is left staring at a dead menu.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ApmMenu) {
                player.closeInventory();
            }
        }
    }

    /** Opens a screen for a player. Safe to call from a click handler. */
    public void open(Player player, ApmMenu menu) {
        prompts.remove(player.getUniqueId());
        menu.open(player);
    }

    /**
     * Closes the player's menu and waits for their next chat message.
     *
     * @param hint     shown to the player, e.g. {@code a search term}
     * @param onInput  receives the typed text, on the main thread
     * @param onCancel run when the player types {@code cancel} or leaves, on the main thread
     */
    public void promptForText(Player player, String hint,
                              Consumer<String> onInput, Runnable onCancel) {
        player.closeInventory();
        prompts.put(player.getUniqueId(), new Prompt(onInput, onCancel, hint));
        player.sendMessage(Msg.info("Type <" + Msg.ACCENT + "><hint></" + Msg.ACCENT
                + "> in chat, or <" + Msg.WARN + ">cancel</" + Msg.WARN + "> to abort.",
                Msg.arg("hint", hint)));
    }

    /** @return true if the player is currently being asked for text */
    public boolean hasPrompt(Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    /**
     * Feeds a chat line into a pending prompt.
     *
     * <p>Called from the chat listener on an async thread; the callback is bounced to the main
     * thread because it will touch the plugin manager and open inventories.
     *
     * @return true when the message was consumed and must not reach chat
     */
    public boolean feedPrompt(Player player, String message) {
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        String trimmed = message.trim();
        plugin.runOnMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("cancel")) {
                player.sendMessage(Msg.warn("Cancelled."));
                prompt.onCancel().run();
                return;
            }
            prompt.onInput().accept(trimmed);
        });
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof ApmMenu menu)) {
            return;
        }
        // Cancel first, ask questions later: even shift-clicking from the player's own inventory
        // must not be able to move an item into an APM screen.
        event.setCancelled(true);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(menu.getInventory())) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.hasAdminPermission(player)) {
            player.closeInventory();
            player.sendMessage(Msg.error("You are no longer allowed to use APM."));
            return;
        }

        try {
            menu.click(player, event.getSlot(), event.getClick());
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("An APM menu action failed", e);
            player.sendMessage(Msg.error("That action failed: <detail>",
                    Msg.arg("detail", String.valueOf(e.getMessage()))));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ApmMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }

    /** Convenience for menus that want to show a title with a subtitle-ish suffix. */
    public static Component title(String main, String suffix) {
        return Msg.raw("<gradient:" + Msg.ACCENT + ":" + Msg.ACCENT_DIM + "><bold>" + main
                + "</bold></gradient><" + Msg.MUTED + "> " + suffix);
    }
}
