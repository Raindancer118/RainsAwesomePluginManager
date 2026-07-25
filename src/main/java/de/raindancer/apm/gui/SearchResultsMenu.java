package de.raindancer.apm.gui;

import java.util.List;
import java.util.Locale;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.source.PluginSource;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * Catalogue hits for a search term, one clickable entry each.
 *
 * <p>This is the screen that makes APM usable without ever leaving the game: type a word, look at
 * what comes back, click to install. The results come from Modrinth, already filtered to plugins
 * for the configured loader.
 */
public final class SearchResultsMenu extends ApmMenu {

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private final String term;
    private final List<PluginSource.SearchResult> hits;

    public SearchResultsMenu(ApmService service, MenuManager menus, ApmMenu parent,
                             String term, List<PluginSource.SearchResult> hits) {
        super(4, MenuManager.title("apm", "results for \"" + trimTitle(term) + "\""));
        this.service = service;
        this.menus = menus;
        this.parent = parent;
        this.term = term;
        this.hits = List.copyOf(hits);
    }

    @Override
    public ApmMenu parent() {
        return parent;
    }

    @Override
    public void render() {
        clear();
        fillFooterBackground();

        int slot = 0;
        for (PluginSource.SearchResult hit : hits) {
            if (slot >= size() - 9) {
                break;
            }
            boolean installed = service.find(hit.title()).isPresent()
                    || service.database().get(hit.title()).isPresent();

            Icon icon = Icon.of(installed ? Material.LIME_DYE : Material.PAPER)
                    .title("<" + Msg.ACCENT + "><bold><title></bold>", Msg.arg("title", hit.title()))
                    .lore("modrinth:<slug>", Msg.arg("slug", hit.slug()));
            if (hit.downloads() >= 0) {
                icon.lore("<downloads> downloads",
                        Msg.arg("downloads", formatCount(hit.downloads())));
            }
            icon.lore(Msg.wrapLore(hit.description(), 44, Msg.MUTED));
            icon.blank();
            if (installed) {
                icon.lore("<" + Msg.OK + ">Already installed on this server");
            }
            icon.action("Install the newest build for MC <version>",
                    Msg.arg("version", service.serverVersion()));

            set(slot++, icon.build(), (player, click) ->
                    InstallFlow.start(service, menus, this, player, "modrinth:" + hit.slug()));
        }

        if (hits.isEmpty()) {
            set(13, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.MUTED + ">Nothing found")
                    .lore("No plugin matched <term>.", Msg.arg("term", term))
                    .build());
        }

        set(size() - 5, Icon.of(Material.SPYGLASS)
                .title("<" + Msg.ACCENT + ">Search again")
                .lore("Current term: <term>", Msg.arg("term", term))
                .blank()
                .action("Type a new search term")
                .build(), (player, click) -> menus.promptForText(player, "a search term",
                        newTerm -> service.searchAsync(newTerm, (newHits, error) -> {
                            if (error != null) {
                                player.sendMessage(Msg.error("Search failed: <detail>",
                                        Msg.arg("detail", error)));
                                menus.open(player, parent);
                                return;
                            }
                            menus.open(player,
                                    new SearchResultsMenu(service, menus, parent, newTerm, newHits));
                        }),
                        () -> menus.open(player, parent)));

        drawBackOrClose(menus, size() - 1);
    }

    private static String formatCount(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    /** Inventory titles are not scrollable, so an over-long search term gets cut. */
    private static String trimTitle(String term) {
        return term.length() <= 20 ? term : term.substring(0, 19) + "…";
    }
}
