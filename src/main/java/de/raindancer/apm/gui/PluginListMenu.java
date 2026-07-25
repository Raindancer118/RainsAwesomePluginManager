package de.raindancer.apm.gui;

import java.util.List;

import de.raindancer.apm.core.ApmService;
import de.raindancer.apm.core.ManagedPlugin;
import de.raindancer.apm.util.Msg;
import org.bukkit.Material;

/**
 * Paginated list of everything APM knows about, colour coded by state.
 */
public final class PluginListMenu extends ApmMenu {

    private static final int PER_PAGE = 45;

    private final ApmService service;
    private final MenuManager menus;
    private final ApmMenu parent;
    private int page;

    public PluginListMenu(ApmService service, MenuManager menus, ApmMenu parent, int page) {
        super(6, MenuManager.title("apm", "installed plugins"));
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

        List<ManagedPlugin> all = service.list();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);

        int from = page * PER_PAGE;
        List<ManagedPlugin> visible = all.subList(from, Math.min(all.size(), from + PER_PAGE));

        if (all.isEmpty()) {
            set(22, Icon.of(Material.STRUCTURE_VOID)
                    .title("<" + Msg.MUTED + ">No plugins found")
                    .lore("Not even APM itself — that should be impossible,")
                    .lore("so something is wrong with the plugins folder.")
                    .build());
        }

        for (int index = 0; index < visible.size(); index++) {
            ManagedPlugin managed = visible.get(index);
            set(index, iconFor(managed).build(),
                    (player, click) -> menus.open(player,
                            new PluginDetailMenu(service, menus, this, managed.name())));
        }

        if (page > 0) {
            set(size() - 9, Icon.of(Material.ARROW)
                    .title("<" + Msg.ACCENT + ">Previous page")
                    .build(), (player, click) -> {
                        page--;
                        refresh();
                    });
        }
        if (page < pages - 1) {
            set(size() - 3, Icon.of(Material.ARROW)
                    .title("<" + Msg.ACCENT + ">Next page")
                    .build(), (player, click) -> {
                        page++;
                        refresh();
                    });
        }

        set(size() - 5, Icon.of(Material.PAPER)
                .title("<" + Msg.MUTED + ">Page <page> of <pages>",
                        Msg.arg("page", String.valueOf(page + 1)),
                        Msg.arg("pages", String.valueOf(pages)))
                .lore("<count> plugin<s> total", Msg.arg("count", String.valueOf(all.size())),
                        Msg.arg("s", all.size() == 1 ? "" : "s"))
                .build());

        drawBackOrClose(menus, size() - 1);
    }

    private Icon iconFor(ManagedPlugin managed) {
        Material material = switch (managed.state()) {
            case ENABLED -> Material.LIME_DYE;
            case DISABLED -> Material.GRAY_DYE;
            case NOT_LOADED -> Material.ORANGE_DYE;
            case PARKED -> Material.RED_STAINED_GLASS_PANE;
        };
        String colour = switch (managed.state()) {
            case ENABLED -> Msg.OK;
            case DISABLED -> Msg.MUTED;
            case NOT_LOADED -> Msg.WARN;
            case PARKED -> Msg.BAD;
        };

        Icon icon = Icon.of(material)
                .title("<" + colour + "><bold><name></bold>", Msg.arg("name", managed.name()))
                .lore("Version <version>",
                        Msg.arg("version", managed.version() == null ? "unknown" : managed.version()))
                .lore("State: <" + colour + "><state>", Msg.arg("state", managed.state().label()));

        icon.lore(List.of(compatibilityLore(service.compatibilityOf(managed))));
        return icon.blank().action("Open");
    }

    /** One-line compatibility summary, shared with {@link PluginDetailMenu}. */
    static net.kyori.adventure.text.Component compatibilityLore(
            de.raindancer.apm.version.CompatibilityCheck check) {
        String api = check.declaredApi()
                .map(de.raindancer.apm.version.McVersion::majorMinor)
                .orElse("?");
        return switch (check.verdict()) {
            case COMPATIBLE -> Msg.lore("<" + Msg.OK + ">API <api> — fits this server",
                    Msg.arg("api", api));
            case TOO_NEW -> Msg.lore("<" + Msg.BAD + ">API <api> — built for a newer server",
                    Msg.arg("api", api));
            case UNKNOWN -> Msg.lore("<" + Msg.WARN + ">declares no api-version");
        };
    }
}
