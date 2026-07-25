package de.raindancer.apm.gui;

import java.util.List;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.ManagedPlugin;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * Picks which plugin to configure, showing only the ones that actually have YAML to edit.
 *
 * <p>Filtering here rather than showing every plugin and greying most of them out keeps the screen
 * honest: if a plugin appears, clicking it leads somewhere useful.
 */
public final class ConfigPluginPickerMenu extends ApmMenu {

    private static final int PER_PAGE = 45;

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private int page;

    public ConfigPluginPickerMenu(ApmService service, MenuManager menus, ApmMenu parent, int page) {
        super(6, MenuManager.title("apm", "configurable plugins"));
        this.service = service;
        this.menus = menus;
        this.parent = parent;
        this.page = Math.max(0, page);
    }

    @Override
    public ApmMenu parent() {
        return parent;
    }

    @Override
    public void render() {
        clear();
        fillFooterBackground();

        record Candidate(ManagedPlugin plugin, int fileCount) {
        }

        List<Candidate> candidates = service.list().stream()
                .map(managed -> new Candidate(managed,
                        service.configs().listConfigFiles(managed.name()).size()))
                .filter(candidate -> candidate.fileCount() > 0)
                .toList();

        int pages = Math.max(1, (candidates.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        List<Candidate> visible = candidates.subList(page * PER_PAGE,
                Math.min(candidates.size(), page * PER_PAGE + PER_PAGE));

        if (candidates.isEmpty()) {
            set(22, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.MUTED + ">Nothing to configure")
                    .lore("No plugin on this server has written a")
                    .lore("YAML file into its data folder yet.")
                    .build());
        }

        int slot = 0;
        for (Candidate candidate : visible) {
            ManagedPlugin managed = candidate.plugin();
            set(slot++, Icon.of(Material.WRITABLE_BOOK)
                    .title("<" + Msg.ACCENT + "><bold><name></bold>", Msg.arg("name", managed.name()))
                    .lore("<count> YAML file<s>",
                            Msg.arg("count", String.valueOf(candidate.fileCount())),
                            Msg.arg("s", candidate.fileCount() == 1 ? "" : "s"))
                    .lore("State: <state>", Msg.arg("state", managed.state().label()))
                    .blank()
                    .action("Open its configuration")
                    .amount(candidate.fileCount())
                    .build(), (player, click) -> menus.open(player,
                            new ConfigFileMenu(service, menus, this, managed.name())));
        }

        if (page > 0) {
            set(size() - 9, Icon.of(Material.ARROW).title("<" + Msg.ACCENT + ">Previous page").build(),
                    (player, click) -> {
                        page--;
                        refresh();
                    });
        }
        if (page < pages - 1) {
            set(size() - 3, Icon.of(Material.ARROW).title("<" + Msg.ACCENT + ">Next page").build(),
                    (player, click) -> {
                        page++;
                        refresh();
                    });
        }

        set(size() - 5, Icon.of(Material.PAPER)
                .title("<" + Msg.MUTED + "><count> plugin<s> with editable YAML",
                        Msg.arg("count", String.valueOf(candidates.size())),
                        Msg.arg("s", candidates.size() == 1 ? "" : "s"))
                .lore("Page <page> of <pages>",
                        Msg.arg("page", String.valueOf(page + 1)),
                        Msg.arg("pages", String.valueOf(pages)))
                .build());

        drawBackOrClose(menus, size() - 1);
    }
}
