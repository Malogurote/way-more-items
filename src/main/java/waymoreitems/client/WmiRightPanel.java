package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import waymoreitems.WmiMod;
import waymoreitems.WmiPayloads;
import waymoreitems.core.CatalogItem;
import waymoreitems.core.GuiLayout;
import waymoreitems.core.InventorySorter;
import waymoreitems.core.PinnedChecklist;
import waymoreitems.core.RecipeIndex;
import waymoreitems.core.SettingsFile;
import waymoreitems.core.ThemePalette;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import waymoreitems.client.WmiButtons.QuickButton;

import static waymoreitems.client.WmiDraw.*;

/**
 * Panneau de droite : onglets par mod, recherche, catalogue paginé, redimensionnement par la poignée gauche,
 * suppression des objets déposés et menu du clic droit sur un objet.
 */
final class WmiRightPanel {

    private static final String TAB_ALL = "all";
    private static final int DELETE_BORDER_RED = 0xFFEF4444;

    private final WmiOverlay o;
    private final WmiSettings settings;

    /** Rectangle du panneau au dernier dessin (null en mode JEI). */
    GuiLayout.Rect bounds;
    /** Ligne des onglets au dernier dessin (la molette y fait défiler les onglets). */
    GuiLayout.Rect tabRow;
    String selectedTabId = TAB_ALL;
    int modTabScroll = 0;
    boolean resizingRightPanel = false;
    boolean highlightChestMode = false;
    boolean searchFocused = false;
    String searchQuery = "";
    long lastSearchClickTime;
    int currentPage = 0;
    /** Objet du catalogue sous la souris dans l'image en cours. */
    String hoveredOverlayItemId;
    /** Menu du clic droit sur un objet du catalogue (null si fermé). */
    String ctxMenuItemId;
    Screen ctxMenuScreen;
    boolean ctxMenuGive;
    int ctxMenuX;
    int ctxMenuY;
    final List<QuickButton> ctxEntries = new ArrayList<>();

    /** Édition complète (curseur, sélection, copier-coller) de la recherche. */
    final TextFieldHelper searchEdit = new TextFieldHelper(() -> searchQuery, v -> {
        searchQuery = v;
        currentPage = 0;
    }, () -> TextFieldHelper.getClipboardContents(Minecraft.getInstance()),
            v -> TextFieldHelper.setClipboardContents(Minecraft.getInstance(), v), v -> v.length() <= 80);

    WmiRightPanel(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
    }

    void focusSearch() {
        searchFocused = true;
        o.notes.postItInputFocused = false;
        searchEdit.setCursorToEnd();
    }

    /** Menu du clic droit sur un objet du catalogue : ajouter aux Notes, voir les usages, donner 1 (don direct). */
    void renderItemMenu(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, int screenW, int screenH) {
        String id = ctxMenuItemId;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(WmiText.s("wmi.menu.add_to_notes"));
        actions.add(() -> o.notes.openNotesPicker(id));
        labels.add(WmiText.s("wmi.menu.uses"));
        actions.add(() -> o.openModal(ctxMenuScreen, WmiRecipeScreen.ViewMode.USES, id));
        if (ctxMenuGive) {
            labels.add(WmiText.s("wmi.menu.give_one"));
            actions.add(() -> o.giveItemToPlayer(id, 1));
        }
        int w = 0;
        for (String s : labels) {
            w = Math.max(w, WmiVector.width(font, s));
        }
        w += 12;
        int rowH = 13;
        int h = labels.size() * rowH + 4;
        int x = Math.max(2, Math.min(ctxMenuX + 2, screenW - w - 2));
        int y = Math.max(2, Math.min(ctxMenuY + 2, screenH - h - 2));
        drawFrostedPanel(g, x, y, w, h);
        ctxEntries.clear();
        for (int i = 0; i < labels.size(); i++) {
            int ry = y + 2 + i * rowH;
            boolean hover = mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= ry && mouseY < ry + rowH;
            if (hover) {
                bar(g, x + 2, ry, x + w - 2, ry + rowH, theme().accentSoft);
            }
            drawText(g, font, labels.get(i), x + 6, ry + 3, hover ? theme().accent : theme().textMain);
            ctxEntries.add(new QuickButton(x + 2, ry, w - 4, rowH, labels.get(i), "", actions.get(i), null));
        }
    }

