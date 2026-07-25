package de.raindancer.apm.gui;

import java.util.ArrayList;
import java.util.List;

import de.raindancer.apm.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Small builder for menu items.
 *
 * <p>Exists so that no menu has to remember to switch off Minecraft's default italics or to
 * escape a plugin name into a lore line — both are easy to forget and both look broken.
 */
public final class Icon {

    private final Material material;
    private Component title;
    private final List<Component> lore = new ArrayList<>();
    private int amount = 1;

    private Icon(Material material) {
        this.material = material;
    }

    public static Icon of(Material material) {
        return new Icon(material);
    }

    public Icon title(String miniMessage, TagResolver... resolvers) {
        this.title = Msg.itemTitle(miniMessage, resolvers).decoration(TextDecoration.ITALIC, false);
        return this;
    }

    public Icon lore(String miniMessage, TagResolver... resolvers) {
        this.lore.add(Msg.lore(miniMessage, resolvers));
        return this;
    }

    /** Adds an empty spacer line. */
    public Icon blank() {
        this.lore.add(Component.empty());
        return this;
    }

    public Icon lore(List<Component> lines) {
        this.lore.addAll(lines);
        return this;
    }

    /** Adds a "click to do X" hint in APM's accent colour. */
    public Icon action(String miniMessage, TagResolver... resolvers) {
        this.lore.add(Msg.itemTitle("<" + Msg.ACCENT + ">▸ " + miniMessage, resolvers)
                .decoration(TextDecoration.ITALIC, false));
        return this;
    }

    /** Adds a destructive-action hint. */
    public Icon danger(String miniMessage, TagResolver... resolvers) {
        this.lore.add(Msg.itemTitle("<" + Msg.BAD + ">▸ " + miniMessage, resolvers)
                .decoration(TextDecoration.ITALIC, false));
        return this;
    }

    public Icon amount(int amount) {
        this.amount = Math.max(1, Math.min(64, amount));
        return this;
    }

    public ItemStack build() {
        ItemStack item = new ItemStack(material, amount);
        item.editMeta(meta -> {
            if (title != null) {
                meta.displayName(title);
            }
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> line.decoration(TextDecoration.ITALIC, false))
                        .toList());
            }
        });
        return item;
    }
}
