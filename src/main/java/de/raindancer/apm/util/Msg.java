package de.raindancer.apm.util;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * All of APM's user-facing text lives behind this class.
 *
 * <p>Everything is built as an Adventure {@link Component} through MiniMessage — no legacy
 * {@code §} codes anywhere. User supplied strings (plugin names, URLs, error messages from remote
 * servers) are always passed as {@link Placeholder#unparsed} so that a plugin called
 * {@code <red>oops} cannot inject formatting into APM's output.
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Brand colours, kept in one place so the whole plugin looks coherent. */
    public static final String ACCENT = "#7FD8FF";
    public static final String ACCENT_DIM = "#4E9BB8";
    public static final String OK = "#7BE07B";
    public static final String WARN = "#FFD166";
    public static final String BAD = "#FF7B7B";
    public static final String TEXT = "#D7DCE0";
    public static final String MUTED = "#8B949E";

    private static final String PREFIX =
            "<gradient:" + ACCENT + ":" + ACCENT_DIM + "><bold>apm</bold></gradient><" + MUTED + "> › </" + MUTED + ">";

    private Msg() {
    }

    /** Parses MiniMessage without a prefix. */
    public static Component raw(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    /** A neutral, prefixed line. */
    public static Component info(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + "<" + TEXT + ">" + miniMessage, resolvers);
    }

    /** A success line. */
    public static Component success(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + "<" + OK + ">" + miniMessage, resolvers);
    }

    /** A warning line. */
    public static Component warn(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + "<" + WARN + ">" + miniMessage, resolvers);
    }

    /** An error line. */
    public static Component error(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + "<" + BAD + ">" + miniMessage, resolvers);
    }

    /** Wraps untrusted text so it can be dropped into a message safely. */
    public static TagResolver arg(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "—" : value);
    }

    /** Wraps a pre-built component for insertion. */
    public static TagResolver comp(String name, Component value) {
        return Placeholder.component(name, value);
    }

    /**
     * Builds an item label: bold accent, italics switched off.
     *
     * <p>Minecraft italicises custom item names by default, which looks sloppy in a menu.
     */
    public static Component itemTitle(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize("<!italic>" + miniMessage, resolvers);
    }

    /** Builds a lore line with italics switched off. */
    public static Component lore(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize("<!italic><" + MUTED + ">" + miniMessage, resolvers)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Splits {@code text} into lore lines of at most {@code width} characters, on word boundaries. */
    public static List<Component> wrapLore(String text, int width, String colour) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Component> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(lore("<" + colour + "><value>", arg("value", line.toString())));
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
            if (lines.size() >= 8) {
                break;
            }
        }
        if (!line.isEmpty() && lines.size() < 9) {
            lines.add(lore("<" + colour + "><value>", arg("value", line.toString())));
        }
        return lines;
    }

    /** Flattens a component to plain text, e.g. to read a chat message a player typed. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