    // ==================== GRAND PANNEAU DE DROITE (ONGLETS CRÉATIFS NATIFS + REDIMENSIONNEMENT + SUPPRESSION AU DÉPÔT) ====================

    /** Onglet du panneau de droite : un mod, réuni avec ses extensions éventuelles. */
    record ModTab(String id, String title, ItemStack icon) {
    }

    List<ModTab> modTabsCache = List.of();
    int modTabsSourceSize = -1;

    /** Espaces de noms connus du catalogue (pour regrouper un mod et ses extensions). */
    java.util.Set<String> knownNamespaces = java.util.Set.of();

    /**
     * Clé d'onglet d'un espace de noms : un mod dont l'identifiant prolonge celui d'un autre mod présent
     * (par exemple {@code monmod_extension} et {@code monmod}) partage l'onglet de ce dernier.
     */
    String tabKey(String namespace) {
        String best = namespace;
        for (String other : knownNamespaces) {
            if (!other.equals(namespace) && namespace.startsWith(other + "_") && other.length() < best.length()) {
                best = other;
            }
        }
        return best;
    }

    List<ModTab> modTabs() {
        List<CatalogItem> all = WmiClientIndex.get().allItems();
        if (all.size() == modTabsSourceSize) {
            return modTabsCache;
        }
        Map<String, String> titles = new HashMap<>();
        Map<String, String> firstItem = new HashMap<>();
        java.util.Set<String> namespaces = new java.util.HashSet<>();
        for (CatalogItem it : all) {
            namespaces.add(it.namespace());
        }
        knownNamespaces = namespaces;
        for (CatalogItem it : all) {
            if (!tabKey(it.namespace()).equals(it.namespace())) {
                continue;
            }
            String key = tabKey(it.namespace());
            if (!titles.containsKey(key)) {
                String title = "minecraft".equals(key) ? "Minecraft"
                        : (it.modName() == null || it.modName().isBlank() ? key : it.modName());
                titles.put(key, title);
                firstItem.put(key, it.id());
            }
        }
        List<String> keys = new ArrayList<>(titles.keySet());
        keys.sort(Comparator.comparingInt((String k) -> k.equals("minecraft") ? 0 : 1)
                .thenComparing(k -> titles.get(k).toLowerCase(Locale.ROOT)));
        List<ModTab> tabs = new ArrayList<>();
        for (String k : keys) {
            tabs.add(new ModTab(k, titles.get(k), WmiClientIndex.stackFor(firstItem.get(k))));
        }
        modTabsCache = tabs;
        modTabsSourceSize = all.size();
        return tabs;
    }

    private RecipeIndex countsIndex;
    private int countsItems = -1;
    private int countsRecipes = -1;
    private Map<String, int[]> tabCounts = Map.of();

    /** {« N objets », « M recettes »} de l'onglet actif (« Tous » : totaux du catalogue). */
    String[] tabCountsTexts() {
        RecipeIndex index = WmiClientIndex.get();
        modTabs();
        if (index != countsIndex || index.allItems().size() != countsItems || index.allRecipes().size() != countsRecipes) {
            tabCounts = index.countsByGroup(this::tabKey);
            countsIndex = index;
            countsItems = index.allItems().size();
            countsRecipes = index.allRecipes().size();
        }
        int items;
        int recipes;
        if (TAB_ALL.equals(selectedTabId)) {
            items = countsItems;
            recipes = countsRecipes;
        } else {
            int[] c = tabCounts.getOrDefault(selectedTabId, new int[2]);
            items = c[0];
            recipes = c[1];
        }
        return new String[] {
                WmiText.s("wmi.tabs.count_items", String.format(Locale.ROOT, "%,d", items).replace(',', ' ')),
                WmiText.s("wmi.tabs.count_recipes", String.format(Locale.ROOT, "%,d", recipes).replace(',', ' '))};
    }

