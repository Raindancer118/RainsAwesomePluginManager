package de.raindancer.apm.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * The startup banner.
 *
 * <p>Printed through {@link ComponentLogger} so the gradient actually renders in colour on a
 * modern console, instead of the escape-code soup a hand-rolled ANSI banner produces. Each line is
 * logged separately — a single multi-line log record would lose the per-line prefix and look broken
 * in log aggregators.
 *
 * <p>The banner doubles as a health report: it prints what the self-check actually found, so an
 * operator scrolling past it learns whether APM came up healthy without reading further.
 */
public final class Banner {

    /** Deliberately narrow so it survives an 80-column terminal. */
    private static final List<String> ART = List.of(
            "  ▄▀█ █▀█ █▀▄▀█ ",
            "  █▀█ █▀▀ █ ▀ █ ",
            "  ▀ ▀ ▀   ▀   ▀ ");

    private Banner() {
    }

    /**
     * @param logger        the plugin's component logger
     * @param version       APM's version
     * @param serverVersion the running Minecraft version
     * @param pluginCount   how many plugins the registry discovered
     * @param pendingCount  how many deferred file operations are queued
     * @param healthy       whether the startup self-check passed
     */
    public static void print(ComponentLogger logger, String version, String serverVersion,
                             int pluginCount, int pendingCount, boolean healthy) {
        for (Component line : build(version, serverVersion, pluginCount, pendingCount, healthy)) {
            logger.info(line);
        }
    }

    /** Built separately from printing so the layout can be asserted in a test. */
    public static List<Component> build(String version, String serverVersion,
                                        int pluginCount, int pendingCount, boolean healthy) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());

        // Art rows, each with its own slice of the gradient plus a right-hand detail column.
        List<String> details = List.of(
                "<" + Msg.TEXT + ">Rain's Awesome Plugin Manager <" + Msg.MUTED + ">v" + escape(version),
                "<" + Msg.MUTED + ">apt for your Paper server",
                "<" + Msg.MUTED + ">by Raindancer118");

        for (int row = 0; row < ART.size(); row++) {
            lines.add(Msg.raw("<gradient:" + Msg.ACCENT + ":" + Msg.ACCENT_DIM + ">"
                    + ART.get(row) + "</gradient>  " + details.get(row)));
        }

        lines.add(Component.empty());
        lines.add(Msg.raw("  <" + Msg.MUTED + ">server </" + Msg.MUTED + "><" + Msg.TEXT
                + ">Minecraft <version>", Msg.arg("version", serverVersion)));
        lines.add(Msg.raw("  <" + Msg.MUTED + ">plugins </" + Msg.MUTED + "><" + Msg.TEXT
                + "><count> discovered", Msg.arg("count", String.valueOf(pluginCount))));

        if (pendingCount > 0) {
            lines.add(Msg.raw("  <" + Msg.MUTED + ">queued  </" + Msg.MUTED + "><" + Msg.WARN
                            + "><count> file operation<s> waiting for shutdown",
                    Msg.arg("count", String.valueOf(pendingCount)),
                    Msg.arg("s", pendingCount == 1 ? "" : "s")));
        }

        lines.add(healthy
                ? Msg.raw("  <" + Msg.MUTED + ">status  </" + Msg.MUTED + "><" + Msg.OK
                        + ">ready <" + Msg.MUTED + ">— try <" + Msg.ACCENT + ">/apm gui</"
                        + Msg.ACCENT + ">")
                : Msg.raw("  <" + Msg.MUTED + ">status  </" + Msg.MUTED + "><" + Msg.BAD
                        + ">self-check failed <" + Msg.MUTED + ">— see the errors above"));
        lines.add(Component.empty());
        return lines;
    }

    /** Keeps a stray {@code <} in a version string from being read as a MiniMessage tag. */
    private static String escape(String raw) {
        return raw == null ? "?" : raw.replace("<", "\\<").toLowerCase(Locale.ROOT);
    }

    /** The shutdown counterpart — short, because nobody wants art in a shutdown log. */
    public static void printShutdown(ComponentLogger logger, int pendingCount) {
        logger.info(pendingCount == 0
                ? Msg.raw("<gradient:" + Msg.ACCENT + ":" + Msg.ACCENT_DIM
                        + "><bold>apm</bold></gradient> <" + Msg.MUTED + ">stopped cleanly.")
                : Msg.raw("<gradient:" + Msg.ACCENT + ":" + Msg.ACCENT_DIM
                                + "><bold>apm</bold></gradient> <" + Msg.MUTED
                                + ">stopped — <" + Msg.WARN + "><count> operation<s> could not be "
                                + "applied and will be retried on the next start.",
                        Msg.arg("count", String.valueOf(pendingCount)),
                        Msg.arg("s", pendingCount == 1 ? "" : "s")));
    }
}
