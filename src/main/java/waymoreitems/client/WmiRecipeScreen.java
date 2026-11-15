package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import waymoreitems.WmiPayloads;
import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiRegistry;
import waymoreitems.core.CatalogItem;
import waymoreitems.core.CommandPalette;
import waymoreitems.core.CraftTreeCalculator;
import waymoreitems.core.PinnedChecklist;
import waymoreitems.core.RecipeEntry;
import waymoreitems.core.RecipeIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fenêtre modale interactive de Way More Items (harmoniée au thème actif de {@link WmiOverlay},
 * « Verre Blanc Flouté » par défaut).
 */
public final class WmiRecipeScreen extends WmiScaledScreen {

    public enum ViewMode {
        RECIPES,
        USES,
        CRAFT_TREE,
        COMMAND_PALETTE,
        POST_IT_INPUT
    }

    private record HistoryState(ViewMode mode, String targetItemId, int tabIndex, int pageIndex, int treeMultiplier) {
    }

    private final Screen parentScreen;
    private final PinnedChecklist checklist;
    private final Map<String, Integer> playerInventory;

    private ViewMode mode;
    private String targetItemId;
    private final Deque<HistoryState> history = new ArrayDeque<>();

    private List<String> availableTypes = List.of();
    private Map<String, List<RecipeEntry>> recipesByType = Map.of();
    private int currentTab;
    private int currentPage;
    private static final int RECIPES_PER_PAGE = 2;

    private int treeMultiplier = 1;
    private CraftTreeCalculator.CraftPlan currentPlan;
    private int treeScrollOffset;

    /** Confirmation affichée deux secondes à côté du bouton « + » de la recette concernée. */
    private RecipeEntry pinnedFeedbackRecipe;
    private String pinnedFeedbackText = "";
    private long pinnedFeedbackUntil;

    private String paletteInput = "";
    private CommandPalette.PaletteResult paletteResult;

    private String hoveredItemId;
    private final List<ClickableZone> clickZones = new ArrayList<>();