    void renderRightBinder(
            AbstractContainerScreen<?> screen,
            GuiGraphicsExtractor g,
            Font font,
            int panelX,
            int panelY,
            int panelW,
            int panelH,
            int mouseX,
            int mouseY
    ) {
        Minecraft mc = Minecraft.getInstance();
        boolean admin = o.canGiveItems(mc);
        boolean carryingItem = !screen.getMenu().getCarried().isEmpty();
        boolean hoveringPanelWithItem = carryingItem
                && mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + panelH;

        drawFrostedPanel(g, panelX, panelY, panelW, panelH);

        // Objet tenu au-dessus du panneau : bordure rouge de suppression directe
        if (hoveringPanelWithItem) {
            drawBorder(g, panelX, panelY, panelW, panelH, DELETE_BORDER_RED);
            drawBorder(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2, DELETE_BORDER_RED);
        }

        // Poignée latérale gauche de redimensionnement
        boolean hoverGrip = (mouseX >= panelX - 3 && mouseX <= panelX + 4 && mouseY >= panelY && mouseY < panelY + panelH) || resizingRightPanel;
        int gripMidY = panelY + panelH / 2;
        bar(g, panelX + 1, gripMidY - 14, panelX + 3, gripMidY + 14, hoverGrip ? theme().accent : theme().textMuted);
        if (hoverGrip) {
            bar(g, panelX, panelY + 2, panelX + 2, panelY + panelH - 2, theme().accent);
        }

        // 1. Onglets par mod sur une seule ligne : [Tous], [Minecraft], puis chaque mod (avec ses extensions)
        // Chaque partie est placée par la mise en page (ancrée sur les bords du panneau par défaut)
        List<ModTab> tabs = modTabs();
        GuiLayout.Rect tabsR = o.frame.place("rightPanel.tabs", panelW - 10, 19);
        if (!settings.catalogTabs) {
            // Onglets masqués (onglet Catalogue de « Paramètres ») : catalogue entier, rangée réduite à rien pour
            // que le titre et la recherche remontent
            selectedTabId = TAB_ALL;
            tabRow = null;
            if (tabsR != null) {
                o.frame.set("rightPanel.tabs", new GuiLayout.Rect(tabsR.x(), tabsR.y() - 6, tabsR.w(), 0));
            }
        } else if (tabsR != null) {
            renderModTabRow(g, font, tabs, tabsR.x(), tabsR.y(), tabsR.w(), tabsR.h(), mouseX, mouseY);
        }

        // 2. Sous-en-tête : titre de l'onglet actif et bascule du clic (Donner ou Recette)
        String activeTitle = TAB_ALL.equals(selectedTabId) ? WmiText.s("wmi.tabs.all_items") : tabs.stream()
                .filter(t -> t.id().equals(selectedTabId)).map(ModTab::title).findFirst().orElse(WmiText.s("wmi.tabs.all_items"));
        int modeBtnW = 0;
        int lastModeY = 0;
        int lastModeH = 0;
        if (admin) {
            // Bascule discrète : texte réduit aux trois quarts, 8 unités de haut
            String modeLbl = WmiText.s(settings.directGiveMode ? "wmi.mode.give" : "wmi.mode.recipe");
            GuiLayout.Rect mode = o.frame.place("rightPanel.clickMode", smallTextWidth(font, modeLbl) + 8, 8);
            if (mode != null) {
                modeBtnW = mode.w();
                lastModeY = mode.y();
                lastModeH = mode.h();
                o.buttons.addDiscreetButton(g, font, mode, modeLbl,
                        WmiText.s("wmi.mode.tip"),
                        mouseX, mouseY, settings.directGiveMode, () -> {
                            settings.directGiveMode = !settings.directGiveMode;
                            settings.save();
                        });
            }
        }
        GuiLayout.Rect header = o.frame.place("rightPanel.header", panelW - 10, 13);
        if (header != null) {
            // Filet de séparation sous les onglets, deux unités au-dessus du titre
            bar(g, header.x(), header.y() - 2, header.right(), header.y() - 1, theme().innerBorder);
            // La place de la bascule n'est réservée que si elle est sur la même ligne que le titre
            int reserve = modeBtnW > 0 && lastModeY < header.bottom() && header.y() < lastModeY + lastModeH ? modeBtnW : 0;
            drawText(g, font, WmiVector.trim(font, activeTitle, header.w() - 4 - reserve), header.x() + 1, header.y() + 2,
                    theme().textMain);
            // Nombres d'objets et de recettes de l'onglet, à droite du titre, seulement si la place suffit
            String[] counts = tabCountsTexts();
            int itemsW = WmiVector.width(font, counts[0]);
            int recipesW = WmiVector.width(font, counts[1]);
            int gap = 4;
            int countsW = itemsW + gap + 1 + gap + recipesW;
            int free = header.w() - 4 - reserve - WmiVector.width(font, activeTitle) - 8;
            if (countsW <= free) {
                int cx = header.right() - reserve - countsW - 1;
                drawText(g, font, counts[0], cx, header.y() + 2, theme().textMuted);
                // Liseré vertical discret entre les deux nombres
                bar(g, cx + itemsW + gap, header.y() + 1, cx + itemsW + gap + 1, header.y() + 10, theme().innerBorder);
                drawText(g, font, counts[1], cx + itemsW + 2 * gap + 1, header.y() + 2, theme().textMuted);
            }
        }

        // 3. Barre de recherche (le filtre « Fabricable » passe par le mot-clé !fab)
        GuiLayout.Rect search = o.frame.place("rightPanel.search", panelW - 10, 13);
        int searchX = search != null ? search.x() : panelX + 5;
        int searchY = search != null ? search.y() : panelY + 42;
        int searchW = search != null ? search.w() : 0;
        int searchH = search != null ? search.h() : 13;
        int searchBorder = highlightChestMode ? 0xFFFFB703 : (searchFocused ? theme().accent : theme().innerBorder);
        if (search != null) {
            drawEditField(g, font, searchX, searchY, searchW, searchH, searchQuery, searchEdit, searchFocused,
                    WmiText.s("wmi.search.placeholder"), searchBorder);
        }
        o.buttons.drawn.add(new QuickButton(searchX, searchY, searchW, searchH, "",
                WmiText.s("wmi.search.tip", RecipeIndex.CRAFTABLE_KEYWORD), () -> {
            long now = System.currentTimeMillis();
            if (now - lastSearchClickTime < 300L) {
                highlightChestMode = !highlightChestMode;
            }
            lastSearchClickTime = now;
            focusSearch();
        }, () -> {
            searchQuery = "";
            currentPage = 0;
            focusSearch();
        }));
        GuiLayout.Rect separator = o.frame.place("rightPanel.separator", panelW - 12, 1);
        if (separator != null) {
            bar(g, separator.x(), separator.y(), separator.right(), separator.y() + Math.max(1, separator.h()),
                    theme().innerBorder);
        }

        // 4. Grille d'objets (taille des icônes, colonnes et lignes réglables) et pied de page
        GuiLayout.Rect grid = o.frame.place("rightPanel.grid", panelW - 10, panelH - 76);
        GuiLayout.Rect footer = o.frame.place("rightPanel.footer", panelW - 10, 12);
        renderCatalogGrid(screen, g, font, grid, footer, mouseX, mouseY);
    }

