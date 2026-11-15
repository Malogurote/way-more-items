package waymoreitems.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;
import waymoreitems.WmiPayloads;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fenêtre des biomes (bouton de la sous-barre « Outils ») : une gélule par ligne portant le nom du biome, sans objet
 * du jeu, triée par nom ; onglets « Tous », « Minecraft » puis un par mod, comme le catalogue (pour éviter de
 * défiler). Seuls les biomes que la dimension du joueur peut générer sont proposés : le
 * serveur les envoie à l'ouverture. Clic : téléportation au plus proche de ce biome (recherche côté serveur, comme
 * {@code /locate biome}).
 */
final class WmiBiomeScreen extends WmiListScreen {
    private static final int PILL_H = 14;
    private static final int TAB_H = 14;
    /** Onglet « Tous ». */
    private static final String ALL = "";

    /** Biome reçu : identifiant, nom traduit, mod d'origine (identifiant et nom affiché). */
    private record Entry(String id, String name, String modId, String modName) {
    }

    /** Onglet retenu (identifiant du mod, ou {@link #ALL}), gardé d'une ouverture à l'autre pendant la partie. */
    private static String selectedTab = ALL;
    /** Dernière liste reçue du serveur (null tant que la réponse n'est pas arrivée). */
    private static @Nullable List<String> received;

    private List<Entry> entries = List.of();
    /** Onglets : identifiant du mod vers son nom affiché, « Tous » puis Minecraft puis les mods par nom. */
    private Map<String, String> tabs = Map.of();
    private @Nullable List<String> shownFrom;
    private int tabsHeight = TAB_H + 6;

    WmiBiomeScreen(@Nullable Screen parent) {
        super(WmiText.c("wmi.biomes.title"), parent);
        received = null;
        WmiNetwork.send(new WmiPayloads.ActionPayload("biome_list"));
    }

    /** Réponse du serveur (fil principal du client). */
    static void onBiomeList(WmiPayloads.BiomeListPayload payload, IPayloadContext ctx) {
        received = List.copyOf(payload.biomeIds());
    }

    private void refreshEntries() {
        List<String> ids = received;
        if (ids == shownFrom) {
            return;
        }
        shownFrom = ids;
        List<Entry> list = new ArrayList<>();
        if (ids != null) {
            for (String raw : ids) {
                Identifier id = Identifier.tryParse(raw);
                if (id == null) {
                    continue;
                }
                String ns = id.getNamespace();
                String modName = "minecraft".equals(ns) ? "Minecraft" : WmiPlatform.modDisplayName(ns);
                String key = Util.makeDescriptionId("biome", id);
                String name = WmiText.s(key);
                // Biome sans traduction (mod incomplet) : son identifiant plutôt que la clé brute
                list.add(new Entry(raw, key.equals(name) ? id.getPath() : name, ns, modName));
            }
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        list.sort((a, b) -> collator.compare(a.name(), b.name()));
        entries = list;

        List<Entry> byMod = new ArrayList<>(list);
        byMod.sort((a, b) -> {
            boolean va = "minecraft".equals(a.modId());
            boolean vb = "minecraft".equals(b.modId());
            return va != vb ? (va ? -1 : 1) : collator.compare(a.modName(), b.modName());
        });
        Map<String, String> t = new LinkedHashMap<>();
        t.put(ALL, WmiText.s("wmi.biomes.tab_all"));
        for (Entry e : byMod) {
            t.putIfAbsent(e.modId(), e.modName());
        }
        tabs = t;
        if (!tabs.containsKey(selectedTab)) {
            selectedTab = ALL;
        }
    }

    @Override
    String subtitle() {
        refreshEntries();
        return received == null ? WmiText.s("wmi.biomes.loading") : WmiText.s("wmi.biomes.count", entries.size());
    }

    @Override
    String searchPlaceholder() {
        return WmiText.s("wmi.biomes.search");
    }

    @Override
    int toolbarHeight() {
        return tabsHeight;
    }

    /** Onglets sur une ou plusieurs lignes selon la largeur de la fenêtre. */
    @Override
    void renderToolbar(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
        int tx = x;
        int ty = y;
        for (Map.Entry<String, String> tab : tabs.entrySet()) {
            String label = WmiVector.trim(font, tab.getValue(), w - 10);
            int tw = WmiVector.width(font, label) + 10;
            if (tx > x && tx + tw > x + w) {
                tx = x;
                ty += TAB_H + 3;
            }
            String modId = tab.getKey();
            button(g, tx, ty, tw, TAB_H, label, modId.equals(selectedTab), mouseX, mouseY, () -> selectTab(modId));
            tx += tw + 3;
        }
        tabsHeight = ty - y + TAB_H + 6;
    }

    private void selectTab(String modId) {
        if (!selectedTab.equals(modId)) {
            selectedTab = modId;
            resetScroll();
        }
    }

    @Override
    int renderRows(GuiGraphicsExtractor g, int x, int top, int w, int mouseX, int mouseY) {
        WmiTheme th = WmiDraw.theme();
        String q = query();
        boolean all = ALL.equals(selectedTab);
        int y = top;
        for (Entry e : entries) {
            if (!all && !e.modId().equals(selectedTab)) {
                continue;
            }
            if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q)
                    && !e.modName().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (isVisible(y, PILL_H)) {
                boolean hover = hovered(x, y, w, PILL_H, mouseX, mouseY);
                drawPill(g, x, y, w, PILL_H, hover ? th.slotHover : th.slotBg, hover ? th.accent : th.innerBorder);
                int col = hover ? th.accent : th.textMain;
                WmiDraw.drawText(g, font, WmiVector.trim(font, e.name(), w - (all ? 90 : 16)), x + 8, y + (PILL_H - 8) / 2, col);
                if (all) {
                    String mod = WmiVector.trim(font, e.modName(), 76);
                    WmiDraw.drawText(g, font, mod, x + w - 8 - WmiVector.width(font, mod), y + (PILL_H - 8) / 2, th.textMuted);
                }
                String id = e.id();
                addZone(x, y, w, PILL_H, () -> teleport(id), null);
            }
            y += PILL_H + 2;
        }
        return y - top;
    }

    private void teleport(String biomeId) {
        if (WmiNetwork.send(new WmiPayloads.BiomeTeleportPayload(biomeId))) {
            // Retour direct au jeu, inventaire fermé
            Minecraft.getInstance().gui.setScreen(null);
        }
    }

    /** Gélule : bords entièrement arrondis en rendu vectoriel, case ordinaire sinon (thème Minecraft, rendu simple). */
    private static void drawPill(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int border) {
        if (WmiVector.enabled() && !WmiDraw.theme().vanilla()) {
            WmiVector.fill(g, x, y, w, h, h / 2.0F, bg);
            WmiVector.strokePx(g, x, y, w, h, h / 2.0F, 1.0F, border);
        } else {
            WmiDraw.drawBox(g, x, y, w, h, bg, border);
        }
    }
}