    private record ClickableZone(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public WmiRecipeScreen(
            Screen parentScreen,
            ViewMode initialMode,
            String targetItemId,
            PinnedChecklist checklist,
            Map<String, Integer> playerInventory
    ) {
        super(Component.translatable("gui.wmi.title"));
        this.parentScreen = parentScreen;
        this.mode = initialMode;
        this.targetItemId = targetItemId == null ? "" : targetItemId;
        this.checklist = checklist;
        this.playerInventory = playerInventory == null ? Map.of() : playerInventory;
        refreshData();
    }

    private WmiTheme theme() {
        return WmiClient.overlay().theme();
    }

    private void navigateTo(ViewMode newMode, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        if (this.mode != ViewMode.COMMAND_PALETTE && !this.targetItemId.isEmpty()) {
            history.push(new HistoryState(this.mode, this.targetItemId, this.currentTab, this.currentPage, this.treeMultiplier));
        }
        this.mode = newMode;
        this.targetItemId = itemId;
        this.currentTab = 0;
        this.currentPage = 0;
        this.treeScrollOffset = 0;
        refreshData();
    }

    private boolean goBack() {
        if (history.isEmpty()) {
            onClose();
            return true;
        }
        HistoryState prev = history.pop();
        this.mode = prev.mode();
        this.targetItemId = prev.targetItemId();
        this.currentTab = prev.tabIndex();
        this.currentPage = prev.pageIndex();
        this.treeMultiplier = prev.treeMultiplier();
        refreshData();
        return true;
    }

    private void refreshData() {
        RecipeIndex idx = WmiClientIndex.get();
        if (mode == ViewMode.COMMAND_PALETTE) {
            paletteResult = CommandPalette.evaluate(idx, paletteInput);
            return;
        }
        if (mode == ViewMode.CRAFT_TREE) {
            currentPlan = CraftTreeCalculator.plan(idx, targetItemId, treeMultiplier, playerInventory, Map.of());
            return;
        }

        List<RecipeEntry> list = mode == ViewMode.RECIPES
                ? idx.recipesForOutput(targetItemId)
                : idx.recipesUsing(targetItemId);

        Map<String, List<RecipeEntry>> grouped = new LinkedHashMap<>();
        for (RecipeEntry r : list) {
            grouped.computeIfAbsent(r.typeId(), k -> new ArrayList<>()).add(r);
        }
        this.recipesByType = grouped;
        this.availableTypes = List.copyOf(grouped.keySet());
        if (currentTab >= availableTypes.size()) {
            currentTab = 0;
        }
        this.currentPage = 0;
    }

    @Override
    public void onClose() {
        if (mode == ViewMode.POST_IT_INPUT) {
            // Saisie terminée (validée ou abandonnée) : aucun champ des Notes ne reste ouvert
            WmiClient.overlay().endNotesInput();
        }
        Minecraft.getInstance().gui.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void extractUi(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (parentScreen != null) {
            graphics.fill(0, 0, width, height, 0x550F172A);
        }
        hoveredItemId = null;
        clickZones.clear();

        // Saisie de texte du système (SDL3) : sans startTextInput, aucun caractère n'arrive à charTyped.
        // La palette et l'éditeur de Notes sont des champs dessinés à la main ; Gui.setScreen coupe la saisie
        // à la fermeture de l'écran.
        if (mode == ViewMode.COMMAND_PALETTE || mode == ViewMode.POST_IT_INPUT) {
            Minecraft.getInstance().textInputManager().startTextInput(this);
        } else {
            Minecraft.getInstance().textInputManager().stopTextInput(this);
        }

        if (mode == ViewMode.POST_IT_INPUT) {
            renderPostItEditor(graphics, mouseX, mouseY);
            return;
        }

        int boxW;
        int boxH;
        if (mode == ViewMode.COMMAND_PALETTE) {
            boxW = 264;
            boxH = 192;
        } else if (mode == ViewMode.CRAFT_TREE) {
            boxW = Math.min(width - 70, 340);
            boxH = Math.min(height - 24, craftTreeHeight(boxW));
        } else {
            boxW = 252;
            boxH = recipesViewHeight();
        }
        int x0 = (width - boxW) / 2;
        int y0 = (height - boxH) / 2;

        WmiDraw.drawFrostedPanel(graphics, x0, y0, boxW, boxH);

        if (mode == ViewMode.COMMAND_PALETTE) {
            renderCommandPalette(graphics, x0, y0, boxW, boxH, mouseX, mouseY);
        } else if (mode == ViewMode.CRAFT_TREE) {
            renderCraftTree(graphics, x0, y0, boxW, boxH, mouseX, mouseY);
        } else {
            renderRecipesView(graphics, x0, y0, boxW, boxH, mouseX, mouseY);
        }

        if (hoveredItemId != null) {
            ItemStack st = WmiClientIndex.stackFor(hoveredItemId);
            if (!st.isEmpty()) {
                WmiNames.tooltip(graphics, font, st, mouseX, mouseY);
            }
        }
    }

    /** Hauteur ajustée au nombre de recettes de la page : plus de grand vide sous une recette unique. */
    private int recipesViewHeight() {
        if (availableTypes.isEmpty()) {
            return 110;
        }
        List<RecipeEntry> list = recipesByType.getOrDefault(availableTypes.get(Math.min(currentTab, availableTypes.size() - 1)), List.of());
        int onPage = Math.max(1, Math.min(RECIPES_PER_PAGE, list.size() - currentPage * RECIPES_PER_PAGE));
        return 48 + onPage * 66 + 22;
    }

    private void renderRecipesView(GuiGraphicsExtractor g, int x0, int y0, int boxW, int boxH, int mouseX, int mouseY) {
        WmiTheme th = theme();
        WmiDraw.drawBox(g, x0 + 3, y0 + 3, boxW - 6, 20, th.cardBg, th.innerBorder);

        ItemStack targetStack = WmiClientIndex.stackFor(targetItemId);
        if (!targetStack.isEmpty()) {
            g.fakeItem(targetStack, x0 + 6, y0 + 5);
        }
        String modeLabel = mode == ViewMode.RECIPES
                ? Component.translatable("gui.wmi.mode.recipes").getString()
                : Component.translatable("gui.wmi.mode.uses").getString();
        String itemName = !targetStack.isEmpty() ? WmiNames.of(targetStack) : targetItemId;
        WmiDraw.drawText(g, font, trimToWidth(modeLabel + " : " + itemName, boxW - 122), x0 + 25, y0 + 9, th.textMain);

        int btnRight = x0 + boxW - 6;
        // Bouton Fermer
        drawSmallButton(g, btnRight - 14, y0 + 6, 14, 14, "x", mouseX, mouseY, this::onClose);
        btnRight -= 16;

        // Bascule Recettes <-> Usages
        String switchModeLbl = WmiText.s(mode == ViewMode.RECIPES ? "gui.wmi.mode.uses" : "gui.wmi.mode.recipes");
        ViewMode otherMode = mode == ViewMode.RECIPES ? ViewMode.USES : ViewMode.RECIPES;
        drawSmallButton(g, btnRight - 44, y0 + 6, 44, 14, switchModeLbl, mouseX, mouseY,
                () -> navigateTo(otherMode, targetItemId));
        btnRight -= 46;

        // Onglets latéraux « Arbre » et « Donner », collés au bord extérieur droit de la fenêtre
        drawSideTabs(g, x0, y0, boxW, mouseX, mouseY);

        if (availableTypes.isEmpty()) {
            String none = Component.translatable("gui.wmi.no_recipes").getString();
            WmiDraw.drawText(g, font, none, x0 + (boxW - WmiVector.width(font, none)) / 2, y0 + boxH / 2 - 14, th.textMuted);

            if (mode == ViewMode.RECIPES && !WmiClientIndex.get().recipesUsing(targetItemId).isEmpty()) {
                drawSmallButton(g, x0 + (boxW - 110) / 2, y0 + boxH / 2 + 2, 110, 16, WmiText.s("wmi.recipe.see_uses"), mouseX, mouseY,
                        () -> navigateTo(ViewMode.USES, targetItemId));
            }
            return;
        }

        // Onglets des procédés / machines
        int tabX = x0 + 6;
        int tabY = y0 + 26;
        for (int i = 0; i < availableTypes.size(); i++) {
            String typeId = availableTypes.get(i);
            List<RecipeEntry> typeRecipes = recipesByType.get(typeId);
            String catTitle = typeRecipes.get(0).categoryName();
            int tw = Math.min(86, WmiVector.width(font, catTitle) + 24);
            if (tabX + tw > x0 + boxW - 6) {
                break;
            }
            boolean active = (i == currentTab);
            int bg = active ? th.accentSoft : th.slotBg;
            int border = active ? th.accent : th.innerBorder;
            WmiDraw.drawBox(g, tabX, tabY, tw, 18, bg, border);
            if (!typeRecipes.get(0).workstations().isEmpty()) {
                ItemStack wsStack = WmiClientIndex.stackFor(typeRecipes.get(0).workstations().get(0));
                if (!wsStack.isEmpty()) {
                    g.fakeItem(wsStack, tabX + 2, tabY + 1);
                }
            }
            WmiDraw.drawText(g, font, trimToWidth(catTitle, tw - 22), tabX + 20, tabY + 5, active ? th.accent : th.textMain);
            final int tabIdx = i;
            clickZones.add(new ClickableZone(tabX, tabY, tw, 18, () -> {
                currentTab = tabIdx;
                currentPage = 0;
            }));
            tabX += tw + 3;
        }

        String activeType = availableTypes.get(currentTab);
        List<RecipeEntry> activeList = recipesByType.getOrDefault(activeType, List.of());
        int totalPages = Math.max(1, (activeList.size() + RECIPES_PER_PAGE - 1) / RECIPES_PER_PAGE);
        if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }

        int startIdx = currentPage * RECIPES_PER_PAGE;
        int cardY = y0 + 48;
        for (int i = startIdx; i < Math.min(activeList.size(), startIdx + RECIPES_PER_PAGE); i++) {
            RecipeEntry rec = activeList.get(i);
            renderRecipeCard(g, rec, x0 + 8, cardY, boxW - 16, 62, mouseX, mouseY);
            cardY += 66;
        }

        int footY = y0 + boxH - 18;
        if (totalPages > 1) {
            drawSmallButton(g, x0 + 8, footY, 22, 13, "<", mouseX, mouseY,
                    () -> currentPage = Math.floorMod(currentPage - 1, totalPages));
            drawSmallButton(g, x0 + boxW - 30, footY, 22, 13, ">", mouseX, mouseY,
                    () -> currentPage = Math.floorMod(currentPage + 1, totalPages));
        }
        String pageStr = (currentPage + 1) + " / " + totalPages + " (" + activeList.size() + ")";
        WmiDraw.drawText(g, font, pageStr, x0 + (boxW - WmiVector.width(font, pageStr)) / 2, footY + 3, th.textMuted);
    }

    private void renderRecipeCard(
            GuiGraphicsExtractor g,
            RecipeEntry rec,
            int cx,
            int cy,
            int cw,
            int ch,
            int mouseX,
            int mouseY
    ) {
        WmiTheme th = theme();
        WmiDraw.drawFrostedCard(g, cx, cy, cw, ch);

        RecipeLayoutSpec layout = WmiRegistry.layout(rec.typeId());
        long tick = System.currentTimeMillis() / 1000L;

        if (layout != null && !layout.slots().isEmpty()) {
            for (RecipeLayoutSpec.SlotPos sp : layout.slots()) {
                int sx = cx + sp.x();
                int sy = cy + sp.y() - 2;
                RecipeEntry.IngredientSlot ing = sp.role() == RecipeLayoutSpec.SlotRole.INPUT ? rec.inputAtCell(sp.index()) : null;
                if (ing != null) {
                    // recette à forme : ingrédient posé dans cette case de la grille ; sinon, ingrédients à la suite
                    String chosen = ing.alternatives().get((int) (tick % ing.alternatives().size()));
                    drawFloatingSlot(g, sx, sy, chosen, ing.count(), false, mouseX, mouseY);
                } else if (sp.role() == RecipeLayoutSpec.SlotRole.CATALYST && sp.index() < rec.catalysts().size()) {
                    RecipeEntry.IngredientSlot cat = rec.catalysts().get(sp.index());
                    String chosen = cat.alternatives().get((int) (tick % cat.alternatives().size()));
                    drawFloatingSlot(g, sx, sy, chosen, cat.count(), false, mouseX, mouseY);
                } else if (sp.role() == RecipeLayoutSpec.SlotRole.OUTPUT && sp.index() < rec.outputs().size()) {
                    RecipeEntry.ItemAmount out = rec.outputs().get(sp.index());
                    drawFloatingSlot(g, sx, sy, out.itemId(), out.count(), sp.large(), mouseX, mouseY);
                } else {
                    drawFloatingSlot(g, sx, sy, null, 0, sp.large(), mouseX, mouseY);
                }
            }
            for (RecipeLayoutSpec.ProgressArrow arr : layout.arrows()) {
                drawAnimatedArrow(g, cx + arr.x(), cy + arr.y() - 2, arr.width());
            }
        } else {
            int ix = cx + 8;
            int iy = cy + 6;
            for (int i = 0; i < 9; i++) {
                RecipeEntry.IngredientSlot ing = rec.inputAtCell(i);
                if (ing == null) {
                    continue;
                }
                String chosen = ing.alternatives().get((int) (tick % ing.alternatives().size()));
                drawFloatingSlot(g, ix + (i % 3) * 19, iy + (i / 3) * 19, chosen, ing.count(), false, mouseX, mouseY);
            }
            drawAnimatedArrow(g, cx + 70, cy + 22, 24);
            for (int i = 0; i < rec.outputs().size(); i++) {
                RecipeEntry.ItemAmount out = rec.outputs().get(i);
                drawFloatingSlot(g, cx + 104 + i * 22, cy + 20, out.itemId(), out.count(), true, mouseX, mouseY);
            }
        }

        int metaX = cx + 140;
        int metaY = cy + 6;
        if (rec.durationTicks() > 0) {
            String sec = String.format(Locale.ROOT, "%.1f s (%d t)", rec.durationTicks() / 20.0, rec.durationTicks());
            WmiDraw.drawText(g, font, sec, metaX, metaY, th.textMain);
            metaY += 10;
        }
        if (rec.euPerTick() > 0) {
            WmiDraw.drawText(g, font, rec.euPerTick() + " EU/t", metaX, metaY, th.accent);
            metaY += 10;
        }
        if (rec.totalEnergy() > 0) {
            WmiDraw.drawText(g, font, rec.totalEnergy() + " EU", metaX, metaY, th.textMuted);
        }

        // Bouton « + » : ingrédients de la recette ajoutés aux Notes (vert si tout est déjà en inventaire)
        boolean canExec = WmiClientIndex.get().canExecuteSingleRecipe(rec, playerInventory);
        int btnX = cx + cw - 20;
        int btnY = cy + ch - 16;
        boolean hover = mouseX >= btnX && mouseX < btnX + 16 && mouseY >= btnY && mouseY < btnY + 13;
        int btnCol = hover ? th.accent : (canExec ? th.green : th.textMuted);
        WmiDraw.drawBox(g, btnX, btnY, 16, 13, hover ? th.slotHover : th.slotBg, btnCol);
        WmiDraw.drawText(g, font, "+", btnX + 5, btnY + 3, btnCol);
        clickZones.add(new ClickableZone(btnX, btnY, 16, 13, () -> pinIngredientsToNotes(rec)));
        if (hover) {
            g.setTooltipForNextFrame(WmiText.c("wmi.recipe.add_to_notes_tip"), WmiUiScale.real(mouseX), WmiUiScale.real(mouseY));
        }
        int leftOfPlus = btnX - 4;
        // Bouton contextuel, seulement si l'écran d'origine est une grille d'artisanat où la recette tient
        if (canPlaceInOpenGrid(rec)) {
            String label = WmiText.s("wmi.recipe.place_in_grid");
            int pw = WmiVector.width(font, label) + 10;
            int px = leftOfPlus - pw;
            boolean phover = mouseX >= px && mouseX < px + pw && mouseY >= btnY && mouseY < btnY + 13;
            WmiDraw.drawBox(g, px, btnY, pw, 13, phover ? th.slotHover : th.slotBg, phover ? th.accent : th.innerBorder);
            WmiDraw.drawText(g, font, label, px + 5, btnY + 3, phover ? th.accent : th.textMain);
            clickZones.add(new ClickableZone(px, btnY, pw, 13, () -> placeInOpenGrid(rec)));
            if (phover) {
                g.setTooltipForNextFrame(WmiText.c("wmi.recipe.place_in_grid_tip"), WmiUiScale.real(mouseX), WmiUiScale.real(mouseY));
            }
            leftOfPlus = px - 4;
        }
        if (System.currentTimeMillis() < pinnedFeedbackUntil && rec == pinnedFeedbackRecipe) {
            String msg = pinnedFeedbackText;
            int mw = WmiVector.width(font, msg);
            WmiDraw.drawText(g, font, msg, leftOfPlus - mw, btnY + 3, th.green);
        }
    }

    /**
     * Vrai si la fenêtre a été ouverte depuis une grille d'artisanat (établi, inventaire, ou bloc d'un mod
     * dont le menu dérive d'{@link AbstractCraftingMenu}) et que la recette y tient.
     */
    private boolean canPlaceInOpenGrid(RecipeEntry rec) {
        if (!WmiNetwork.serverHasWmi()) {
            return false; // le placement se fait sur le serveur
        }
        if (!(parentScreen instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof AbstractCraftingMenu menu)) {
            return false;
        }
        Recipe<?> recipe = WmiClientIndex.recipeById(rec.id());
        return recipe != null && WmiPayloads.fitsCraftingGrid(recipe, menu.getGridWidth(), menu.getGridHeight());
    }

    /** Le serveur place la recette (mécanisme du livre de recettes) ; retour à la grille pour voir le résultat. */
    private void placeInOpenGrid(RecipeEntry rec) {
        WmiNetwork.send(new WmiPayloads.PlaceRecipePayload(rec.id(), false));
        onClose();
    }

    /** Ajoute les ingrédients de {@code rec} aux Notes (suivi de quantités) ; la fenêtre reste ouverte. */
    private void pinIngredientsToNotes(RecipeEntry rec) {
        int added = checklist.pinRecipeInputs(rec, 1);
        pinnedFeedbackRecipe = rec;
        pinnedFeedbackText = added == 0 ? WmiText.s("wmi.recipe.added_none") : WmiText.n("wmi.recipe.added", added);
        pinnedFeedbackUntil = System.currentTimeMillis() + 2000L;
    }

    private void drawAnimatedArrow(GuiGraphicsExtractor g, int x, int y, int w) {
        WmiTheme th = theme();
        WmiDraw.bar(g, x, y + 6, x + w - 5, y + 9, th.innerBorder);
        int fillW = (int) ((System.currentTimeMillis() / 60L) % (w + 1));
        if (fillW > 0) {
            WmiDraw.bar(g, x, y + 6, x + Math.min(fillW, w - 5), y + 9, th.accent);
        }
        for (int i = 0; i < 5; i++) {
            g.fill(x + w - 5 + i, y + 3 + i, x + w - 4 + i, y + 12 - i, th.accent);
        }
    }

    private void drawFloatingSlot(
            GuiGraphicsExtractor g,
            int x,
            int y,
            String itemId,
            int count,
            boolean large,
            int mouseX,
            int mouseY
    ) {
        WmiTheme th = theme();
        int size = large ? 22 : 18;
        int offset = large ? 3 : 1;
        boolean hover = itemId != null && !itemId.isEmpty() && mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17;

        WmiDraw.bar(g, x - offset + 1, y - offset + 1, x - offset + size + 1, y - offset + size + 1, 0x1E0F172A);
        WmiDraw.drawBox(g, x - offset, y - offset, size, size, hover ? th.slotHover : th.slotBg, hover ? th.accent : th.innerBorder);

        if (itemId != null && !itemId.isEmpty()) {
            ItemStack st = WmiClientIndex.stackFor(itemId);
            if (!st.isEmpty()) {
                ItemStack withCount = count > 1 ? st.copyWithCount(count) : st;
                g.fakeItem(withCount, x, y);
                g.itemDecorations(font, withCount, x, y);
            }
            if (hover) {
                hoveredItemId = itemId;
            }
        }
    }

    private static final int TREE_SLOT = 20;
    private static final int TREE_STEP_H = 24;
    private static final int TREE_MAX_STEPS = 8;

    /** Étapes de fabrication dans l'ordre où on les réalise (des matières premières vers l'objet final). */
    private static void collectSteps(CraftTreeCalculator.CraftNode node, List<CraftTreeCalculator.CraftNode> out) {
        for (CraftTreeCalculator.CraftNode child : node.steps()) {
            collectSteps(child, out);
        }
        if (node.recipe() != null) {
            out.add(node);
        }
    }

    private int treeRawPerRow(int boxW) {
        return Math.max(1, (boxW - 16) / (TREE_SLOT + 2));
    }

    /** Hauteur de la fenêtre de l'arbre, calculée sur son contenu. */
    private int craftTreeHeight(int boxW) {
        if (currentPlan == null) {
            return 120;
        }
        int raw = Math.max(1, currentPlan.totalRawRequired().size());
        int rawRows = (raw + treeRawPerRow(boxW) - 1) / treeRawPerRow(boxW);
        List<CraftTreeCalculator.CraftNode> steps = new ArrayList<>();
        collectSteps(currentPlan.root(), steps);
        int visible = Math.max(1, Math.min(TREE_MAX_STEPS, steps.size()));
        return 30 + 14 + rawRows * (TREE_SLOT + 2) + 8 + 14 + visible * TREE_STEP_H + 44;
    }

    private void renderCraftTree(GuiGraphicsExtractor g, int x0, int y0, int boxW, int boxH, int mouseX, int mouseY) {
        WmiTheme th = theme();
        WmiOverlay o = WmiClient.overlay();

        // En-tête : objet visé et multiplicateurs
        WmiDraw.drawBox(g, x0 + 3, y0 + 3, boxW - 6, 22, th.cardBg, th.innerBorder);
        ItemStack targetStack = WmiClientIndex.stackFor(targetItemId);
        if (!targetStack.isEmpty()) {
            g.fakeItem(targetStack, x0 + 7, y0 + 6);
        }
        drawSideTabs(g, x0, y0, boxW, mouseX, mouseY);
        String targetName = !targetStack.isEmpty() ? WmiNames.of(targetStack) : targetItemId;
        int[] mults = {1, 4, 16, 64};
        int multW = 24;
        int mx = x0 + boxW - 7 - mults.length * (multW + 1);
        WmiDraw.drawText(g, font, trimToWidth(WmiText.s("wmi.tree.craft_title", targetName), mx - x0 - 32), x0 + 27, y0 + 10, th.textMain);
        for (int m : mults) {
            final int mul = m;
            boolean active = treeMultiplier == m;
            boolean hover = mouseX >= mx && mouseX < mx + multW && mouseY >= y0 + 7 && mouseY < y0 + 21;
            WmiDraw.drawBox(g, mx, y0 + 7, multW, 14, active ? th.accentSoft : (hover ? th.slotHover : th.slotBg),
                    (active || hover) ? th.accent : th.innerBorder);
            String lbl = "×" + m;
            WmiDraw.drawText(g, font, lbl, mx + (multW - WmiVector.width(font, lbl)) / 2, y0 + 10, active ? th.accent : th.textMain);
            clickZones.add(new ClickableZone(mx, y0 + 7, multW, 14, () -> {
                treeMultiplier = mul;
                treeScrollOffset = 0;
                refreshData();
            }));
            mx += multW + 1;
        }

        if (currentPlan == null) {
            return;
        }

        // 1. Matières premières à réunir (vert : en stock, rouge : à récolter)
        int y = y0 + 32;
        int missingKinds = currentPlan.missingRaw().size();
        WmiDraw.drawText(g, font, WmiText.s("wmi.tree.to_gather"), x0 + 9, y, th.textMuted);
        String rawState = missingKinds == 0 ? WmiText.s("wmi.tree.all_in_stock") : WmiText.s("wmi.tree.to_harvest", missingKinds);
        WmiDraw.drawText(g, font, rawState, x0 + boxW - 9 - WmiVector.width(font, rawState), y, missingKinds == 0 ? th.green : 0xFFDC2626);
        y += 13;
        int perRow = treeRawPerRow(boxW);
        int i = 0;
        for (var e : currentPlan.totalRawRequired().entrySet()) {
            int sx = x0 + 9 + (i % perRow) * (TREE_SLOT + 2);
            int sy = y + (i / perRow) * (TREE_SLOT + 2);
            boolean missing = currentPlan.missingRaw().getOrDefault(e.getKey(), 0) > 0;
            drawTreeSlot(g, sx, sy, e.getKey(), e.getValue(), missing ? 0xFFDC2626 : th.green, mouseX, mouseY);
            i++;
        }
        int rawRows = Math.max(1, (currentPlan.totalRawRequired().size() + perRow - 1) / perRow);
        y += rawRows * (TREE_SLOT + 2) + 6;

        // 2. Étapes de fabrication, dans l'ordre de réalisation
        List<CraftTreeCalculator.CraftNode> steps = new ArrayList<>();
        collectSteps(currentPlan.root(), steps);
        WmiDraw.bar(g, x0 + 8, y - 3, x0 + boxW - 8, y - 2, th.innerBorder);
        WmiDraw.drawText(g, font, WmiText.s("wmi.tree.steps_title", steps.size()), x0 + 9, y + 1, th.textMuted);
        if (steps.size() > TREE_MAX_STEPS) {
            String hint = WmiText.s("wmi.tree.scroll_hint", treeScrollOffset + 1, Math.min(steps.size(), treeScrollOffset + TREE_MAX_STEPS));
            WmiDraw.drawText(g, font, hint, x0 + boxW - 9 - WmiVector.width(font, hint), y + 1, th.textMuted);
        }
        y += 14;
        treeScrollOffset = Math.max(0, Math.min(treeScrollOffset, Math.max(0, steps.size() - TREE_MAX_STEPS)));
        for (int s = treeScrollOffset; s < steps.size() && s < treeScrollOffset + TREE_MAX_STEPS; s++) {
            renderTreeStep(g, steps.get(s), s + 1, x0 + 8, y, boxW - 16, mouseX, mouseY);
            y += TREE_STEP_H;
        }

        // 3. Bilan et actions
        int footY = y0 + boxH - 36;
        WmiDraw.bar(g, x0 + 8, footY - 2, x0 + boxW - 8, footY - 1, th.innerBorder);
        StringBuilder sum = new StringBuilder();
        sum.append(WmiText.n("wmi.tree.step_count", steps.size()));
        if (currentPlan.totalEnergyEu() > 0) {
            sum.append(" • ").append(currentPlan.totalEnergyEu()).append(" EU");
        }
        if (currentPlan.totalTicks() > 0) {
            sum.append(" • ").append(WmiText.s("wmi.tree.machine_seconds", String.format(Locale.ROOT, "%.0f", currentPlan.totalTicks() / 20.0)));
        }
        WmiDraw.drawText(g, font, sum.toString(), x0 + 9, footY + 3, th.textMain);
        drawSmallButton(g, x0 + 8, footY + 16, 64, 15, Component.translatable("gui.wmi.btn.back").getString(), mouseX, mouseY, this::goBack);
        drawSmallButton(g, x0 + boxW - 128, footY + 16, 120, 15, Component.translatable("gui.wmi.btn.pin_hud").getString(), mouseX, mouseY, () -> {
            checklist.pinPlan(targetItemId, treeMultiplier, currentPlan);
            onClose();
        });
    }

    /** Une étape : numéro, ingrédients, flèche, machine, produit et libellé (procédé ×lots). */
    private void renderTreeStep(GuiGraphicsExtractor g, CraftTreeCalculator.CraftNode node, int number,
                                int x, int y, int w, int mouseX, int mouseY) {
        WmiTheme th = theme();
        WmiOverlay o = WmiClient.overlay();
        boolean hoverRow = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TREE_STEP_H - 2;
        WmiDraw.bar(g, x, y, x + w, y + TREE_STEP_H - 2, hoverRow ? th.slotHover : th.cardBg);

        // Numéro d'ordre dans une pastille
        String num = Integer.toString(number);
        WmiDraw.bar(g, x + 3, y + 5, x + 15, y + 17, th.accentSoft);
        WmiDraw.drawText(g, font, num, x + 9 - WmiVector.width(font, num) / 2, y + 7, th.accent);

        // Ingrédients (au plus quatre, puis « +n »)
        int cx = x + 19;
        int shown = 0;
        for (CraftTreeCalculator.CraftNode in : node.steps()) {
            if (shown == 4) {
                WmiDraw.drawText(g, font, "+" + (node.steps().size() - 4), cx + 1, y + 7, th.textMuted);
                cx += 14;
                break;
            }
            drawTreeSlot(g, cx, y + 1, in.itemId(), in.needed(), th.innerBorder, mouseX, mouseY);
            cx += TREE_SLOT + 1;
            shown++;
        }

        // Flèche, machine, produit
        cx += 2;
        WmiDraw.bar(g, cx, y + 10, cx + 9, y + 12, th.accent);
        for (int k = 0; k < 4; k++) {
            g.fill(cx + 9 + k, y + 7 + k, cx + 10 + k, y + 15 - k, th.accent);
        }
        cx += 16;
        RecipeEntry r = node.recipe();
        if (!r.workstations().isEmpty()) {
            ItemStack ws = WmiClientIndex.stackFor(r.workstations().get(0));
            if (!ws.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(cx, y + 4);
                g.pose().scale(0.8F, 0.8F);
                g.fakeItem(ws, 0, 0);
                g.pose().popMatrix();
            }
        }
        cx += 16;
        drawTreeSlot(g, cx, y + 1, node.itemId(), node.crafted(), th.accent, mouseX, mouseY);
        cx += TREE_SLOT + 5;

        String label = r.categoryName() + (node.batches() > 1 ? " ×" + node.batches() : "");
        WmiDraw.drawText(g, font, trimToWidth(label, x + w - cx - 3), cx, y + 7, th.textMuted);
    }

    /** Case de l'arbre : objet, quantité et cadre de couleur (statut). Survol : infobulle et clic vers sa recette. */
    private void drawTreeSlot(GuiGraphicsExtractor g, int x, int y, String itemId, int count, int border, int mouseX, int mouseY) {
        WmiTheme th = theme();
        boolean hover = mouseX >= x && mouseX < x + TREE_SLOT && mouseY >= y && mouseY < y + TREE_SLOT;
        WmiDraw.drawBox(g, x, y, TREE_SLOT, TREE_SLOT, hover ? th.slotHover : th.slotBg, hover ? th.accent : border);
        ItemStack st = WmiClientIndex.stackFor(itemId);
        if (!st.isEmpty()) {
            ItemStack withCount = count > 1 ? st.copyWithCount(Math.min(count, 999)) : st;
            g.fakeItem(withCount, x + 2, y + 2);
            g.itemDecorations(font, withCount, x + 2, y + 2);
        }
        if (hover) {
            hoveredItemId = itemId;
        }
    }

    private void renderCommandPalette(GuiGraphicsExtractor g, int x0, int y0, int boxW, int boxH, int mouseX, int mouseY) {
        WmiTheme th = theme();
        WmiDraw.bar(g, x0 + 3, y0 + 3, x0 + boxW - 3, y0 + 23, th.cardBg);
        WmiDraw.drawText(g, font, Component.translatable("gui.wmi.palette.title").getString(), x0 + 8, y0 + 8, th.accent);

        int barX = x0 + 8;
        int barY = y0 + 28;
        int barW = boxW - 16;
        WmiDraw.drawBox(g, barX, barY, barW, 18, th.slotBg, th.accent);
        String prompt = "> " + paletteInput + "_";
        WmiDraw.drawText(g, font, prompt, barX + 5, barY + 5, th.textMain);

        if (paletteResult == null) {
            return;
        }

        int listY = barY + 24;
        if (paletteResult.kind() == CommandPalette.IntentKind.EXECUTE_ACTION && paletteResult.action() != null) {
            String actDesc = Component.translatable("gui.wmi.palette.run_action", paletteResult.action().id()).getString();
            drawSmallButton(g, barX, listY, barW, 18, actDesc, mouseX, mouseY, () -> {
                WmiNetwork.send(new WmiPayloads.ActionPayload(paletteResult.action().id()));
                onClose();
            });
            listY += 22;
        } else if (paletteResult.kind() == CommandPalette.IntentKind.GIVE_ITEM && !paletteResult.matchingItems().isEmpty()) {
            CatalogItem first = paletteResult.matchingItems().get(0);
            String giveDesc = Component.translatable("gui.wmi.palette.give_item", paletteResult.giveCount(), first.displayName()).getString();
            drawSmallButton(g, barX, listY, barW, 18, giveDesc, mouseX, mouseY, () -> {
                WmiNetwork.send(new WmiPayloads.GivePayload(first.id(), paletteResult.giveCount()));
                onClose();
            });
            listY += 22;
        }

        int ax = barX;
        for (int i = 0; i < Math.min(4, paletteResult.matchingActions().size()); i++) {
            CommandPalette.WorldAction wa = paletteResult.matchingActions().get(i);
            String label = Component.translatable("gui.wmi.action." + wa.id()).getString();
            int bw = Math.min(60, WmiVector.width(font, label) + 8);
            if (ax + bw > barX + barW) {
                break;
            }
            drawSmallButton(g, ax, listY, bw, 14, label, mouseX, mouseY, () -> {
                WmiNetwork.send(new WmiPayloads.ActionPayload(wa.id()));
                onClose();
            });
            ax += bw + 4;
        }
        if (!paletteResult.matchingActions().isEmpty()) {
            listY += 18;
        }

        int cols = barW / 20;
        List<CatalogItem> items = paletteResult.matchingItems();
        int maxItems = cols * 4;
        for (int i = 0; i < Math.min(items.size(), maxItems); i++) {
            CatalogItem it = items.get(i);
            int sx = barX + (i % cols) * 20;
            int sy = listY + (i / cols) * 20;
            drawFloatingSlot(g, sx, sy, it.id(), 1, false, mouseX, mouseY);
        }
    }

    /**
     * Onglets marque-pages collés au bord extérieur de la fenêtre, à la manière des onglets des conteneurs :
     * arbre de fabrication et, en mode Admin, don d'une pile (un seul exemplaire pour un objet non empilable).
     */
    private void drawSideTabs(GuiGraphicsExtractor g, int x0, int y0, int boxW, int mouseX, int mouseY) {
        if (targetItemId == null || targetItemId.isEmpty()) {
            return;
        }
        WmiTheme th = theme();
        int tx = x0 + boxW - 2;
        int tw = 24;
        int th1 = 22;
        int ty = y0 + 26;
        boolean treeActive = mode == ViewMode.CRAFT_TREE;
        sideTab(g, th, tx, ty, tw, th1, new ItemStack(net.minecraft.world.item.Items.CRAFTING_TABLE), treeActive,
                Component.translatable("gui.wmi.btn.tree").getString(), mouseX, mouseY,
                () -> navigateTo(treeActive ? ViewMode.RECIPES : ViewMode.CRAFT_TREE, targetItemId));
        if (WmiClient.overlay().canGiveItems(Minecraft.getInstance())) {
            ItemStack st = WmiClientIndex.stackFor(targetItemId);
            int count = st.isEmpty() ? 64 : Math.max(1, Math.min(64, st.getMaxStackSize()));
            sideTab(g, th, tx, ty + th1 + 3, tw, th1, st, false, WmiText.s("wmi.recipe.give_count", count), mouseX, mouseY,
                    () -> WmiClient.overlay().giveItemToPlayer(targetItemId, count));
        }
    }

    private void sideTab(GuiGraphicsExtractor g, WmiTheme th, int x, int y, int w, int h, ItemStack icon, boolean active,
                         String tooltip, int mouseX, int mouseY, Runnable onClick) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        WmiDraw.drawBox(g, x, y, w, h, active ? th.accentSoft : (hover ? th.slotHover : th.panelBg),
                (active || hover) ? th.accent : th.border);
        if (!icon.isEmpty()) {
            g.fakeItem(icon, x + (w - 16) / 2 + 1, y + (h - 16) / 2);
        }
        if (hover) {
            g.setTooltipForNextFrame(Component.literal(tooltip), WmiUiScale.real(mouseX), WmiUiScale.real(mouseY));
        }
        clickZones.add(new ClickableZone(x, y, w, h, onClick));
    }