    /** Ligne unique d'onglets, défilante par flèches ou molette, infobulle au nom du mod. */
    void renderModTabRow(GuiGraphicsExtractor g, Font font, List<ModTab> tabs, int x, int y, int w, int h,
                                 int mouseX, int mouseY) {
        tabRow = new GuiLayout.Rect(x, y, w, h);
        int allW = 22 + WmiVector.width(font, WmiText.s("wmi.tabs.all"));
        int tabW = 20;
        drawCreativeTabButton(g, font, x, y, allW, h, new ItemStack(Items.COMPASS), WmiText.s("wmi.tabs.all"), WmiText.s("wmi.tabs.all_items"),
                TAB_ALL.equals(selectedTabId), false, mouseX, mouseY, () -> {
                    selectedTabId = TAB_ALL;
                    currentPage = 0;
                });
        int rowLeft = x + allW + 2;
        int rowRight = x + w;
        int needed = tabs.size() * (tabW + 2);
        boolean overflow = needed > rowRight - rowLeft;
        int arrowW = 9;
        int visibleLeft = overflow ? rowLeft + arrowW + 1 : rowLeft;
        int visibleRight = overflow ? rowRight - arrowW - 1 : rowRight;
        int fit = Math.max(1, (visibleRight - visibleLeft + 2) / (tabW + 2));
        int maxScroll = Math.max(0, tabs.size() - fit);
        modTabScroll = Math.max(0, Math.min(modTabScroll, maxScroll));
        if (overflow) {
            o.buttons.addFloatingButton(g, font, rowLeft, y, arrowW, h, "<", WmiText.s("wmi.tabs.prev_tip"), mouseX, mouseY, false,
                    () -> modTabScroll = Math.max(0, modTabScroll - 1), null);
            o.buttons.addFloatingButton(g, font, rowRight - arrowW, y, arrowW, h, ">", WmiText.s("wmi.tabs.next_tip"), mouseX, mouseY, false,
                    () -> modTabScroll = Math.min(Math.max(0, tabs.size() - 1), modTabScroll + 1), null);
        }
        int tx = visibleLeft;
        for (int i = modTabScroll; i < tabs.size() && i < modTabScroll + fit; i++) {
            ModTab tab = tabs.get(i);
            drawCreativeTabButton(g, font, tx, y, tabW, h, tab.icon(), "", tab.title(),
                    tab.id().equals(selectedTabId), false, mouseX, mouseY, () -> {
                        selectedTabId = tab.id();
                        currentPage = 0;
                    });
            tx += tabW + 2;
        }
    }

