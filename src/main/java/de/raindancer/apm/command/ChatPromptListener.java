package de.raindancer.apm.command;

import de.raindancer.apm.gui.MenuManager;
import de.raindancer.apm.util.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Catches the chat line a player types in response to a GUI prompt.
 *
 * <p>Uses {@link AsyncChatEvent} with Adventure components — the deprecated
 * {@code AsyncPlayerChatEvent} does not fire reliably on modern Paper. Registered at
 * {@code LOWEST} so the message is cancelled before any chat formatting plugin renders it:
 * a pasted download URL or a search term has no business appearing in public chat.
 */
public final class ChatPromptListener implements Listener {

    private final MenuManager menus;

    public ChatPromptListener(MenuManager menus) {
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!menus.hasPrompt(event.getPlayer())) {
            return;
        }
        String text = Msg.plain(event.message());
        if (menus.feedPrompt(event.getPlayer(), text)) {
            event.setCancelled(true);
        }
    }
}
