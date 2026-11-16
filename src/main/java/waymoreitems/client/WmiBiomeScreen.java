package waymoreitems.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;
import waymoreitems.WmiPayloads;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Fenêtre des biomes (bouton de la sous-barre « Outils ») : une gélule par ligne portant le nom du biome, sans objet
 * du jeu, triée par nom ; onglets carrés contre le bord gauche : biomes de Minecraft, « Dimensions » (téléportation
 * au point d'apparition de chaque dimension du serveur), puis un par mod qui ajoute des biomes. Seuls les biomes que la dimension du joueur peut générer sont proposés : le
 * serveur les envoie à l'ouverture. Clic : téléportation au plus proche de ce biome (recherche côté serveur, comme
 * {@code /locate biome}).
 */
final class WmiBiomeScreen extends WmiListScreen {
    private static final int PILL_H = 14;
    /** Onglet « Tous ». */
    private static final String MINECRAFT = "minecraft";
    private static final int TAB_SIZE = 22;
    /** Onglet « Dimensions » (le « # » ne peut pas figurer dans l'identifiant d'un mod). */
    private static final String DIMENSIONS = "#dimensions";

    /** Biome reçu : identifiant, nom traduit, mod d'origine (identifiant et nom affiché). */
    private record Entry(String id, String name, String modId, String modName) {
    }

    /** Onglet carré latéral : identifiant (mod, {@link #MINECRAFT} ou {@link #DIMENSIONS}), nom et icône. */
    private record SideTab(String id, String title, ItemStack icon) {
    }

    /** Onglet retenu (identifiant du mod, {@link #MINECRAFT} ou {@link #DIMENSIONS}), gardé pendant la partie. */
    private static String selectedTab = MINECRAFT;
    /** Dernière liste reçue du serveur (null tant que la réponse n'est pas arrivée). */
    private static @Nullable List<String> received;
    /** Dimensions chargées par le serveur, reçues avec les biomes. */
    private static List<String> receivedDimensions = List.of();

    private List<Entry> entries = List.of();
    private List<Entry> dimensions = List.of();
    /** Onglets des mods qui ajoutent des biomes, par nom. */
    private List<SideTab> modTabs = List.of();
    private @Nullable List<String> shownFrom;

    WmiBiomeScreen(@Nullable Screen parent) {
        super(WmiText.c("wmi.biomes.title"), parent);
        received = null;
        WmiNetwork.send(new WmiPayloads.ActionPayload("biome_list"));
    }

    /** Réponse du serveur (fil principal du client). */
    static void onBiomeList(WmiPayloads.BiomeListPayload payload, IPayloadContext ctx) {
        receivedDimensions = List.copyOf(payload.dimensionIds());
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

        // Onglets carrés : biomes de Minecraft et dimensions, puis un par mod qui ajoute des biomes, par nom
        List<SideTab> mods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Entry> byModName = new ArrayList<>(list);
        byModName.sort((x, y) -> collator.compare(x.modName(), y.modName()));
        for (Entry e : byModName) {
            if (!"minecraft".equals(e.modId()) && seen.add(e.modId())) {
                mods.add(new SideTab(e.modId(), e.modName(), modIcon(e.modId())));
            }
        }
        modTabs = mods;
        dimensions = dimensionEntries(collator);
        boolean known = MINECRAFT.equals(selectedTab) || (DIMENSIONS.equals(selectedTab) && !dimensions.isEmpty())
                || mods.stream().anyMatch(t -> t.id().equals(selectedTab));
        if (!known) {
            selectedTab = MINECRAFT;
        }
    }

    /** Icône d'un mod : celle de son premier onglet créatif, sinon son premier objet, sinon un bloc d'herbe. */
    private static ItemStack modIcon(String modId) {
        for (WmiClientIndex.CreativeTabEntry tab : WmiClientIndex.creativeTabs()) {
            Identifier tid = Identifier.tryParse(tab.id());
            if (tid != null && modId.equals(tid.getNamespace()) && !tab.icon().isEmpty()) {
                return tab.icon();
            }
        }
        for (Identifier itemId : BuiltInRegistries.ITEM.keySet()) {
            if (modId.equals(itemId.getNamespace())) {
                return new ItemStack(BuiltInRegistries.ITEM.getValue(itemId));
            }
        }
        return new ItemStack(Items.GRASS_BLOCK);
    }