    private void drawSmallButton(
            GuiGraphicsExtractor g,
            int x,
            int y,
            int w,
            int h,
            String text,
            int mouseX,
            int mouseY,
            Runnable onClick
    ) {
        WmiTheme th = theme();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        WmiDraw.drawBox(g, x, y, w, h, hover ? th.accentSoft : th.slotBg, hover ? th.accent : th.innerBorder);
        String t = trimToWidth(text, w - 4);
        WmiDraw.drawText(g, font, t, x + (w - WmiVector.width(font, t)) / 2, y + (h - 8) / 2, hover ? th.accent : th.textMain);
        clickZones.add(new ClickableZone(x, y, w, h, onClick));
    }

    private String trimToWidth(String s, int maxW) {
        if (WmiVector.width(font, s) <= maxW) {
            return s;
        }
        return WmiVector.trim(font, s, Math.max(8, maxW - 6)) + "..";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent rawEvent, boolean doubleClick) {
        MouseButtonEvent event = ui(rawEvent);
        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if (WmiOverlayInput.isLeftClick(btn)) {
            for (ClickableZone z : clickZones) {
                if (z.contains(mx, my)) {
                    z.action().run();
                    return true;
                }
            }
        }
        if (hoveredItemId != null) {
            if (WmiOverlayInput.isLeftClick(btn)) {
                navigateTo(ViewMode.RECIPES, hoveredItemId);
                return true;
            } else if (WmiOverlayInput.isRightClick(btn)) {
                navigateTo(ViewMode.USES, hoveredItemId);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mode == ViewMode.CRAFT_TREE) {
            treeScrollOffset = Math.max(0, treeScrollOffset + (scrollY < 0 ? 1 : -1));
            return true;
        }
        if (mode == ViewMode.RECIPES || mode == ViewMode.USES) {
            if (!availableTypes.isEmpty()) {
                String activeType = availableTypes.get(currentTab);
                List<RecipeEntry> activeList = recipesByType.getOrDefault(activeType, List.of());
                int totalPages = Math.max(1, (activeList.size() + RECIPES_PER_PAGE - 1) / RECIPES_PER_PAGE);
                if (totalPages > 1) {
                    currentPage = Math.floorMod(currentPage + (scrollY < 0 ? 1 : -1), totalPages);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // Fenêtre de la touche N en jeu : note textuelle ou objet (seul ou avec son arbre), comme les Notes de l'inventaire
    private boolean editorItemMode = false;
    private String editorPicked;
    private int editorQty = 1;
    private String editorMatchQuery;
    private List<String> editorMatches = List.of();

    /**
     * Fenêtre de saisie rapide des Notes (touche N en jeu), à l'emplacement des Notes du HUD : en-tête des Notes (livre
     * et titre), bascule « Note » / « Objet » (ou Tab), champ ; en mode objet, objets trouvés (clic : choisir), quantité
     * (boutons ou nombre en tête de la recherche, « 16 torche ») et ajout seul ou avec l'arbre. Entrée valide, Échap ferme.
     */
    private void renderPostItEditor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        WmiOverlay o = WmiClient.overlay();
        WmiTheme th = theme();
        int x = 8;
        int y = 8;
        int boxW = 200;
        int boxH = 22 + 15 + (editorItemMode ? 30 : 0) + 3;
        WmiDraw.drawFrostedPanel(g, x, y, boxW, boxH);

        // En-tête identique aux Notes de l'inventaire
        WmiDraw.drawSmallItem(g, WmiNotesPanel.notesIcon(), x + 4, y + 6, 12);
        WmiDraw.drawText(g, font, WmiText.s("wmi.notes.title"), x + 17, y + 7, th.textMain);
        String itemLabel = WmiText.s("wmi.notes.mode_item");
        String noteLabel = WmiText.s("wmi.notes.mode_note");
        int itemW = WmiVector.width(font, itemLabel) + 8;
        int noteW = WmiVector.width(font, noteLabel) + 8;
        int itemX = x + boxW - 5 - itemW;
        int noteX = itemX - 2 - noteW;
        modeButton(g, noteX, y + 5, noteW, noteLabel, !editorItemMode, mouseX, mouseY, () -> setEditorItemMode(false));
        modeButton(g, itemX, y + 5, itemW, itemLabel, editorItemMode, mouseX, mouseY, () -> setEditorItemMode(true));

        // Champ de saisie
        int fieldY = y + 22;
        int fieldW = boxW - 28;
        WmiDraw.drawBox(g, x + 4, fieldY, fieldW, 13, th.slotBg, th.accent);
        if (paletteInput.isEmpty() && editorItemMode) {
            WmiDraw.drawText(g, font, trimToWidth(WmiText.s("wmi.notes.search_placeholder"), fieldW - 6), x + 7, fieldY + 3, th.textMuted);
        }
        String shown = paletteInput + ((System.currentTimeMillis() / 500L) % 2 == 0 ? "_" : "");
        WmiDraw.drawText(g, font, trimToWidth(shown, fieldW - 6), x + 7, fieldY + 3, th.textMain);
        drawSmallButton(g, x + 6 + fieldW, fieldY, 18, 13, WmiText.s("wmi.common.ok"), mouseX, mouseY, () -> commitEditor(false));

        if (editorItemMode) {
            renderEditorPicker(g, o, th, x, fieldY + 15, boxW, mouseX, mouseY);
        }
    }

    private void modeButton(GuiGraphicsExtractor g, int x, int y, int w, String label, boolean active, int mouseX, int mouseY, Runnable action) {
        WmiTheme th = theme();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
        WmiDraw.drawBox(g, x, y, w, 12, active || hover ? th.accentSoft : th.slotBg, active || hover ? th.accent : th.innerBorder);
        WmiDraw.drawText(g, font, label, x + 4, y + 2, active || hover ? th.accent : th.textMain);
        clickZones.add(new ClickableZone(x, y, w, 12, action));
    }

    private void setEditorItemMode(boolean item) {
        editorItemMode = item;
        editorPicked = null;
        editorMatchQuery = null;
    }

    /** Recherche de l'objet, sans le nombre éventuel en tête (« 16 torche » : 16 torches). */
    private String editorQuery() {
        String q = paletteInput.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,4})\\s+(.*)$").matcher(q);
        return m.matches() ? m.group(2) : q;
    }

    private int editorCount() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,4})\\s+.*$").matcher(paletteInput.trim());
        return m.matches() ? Math.max(1, Integer.parseInt(m.group(1))) : editorQty;
    }

    private void renderEditorPicker(GuiGraphicsExtractor g, WmiOverlay o, WmiTheme th, int x, int ry, int boxW,
                                    int mouseX, int mouseY) {
        String q = editorQuery();
        if (!q.equals(editorMatchQuery)) {
            editorMatchQuery = q;
            List<String> found = new java.util.ArrayList<>();
            if (!q.isEmpty()) {
                for (waymoreitems.core.CatalogItem it : WmiClientIndex.peek().search(q)) {
                    found.add(it.id());
                    if (found.size() >= 24) {
                        break;
                    }
                }
            }
            editorMatches = found;
        }
        List<String> shown = new java.util.ArrayList<>();
        if (editorPicked != null) {
            shown.add(editorPicked);
        }
        for (String id : editorMatches) {
            if (!id.equals(editorPicked)) {
                shown.add(id);
            }
        }
        int slots = Math.max(1, (boxW - 8) / 13);
        String hovered = null;
        if (shown.isEmpty()) {
            WmiDraw.drawText(g, font, trimToWidth(WmiText.s("wmi.notes.search_hint"), boxW - 10), x + 5, ry + 3, th.textMuted);
        }
        for (int i = 0; i < shown.size() && i < slots; i++) {
            String id = shown.get(i);
            int sx = x + 4 + i * 13;
            boolean hover = mouseX >= sx && mouseX < sx + 12 && mouseY >= ry && mouseY < ry + 12;
            if (id.equals(editorPicked) || hover) {
                WmiDraw.drawBox(g, sx - 1, ry - 1, 14, 14, th.accentSoft, th.accent);
            }
            ItemStack st = WmiClientIndex.stackFor(id);
            if (!st.isEmpty()) {
                WmiDraw.drawSmallItem(g, st, sx, ry, 12);
            }
            if (hover) {
                hovered = id;
            }
            clickZones.add(new ClickableZone(sx, ry, 12, 12, () -> editorPicked = id));
        }

        int ay = ry + 15;
        drawSmallButton(g, x + 4, ay, 11, 12, "-", mouseX, mouseY, () -> editorQty = Math.max(1, editorQty - 1));
        String qty = "×" + editorCount();
        int qtyW = Math.max(22, WmiVector.width(font, qty) + 4);
        WmiDraw.drawText(g, font, qty, x + 16 + (qtyW - WmiVector.width(font, qty)) / 2, ay + 2, th.textMain);
        drawSmallButton(g, x + 17 + qtyW, ay, 11, 12, "+", mouseX, mouseY, () -> editorQty = Math.min(9999, editorQty + 1));
        String target = editorTarget();
        String treeLabel = WmiText.s("wmi.notes.add_tree");
        String itemLabel = WmiText.s("wmi.notes.add_item");
        int treeW = WmiVector.width(font, treeLabel) + 8;
        int itemW = WmiVector.width(font, itemLabel) + 8;
        int treeX = x + boxW - 4 - treeW;
        int itemX = treeX - 2 - itemW;
        if (target != null) {
            drawSmallButton(g, itemX, ay, itemW, 12, itemLabel, mouseX, mouseY, () -> commitEditor(false));
            if (!WmiClientIndex.peek().recipesForOutput(target).isEmpty()) {
                drawSmallButton(g, treeX, ay, treeW, 12, treeLabel, mouseX, mouseY, () -> commitEditor(true));
            }
        }
        if (hovered != null) {
            ItemStack st = WmiClientIndex.stackFor(hovered);
            if (!st.isEmpty()) {
                WmiNames.tooltip(g, font, st, mouseX, mouseY);
            }
        }
    }

    private String editorTarget() {
        return editorPicked != null ? editorPicked : (editorMatches.isEmpty() ? null : editorMatches.get(0));
    }

    /** Valide la saisie (note, ou objet seul ou avec son arbre), puis revient au jeu sans champ en attente. */
    private void commitEditor(boolean tree) {
        if (editorItemMode) {
            String id = editorTarget();
            if (id == null) {
                return;
            }
            int count = editorCount();
            if (tree && !WmiClientIndex.peek().recipesForOutput(id).isEmpty()) {
                checklist.useIndex(WmiClientIndex.peek());
                checklist.pinCraft(id, count, java.util.Map.of(), null);
            } else {
                checklist.pinOrIncrementItem(id, count);
            }
        } else if (!paletteInput.isBlank()) {
            checklist.addNote(paletteInput);
        }
        paletteInput = "";
        onClose();
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if ((mode == ViewMode.COMMAND_PALETTE || mode == ViewMode.POST_IT_INPUT) && event.isAllowedChatCharacter()) {
            paletteInput += event.codepointAsString();
            if (mode == ViewMode.COMMAND_PALETTE) {
                refreshData();
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean isBackspace = (key == InputConstants.KEY_BACKSPACE || key == 259);
        boolean isEnter = (event.isConfirmation() || key == InputConstants.KEY_RETURN || key == 257 || key == 335);

        if (mode == ViewMode.POST_IT_INPUT) {
            if (isBackspace) {
                if (!paletteInput.isEmpty()) {
                    paletteInput = paletteInput.substring(0, paletteInput.length() - 1);
                }
                return true;
            }
            if (isEnter) {
                commitEditor(false);
                return true;
            }
            if (key == InputConstants.KEY_TAB) {
                setEditorItemMode(!editorItemMode);
                return true;
            }
            return super.keyPressed(event);
        }
        if (mode == ViewMode.COMMAND_PALETTE) {
            if (isBackspace) {
                if (!paletteInput.isEmpty()) {
                    paletteInput = paletteInput.substring(0, paletteInput.length() - 1);
                    refreshData();
                }
                return true;
            }
            if (isEnter) {
                if (paletteResult != null) {
                    if (paletteResult.kind() == CommandPalette.IntentKind.EXECUTE_ACTION && paletteResult.action() != null) {
                        WmiNetwork.send(new WmiPayloads.ActionPayload(paletteResult.action().id()));
                        onClose();
                        return true;
                    }
                    if (paletteResult.kind() == CommandPalette.IntentKind.GIVE_ITEM && !paletteResult.matchingItems().isEmpty()) {
                        WmiNetwork.send(new WmiPayloads.GivePayload(
                                paletteResult.matchingItems().get(0).id(), paletteResult.giveCount()));
                        onClose();
                        return true;
                    }
                    if (!paletteResult.matchingItems().isEmpty()) {
                        navigateTo(ViewMode.RECIPES, paletteResult.matchingItems().get(0).id());
                        return true;
                    }
                }
            }
        } else {
            if (isBackspace) {
                return goBack();
            }
            if (hoveredItemId != null) {
                if (WmiClient.KEY_RECIPES.matches(event)) {
                    navigateTo(ViewMode.RECIPES, hoveredItemId);
                    return true;
                }
                if (WmiClient.KEY_USES.matches(event)) {
                    navigateTo(ViewMode.USES, hoveredItemId);
                    return true;
                }
                if (WmiClient.KEY_PIN.matches(event)) {
                    checklist.pinOrIncrementItem(hoveredItemId, 1);
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }
}