    void drawCreativeTabButton(
            GuiGraphicsExtractor g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            ItemStack icon,
            String text,
            String tooltip,
            boolean active,
            boolean isPurple,
            int mouseX,
            int mouseY,
            Runnable onClick
    ) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = active
                ? (isPurple ? theme().purpleSoft : theme().accentSoft)
                : (hover ? theme().slotHover : theme().slotBg);
        int brd = (active || hover)
                ? (isPurple ? theme().purple : theme().accent)
                : theme().border;
        drawBox(g, x, y, w, h, bg, brd);
        if (active) {
            bar(g, x + 1, y + h - 2, x + w - 1, y + h, isPurple ? theme().purple : theme().accent);
        }
        if (text != null && !text.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(x + 2, y + 2);
            g.pose().scale(0.88F, 0.88F);
            g.fakeItem(icon, 0, 0);
            g.pose().popMatrix();
            drawText(g, font, text, x + 17, y + (h - 8) / 2, active ? theme().accent : theme().textMain);
        } else {
            g.fakeItem(icon, x + (w - 16) / 2, y + (h - 16) / 2);
        }
        o.buttons.drawn.add(new QuickButton(x, y, w, h, text != null ? text : "", tooltip, onClick, null));
    }

    /** Clé de la dernière liste du catalogue : tant qu'elle ne change pas, la recherche et le tri ne sont pas refaits. */
    private record CatalogKey(RecipeIndex index, String query, String tab, WmiSettings.CatalogSort sort,
                              boolean hideNoRecipe, @Nullable String station, int inventoryHash) {
    }

    private @Nullable CatalogKey catalogKey;
    private List<CatalogItem> catalogCache = List.of();

    /**
     * Objets de la grille : recherche, onglet actif, objets sans recette masqués et ordre choisis dans l'onglet
     * Catalogue de « Paramètres ». Avec une machine ouverte, l'ordre de la recherche (objets de la machine en tête)
     * est conservé. Liste recalculée seulement quand l'un de ses paramètres change.
     */
    List<CatalogItem> catalogItems(Map<String, Integer> inv, @Nullable String activeStation) {
        RecipeIndex idx = WmiClientIndex.get();
        // Onglets masqués : catalogue entier (aperçu de « Paramètres » compris, dessiné avant le panneau)
        String tab = settings.catalogTabs ? selectedTabId : TAB_ALL;
        CatalogKey key = new CatalogKey(idx, searchQuery, tab, settings.catalogSort, settings.hideNoRecipe,
                activeStation, inv.hashCode());
        if (key.equals(catalogKey)) {
            return catalogCache;
        }
        List<CatalogItem> allMatched = idx.search(searchQuery, inv, false, activeStation);
        List<CatalogItem> items = new ArrayList<>(allMatched.size());
        for (CatalogItem it : allMatched) {
            if (!TAB_ALL.equals(tab) && !tabKey(it.namespace()).equals(tab)) {
                continue;
            }
            if (settings.hideNoRecipe && idx.recipesForOutput(it.id()).isEmpty()) {
                continue;
            }
            items.add(it);
        }
        boolean stationOrder = activeStation != null && !activeStation.isBlank();
        if (!stationOrder && settings.catalogSort == WmiSettings.CatalogSort.NAME) {
            items.sort(Comparator.comparing(CatalogItem::normalizedName).thenComparing(CatalogItem::id));
        } else if (!stationOrder && settings.catalogSort == WmiSettings.CatalogSort.ID) {
            items.sort(Comparator.comparing(CatalogItem::id));
        }
        catalogKey = key;
        catalogCache = List.copyOf(items);
        return catalogCache;
    }

    void renderCatalogGrid(
            AbstractContainerScreen<?> screen,
            GuiGraphicsExtractor g,
            Font font,
            GuiLayout.Rect grid,
            GuiLayout.Rect footer,
            int mouseX,
            int mouseY
    ) {
        Map<String, Integer> inv = o.snapshotPlayerInventory();
        String activeStation = inferWorkstationFromScreen(screen);
        List<CatalogItem> items = catalogItems(inv, activeStation);

        int icon = settings.gridIconSize;
        int slotStep = icon + 2;
        int gridW = grid != null ? grid.w() : 0;
        int gridTop = grid != null ? grid.y() : 0;
        int fitCols = Math.max(1, gridW / slotStep);
        int fitRows = Math.max(1, (grid != null ? grid.h() : 0) / slotStep);
        int cols = settings.gridColumns > 0 ? Math.min(settings.gridColumns, fitCols) : fitCols;
        int rows = settings.gridRows > 0 ? Math.min(settings.gridRows, fitRows) : fitRows;
        int perPage = cols * rows;
        int totalPages = Math.max(1, (items.size() + perPage - 1) / perPage);
        if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }

        int gridLeft = grid != null ? grid.x() + (gridW - cols * slotStep) / 2 : 0;
        if (grid != null) {
            o.frame.record("rightPanel.gridCells", gridLeft, gridTop, cols * slotStep, rows * slotStep);
        }
        int offset = currentPage * perPage;
        for (int i = 0; grid != null && i < Math.min(perPage, items.size() - offset); i++) {
            CatalogItem item = items.get(offset + i);
            int cx = gridLeft + (i % cols) * slotStep;
            int cy = gridTop + (i / cols) * slotStep;
            drawFloatingCatalogSlot(g, cx, cy, icon, item.id(), mouseX, mouseY);
        }

        // Pied de page : pagination (la suppression se fait en déposant l'objet n'importe où sur le panneau)
        if (footer == null) {
            return;
        }
        int footY = footer.y();
        int footX = footer.x();
        o.buttons.addFloatingButton(g, font, footX, footY, 16, 12, "<", WmiText.s("wmi.page.prev_tip"), mouseX, mouseY, false,
                () -> currentPage = Math.floorMod(currentPage - 1, totalPages), null);
        o.buttons.addFloatingButton(g, font, footX + 18, footY, 16, 12, ">", WmiText.s("wmi.page.next_tip"), mouseX, mouseY, false,
                () -> currentPage = Math.floorMod(currentPage + 1, totalPages), null);
        drawText(g, font, (currentPage + 1) + "/" + totalPages, footX + 37, footY + 2, theme().textMuted);
    }

    /** Case du catalogue de {@code size} unités ; l'objet est mis à l'échelle (indépendant de l'échelle d'interface). */
    void drawFloatingCatalogSlot(GuiGraphicsExtractor g, int x, int y, int size, String itemId, int mouseX, int mouseY) {
        ItemStack st = WmiClientIndex.stackFor(itemId);
        if (st.isEmpty()) {
            return;
        }
        boolean hover = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        drawBox(g, x, y, size, size, hover ? theme().slotHover : theme().slotBg, hover ? theme().accent : theme().innerBorder);
        if (hover) {
            hoveredOverlayItemId = itemId;
        }
        // Au-delà de 16 unités, rendu net à la taille de la case (l'atlas des objets est limité à 16 unités)
        WmiItemPip.drawItem(g, st, x + 1, y + 1, size - 2);
    }

    /**
     * Poste de travail de l'écran ouvert, reconnu de façon générique : l'objet du catalogue dont le nom affiché
     * correspond au titre de l'écran (vrai pour la plupart des machines, de tout mod), sinon le four de Minecraft.
     */
    String inferWorkstationFromScreen(AbstractContainerScreen<?> screen) {
        String normTitle = CatalogItem.normalize(screen.getTitle().getString());
        if (normTitle.isBlank()) {
            return null;
        }
        for (CatalogItem it : WmiClientIndex.get().allItems()) {
            if (it.normalizedName().equals(normTitle)) {
                return it.id();
            }
        }
        if (normTitle.contains("four") || normTitle.contains("furnace")) {
            return "minecraft:furnace";
        }
        return null;
    }

    /**
     * Place le panneau dans la mise en page : largeur réglable à la souris (proportion de l'écran), bornée par la
     * mise en page. Rend le rectangle retenu (largeur nulle à droite de l'écran si la mise en page l'omet).
     */
    GuiLayout.Rect place(GuiLayout.Resolver lay, int screenW, int mouseX) {
        boolean dragging = resizingRightPanel && bounds != null && bounds.w() > 0;
        if (dragging) {
            int draggedW = bounds.x() + bounds.w() - mouseX;
            lay.constant("rightPanelRatio", (double) draggedW / (double) Math.max(1, screenW));
        } else {
            lay.constant("rightPanelRatio", settings.rightPanelWidthRatio);
        }
        GuiLayout.Rect rp = lay.place("rightPanel", 0, 0);
        if (rp != null && dragging) {
            settings.rightPanelWidthRatio = (double) rp.w() / (double) Math.max(1, screenW);
        }
        bounds = rp != null ? rp : new GuiLayout.Rect(screenW, 0, 0, 0);
        return bounds;
    }
}