    /**
     * Dimensions du serveur, monde d'apparition, Nether et End d'abord : nom traduit par WMI pour celles de
     * Minecraft, chemin de l'identifiant mis en forme pour celles des mods.
     */
    private static List<Entry> dimensionEntries(Collator collator) {
        List<Entry> list = new ArrayList<>();
        for (String raw : receivedDimensions) {
            Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                continue;
            }
            String ns = id.getNamespace();
            String modName = "minecraft".equals(ns) ? "Minecraft" : WmiPlatform.modDisplayName(ns);
            String key = "wmi.dimension." + id.getNamespace() + "." + id.getPath().replace('/', '.');
            String name = WmiText.s(key);
            if (key.equals(name)) {
                String path = id.getPath().replace('_', ' ').replace('/', ' ');
                name = path.isEmpty() ? raw : Character.toUpperCase(path.charAt(0)) + path.substring(1);
            }
            list.add(new Entry(raw, name, ns, modName));
        }
        List<String> vanillaOrder = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        list.sort((a, b) -> {
            int ia = vanillaOrder.indexOf(a.id());
            int ib = vanillaOrder.indexOf(b.id());
            if (ia >= 0 || ib >= 0) {
                return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);
            }
            return collator.compare(a.name(), b.name());
        });
        return list;
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

    /**
     * Onglets carrés contre le bord gauche de la fenêtre, à l'extérieur : biomes de Minecraft (pierre) et dimensions
     * (portail du Nether animé), un liseré, puis un carré par mod qui ajoute des biomes ; nom en infobulle.
     */
    @Override
    void renderOutside(GuiGraphicsExtractor g, int x0, int y0, int boxW, int boxH, int mouseX, int mouseY) {
        int tx = x0 - 4 - TAB_SIZE;
        int ty = y0;
        sideTab(g, tx, ty, new SideTab(MINECRAFT, WmiText.s("wmi.biomes.tab_minecraft"), new ItemStack(Items.STONE)), mouseX, mouseY);
        ty += TAB_SIZE + 3;
        if (!dimensions.isEmpty()) {
            sideTab(g, tx, ty, new SideTab(DIMENSIONS, WmiText.s("wmi.biomes.tab_dimensions"), ItemStack.EMPTY), mouseX, mouseY);
            ty += TAB_SIZE + 3;
        }
        if (modTabs.isEmpty()) {
            return;
        }
        WmiDraw.bar(g, tx + 2, ty + 1, tx + TAB_SIZE - 2, ty + 2, WmiDraw.theme().innerBorder);
        ty += 6;
        for (SideTab tab : modTabs) {
            if (ty + TAB_SIZE > y0 + boxH) {
                break;
            }
            sideTab(g, tx, ty, tab, mouseX, mouseY);
            ty += TAB_SIZE + 3;
        }
    }

    private void sideTab(GuiGraphicsExtractor g, int x, int y, SideTab tab, int mouseX, int mouseY) {
        WmiTheme th = WmiDraw.theme();
        boolean active = tab.id().equals(selectedTab);
        boolean hover = mouseX >= x && mouseX < x + TAB_SIZE && mouseY >= y && mouseY < y + TAB_SIZE;
        WmiDraw.drawBox(g, x, y, TAB_SIZE, TAB_SIZE, active ? th.accentSoft : (hover ? th.slotHover : th.panelBg),
                (active || hover) ? th.accent : th.border);
        if (DIMENSIONS.equals(tab.id())) {
            drawPortal(g, x + 4, y + 4, TAB_SIZE - 8);
        } else {
            g.fakeItem(tab.icon(), x + (TAB_SIZE - 16) / 2, y + (TAB_SIZE - 16) / 2);
        }
        if (hover) {
            g.setTooltipForNextFrame(font, List.of(Component.literal(tab.title())), Optional.empty(),
                    WmiUiScale.real(mouseX), WmiUiScale.real(mouseY));
        }
        String id = tab.id();
        addClickZone(x, y, TAB_SIZE, TAB_SIZE, () -> selectTab(id));
    }

    /**
     * Portail du Nether dessiné : cadre d'obsidienne et voile violet en dégradé vertical dont les teintes glissent
     * lentement (cycle de quatre secondes).
     */
    private static void drawPortal(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, 0xFF1A1029);
        double t = (System.currentTimeMillis() % 4000L) / 4000.0 * Math.PI * 2.0;
        float k = (float) ((Math.sin(t) + 1.0) / 2.0);
        int top = lerpColor(0xFF5B1A9E, 0xFFB84DFF, k);
        int bottom = lerpColor(0xFFB84DFF, 0xFF3A0E73, k);
        g.fillGradient(x + 2, y + 2, x + size - 2, y + size - 2, top, bottom);
    }

    private static int lerpColor(int a, int b, float k) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int gr = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    private void selectTab(String tabId) {
        if (!selectedTab.equals(tabId)) {
            selectedTab = tabId;
            resetScroll();
        }
    }

    @Override
    int renderRows(GuiGraphicsExtractor g, int x, int top, int w, int mouseX, int mouseY) {
        WmiTheme th = WmiDraw.theme();
        String q = query();
        boolean dims = DIMENSIONS.equals(selectedTab);
        // Recherche saisie : tous les biomes, quel que soit l'onglet (sauf « Dimensions ») ; mod rappelé à droite
        boolean searching = !q.isEmpty() && !dims;
        boolean all = searching || dims;
        int y = top;
        for (Entry e : dims ? dimensions : entries) {
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
                addZone(x, y, w, PILL_H, dims ? () -> teleportToDimension(id) : () -> teleport(id), null);
            }
            y += PILL_H + 2;
        }
        return y - top;
    }

    private void teleportToDimension(String dimensionId) {
        if (WmiNetwork.send(new WmiPayloads.DimensionTeleportPayload(dimensionId))) {
            Minecraft.getInstance().gui.setScreen(null);
        }
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
