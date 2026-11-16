package waymoreitems.compat.jei;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.slf4j.Logger;
import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.client.WmiClient;
import waymoreitems.client.WmiExternalSources;
import waymoreitems.client.WmiRecipeScreen;
import waymoreitems.core.ImportedRecipeLayout;
import waymoreitems.core.RecipeEntry;

/**
 * Pont entre JEI et WMI, par l'interface publique de JEI seulement : recettes que les mods décrivent à JEI (catégories,
 * cases, catalyseurs), zones que les machines déclarent à côté de leur inventaire et zones cliquables de leurs écrans.
 * Chargé par {@link WmiJeiPlugin}, donc jamais sans JEI.
 */
final class WmiJeiBridge implements WmiExternalSources.Bridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Au-delà, une catégorie dont les recettes échouent toutes est abandonnée. */
    private static final int MAX_FAILURES_PER_CATEGORY = 20;
    /** Variantes retenues par case (étiquettes immenses, façades) : la recherche et l'arbre n'en lisent pas plus. */
    private static final int MAX_ALTERNATIVES = 256;
    /**
     * Catégories de JEI qui ne décrivent pas une fabrication (réparation et fusion d'enchantements, désenchantement) :
     * des centaines de combinaisons qui encombreraient les usages et l'arbre de fabrication.
     */
    private static final Set<String> SKIPPED_CATEGORIES = Set.of("minecraft:anvil", "minecraft:grindstone");

    private volatile IJeiRuntime runtime;
    /** Vrai pendant que WMI interroge JEI sur les zones d'écran : le greffon ne rend alors pas les zones de WMI. */
    private boolean queryingAreas;

    void setRuntime(IJeiRuntime rt) {
        this.runtime = rt;
    }

    boolean queryingAreas() {
        return queryingAreas;
    }

    // ==================== RECETTES ====================

    /** Compteurs d'un import, écrits dans le journal du jeu. */
    private static final class Stats {
        final Map<String, Integer> importedByMod = new TreeMap<>();
        final Map<String, Integer> importedByCategory = new TreeMap<>();
        final Map<String, Integer> nonItemByType = new TreeMap<>();
        final Map<String, Integer> noItemOutputByCategory = new TreeMap<>();
        final Map<String, Integer> failedByCategory = new TreeMap<>();
        /** Recettes masquées dans JEI (par un mod ou par le joueur), non importées : comptées par mod. */
        final Map<String, Integer> hiddenByMod = new TreeMap<>();
        final Set<String> describedCategories = new java.util.HashSet<>();
        int alreadyRead;
        int noItemOutput;
        int failed;
        int layouts;
    }

    @Override
    public WmiExternalSources.Imported importRecipes(Set<String> knownRecipeIds) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return WmiExternalSources.Imported.EMPTY;
        }
        long start = System.nanoTime();
        Stats stats = new Stats();
        List<RecipeEntry> recipes = new ArrayList<>();
        Map<String, RecipeLayoutSpec> layouts = new HashMap<>();
        Set<String> seen = new java.util.HashSet<>(knownRecipeIds);
        ImportedRecipeLayout.Cache cache = new ImportedRecipeLayout.Cache();
        try {
            IRecipeManager rm = rt.getRecipeManager();
            IFocusGroup noFocus = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            List<IRecipeCategory<?>> categories = rm.createRecipeCategoryLookup().get().toList();
            rm.createRecipeCategoryLookup().includeHidden().get()
                    .filter(c -> !categories.contains(c))
                    .forEach(c -> stats.hiddenByMod.merge("category " + c.getRecipeType().getUid(), 1, Integer::sum));
            for (IRecipeCategory<?> category : categories) {
                importCategory(rm, category, noFocus, seen, cache, recipes, layouts, stats);
            }
        } catch (Throwable t) {
            // Appel d'un autre mod : une erreur de JEI ne doit jamais empêcher WMI de construire son index
            LOGGER.warn("WMI: JEI recipe import stopped", t);
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.info("WMI: JEI import: {} recipes added ({} layouts) in {} ms; already read {}, without item output {},"
                        + " failed {}; by mod {}; by category {}; without item output by category {}; failed by category {};"
                + " non-item ingredients skipped {}; hidden in JEI (not imported) by mod {}",
                recipes.size(), stats.layouts, ms, stats.alreadyRead, stats.noItemOutput, stats.failed,
                stats.importedByMod, stats.importedByCategory, stats.noItemOutputByCategory, stats.failedByCategory,
                stats.nonItemByType, stats.hiddenByMod);
        return new WmiExternalSources.Imported(recipes, layouts);
    }

    private static <T> void importCategory(IRecipeManager rm, IRecipeCategory<T> category, IFocusGroup noFocus,
                                           Set<String> seen, ImportedRecipeLayout.Cache cache,
                                           List<RecipeEntry> out, Map<String, RecipeLayoutSpec> layouts, Stats stats) {
        IRecipeType<T> type = category.getRecipeType();
        String typeId = type.getUid().toString();
        if (SKIPPED_CATEGORIES.contains(typeId)) {
            return;
        }
        String title = category.getTitle().getString();
        List<T> list = rm.createRecipeLookup(type).get().toList();
        countHidden(rm, category, type, list, stats);
        if (list.isEmpty()) {
            return;
        }
        List<String> workstations = new ArrayList<>();
        try {
            rm.createCraftingStationLookup(type).getItemStack().forEach(s -> {
                String id = itemId(s);
                if (id != null && !workstations.contains(id)) {
                    workstations.add(id);
                }
            });
        } catch (Throwable ignored) {
            // catalyseurs facultatifs
        }
        int failures = 0;
        int index = 0;
        for (T recipe : list) {
            index++;
            String recipeId = recipeId(category, recipe);
            if (recipeId != null && seen.contains(recipeId)) {
                stats.alreadyRead++;
                continue;
            }
            if (recipeId == null) {
                recipeId = typeId + "/jei_" + index;
            }
            try {
                RecipeEntry entry = convert(rm, category, recipe, noFocus, recipeId, typeId, title, workstations, cache,
                        layouts, stats);
                if (entry == null) {
                    stats.noItemOutput++;
                    stats.noItemOutputByCategory.merge(typeId, 1, Integer::sum);
                    continue;
                }
                seen.add(recipeId);
                out.add(entry);
                stats.importedByMod.merge(namespace(recipeId), 1, Integer::sum);
                stats.importedByCategory.merge(typeId, 1, Integer::sum);
            } catch (Throwable t) {
                stats.failed++;
                stats.failedByCategory.merge(typeId, 1, Integer::sum);
                if (++failures == MAX_FAILURES_PER_CATEGORY) {
                    LOGGER.warn("WMI: JEI category {} skipped after {} failures", typeId, failures, t);
                    return;
                }
            }
        }
    }

    private static <T> void countHidden(IRecipeManager rm, IRecipeCategory<T> category, IRecipeType<T> type,
                                        List<T> visible, Stats stats) {
        try {
            Set<T> shown = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            shown.addAll(visible);
            rm.createRecipeLookup(type).includeHidden().get().forEach(r -> {
                if (!shown.contains(r)) {
                    String id = recipeId(category, r);
                    stats.hiddenByMod.merge(id == null ? type.getUid().getNamespace() : namespace(id), 1, Integer::sum);
                }
            });
        } catch (Throwable ignored) {
            // simple relevé
        }
    }

    private static <T> RecipeEntry convert(IRecipeManager rm, IRecipeCategory<T> category, T recipe,
                                           IFocusGroup noFocus, String recipeId, String typeId, String title,
                                           List<String> workstations, ImportedRecipeLayout.Cache cache,
                                           Map<String, RecipeLayoutSpec> layouts, Stats stats) {
        Optional<IRecipeLayoutDrawable<T>> drawable = rm.createRecipeLayoutDrawable(category, recipe, noFocus);
        if (drawable.isEmpty()) {
            return null;
        }
        IRecipeLayoutDrawable<T> layout = drawable.get();
        layout.setPosition(0, 0);
        List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
        List<RecipeEntry.IngredientSlot> catalysts = new ArrayList<>();
        List<RecipeEntry.ItemAmount> outputs = new ArrayList<>();
        List<ImportedRecipeLayout.Slot> slots = new ArrayList<>();
        boolean positioned = true;
        for (IRecipeSlotView view : layout.getRecipeSlotsView().getSlotViews()) {
            RecipeIngredientRole role = view.getRole();
            RecipeLayoutSpec.SlotRole wmiRole = switch (role) {
                case INPUT -> RecipeLayoutSpec.SlotRole.INPUT;
                case OUTPUT -> RecipeLayoutSpec.SlotRole.OUTPUT;
                case CRAFTING_STATION -> RecipeLayoutSpec.SlotRole.CATALYST;
                default -> null;
            };
            if (wmiRole == null) {
                continue;
            }
            List<ITypedIngredient<?>> all = view.getAllIngredientsList();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            int count = 1;
            for (ITypedIngredient<?> ti : all) {
                if (ti == null) {
                    continue;
                }
                Optional<ItemStack> stack = ti.getItemStack();
                if (stack.isPresent()) {
                    String id = itemId(stack.get());
                    if (id != null && ids.size() < MAX_ALTERNATIVES) {
                        if (ids.isEmpty()) {
                            count = Math.max(1, stack.get().getCount());
                        }
                        ids.add(id);
                    }
                } else {
                    // Fluide ou autre ingrédient non objet : compté et laissé de côté (pas encore lu par WMI)
                    stats.nonItemByType.merge(ti.getType().getUid(), 1, Integer::sum);
                }
            }
            int ingredient = -1;
            if (!ids.isEmpty()) {
                List<String> alternatives = List.copyOf(ids);
                switch (wmiRole) {
                    case INPUT -> {
                        String tag = view.getTagKey().map(k -> k.location().toString()).orElse("");
                        ingredient = inputs.size();
                        inputs.add(new RecipeEntry.IngredientSlot(alternatives, count, tag));
                    }
                    case CATALYST -> {
                        ingredient = catalysts.size();
                        catalysts.add(new RecipeEntry.IngredientSlot(alternatives, count));
                    }
                    case OUTPUT -> {
                        ingredient = outputs.size();
                        outputs.add(new RecipeEntry.ItemAmount(alternatives.get(0), count));
                    }
                }
            }
            int[] pos = view instanceof IRecipeSlotDrawable d
                    ? locate(d, category.getWidth(), category.getHeight()) : null;
            if (pos != null) {
                slots.add(new ImportedRecipeLayout.Slot(wmiRole, pos[0], pos[1], ingredient));
            } else {
                positioned = false;
            }
        }
        if (outputs.isEmpty()) {
            return null;
        }
        List<Integer> cells = positioned ? ImportedRecipeLayout.gridCells(slots, inputs.size()) : List.of();
        RecipeEntry entry = new RecipeEntry(recipeId, typeId, title, workstations, inputs, catalysts, outputs,
                0, 0, 0, cells);
        RecipeLayoutSpec spec = positioned ? cache.get(typeId, title, slots, cells) : null;
        if (spec != null) {
            layouts.put(recipeId, spec);
            stats.layouts++;
        }
        if (stats.describedCategories.add(typeId)) {
            // Une ligne par catégorie importée : cases relevées dans JEI, pour vérifier la disposition reprise
            LOGGER.info("WMI: JEI category {} ({}x{}): inputs {}, outputs {}, slots {}, grid {}, layout {}", typeId,
                    category.getWidth(), category.getHeight(), inputs.size(), outputs.size(), slots, cells,
                    spec == null ? "generic" : "kept");
        }
        return entry;
    }

    /**
     * Coin de la case de 18 qu'occupe une case de JEI dans le plan de sa catégorie, ou {@code null}. JEI ne donne pas
     * sa position (sa zone « avec fond » reste à l'origine) ; elle se lit par sa zone sensible ({@code isMouseOver},
     * en coordonnées de la recette), sondée au pas de 2 puis affinée au pixel. Zone de 16 : case sans son fond.
     */
    private static int[] locate(IRecipeSlotDrawable slot, int width, int height) {
        int maxX = Math.max(18, width) + 4;
        int maxY = Math.max(18, height) + 4;
        for (int y = -4; y < maxY; y += 2) {
            for (int x = -4; x < maxX; x += 2) {
                if (!slot.isMouseOver(x + 0.5, y + 0.5)) {
                    continue;
                }
                int left = x;
                while (left > x - 2 && slot.isMouseOver(left - 0.5, y + 0.5)) {
                    left--;
                }
                int top = y;
                while (top > y - 18 && slot.isMouseOver(left + 0.5, top - 0.5)) {
                    top--;
                }
                int right = left;
                while (right < left + 40 && slot.isMouseOver(right + 1.5, top + 0.5)) {
                    right++;
                }
                int w = right - left + 1;
                int pad = w < ImportedRecipeLayout.SLOT ? (ImportedRecipeLayout.SLOT - w) / 2 : 0;
                return new int[] {left - pad, top - pad};
            }
        }
        return null;
    }

    /** Identifiant de la recette : celui de Minecraft si JEI présente une recette du jeu, sinon celui de la catégorie. */
    private static <T> String recipeId(IRecipeCategory<T> category, T recipe) {
        if (recipe instanceof RecipeHolder<?> holder) {
            return holder.id().identifier().toString();
        }
        try {
            Identifier id = category.getIdentifier(recipe);
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : id;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    // ==================== ZONES D'ÉCRAN ====================

    @Override
    public List<Rect2i> screenAreas(Screen screen) {
        IJeiRuntime rt = runtime;
        if (rt == null || screen == null) {
            return List.of();
        }
        queryingAreas = true;
        try {
            return rt.getScreenHelper().getGuiExclusionAreas(screen).toList();
        } catch (Throwable t) {
            return List.of();
        } finally {
            queryingAreas = false;
        }
    }

    @Override
    public boolean clickArea(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return false;
        }
        try {
            // Coordonnées relatives au coin de l'inventaire, comme JEI les passe lui-même
            Optional<IGuiClickableArea> area = rt.getScreenHelper()
                    .getGuiClickableArea(screen, mouseX - screen.getLeftPos(), mouseY - screen.getTopPos()).findFirst();
            if (area.isEmpty()) {
                return false;
            }
            ViewRequest request = new ViewRequest(rt, screen);
            area.get().onClick(rt.getJeiHelpers().getFocusFactory(), request);
            return request.opened;
        } catch (Throwable t) {
            LOGGER.warn("WMI: JEI clickable area failed", t);
            return false;
        }
    }

    /**
     * Demande d'affichage que la zone cliquable adresse à JEI, redirigée vers les fenêtres de WMI : un objet ouvre ses
     * recettes ou ses usages ; une catégorie ouvre les usages de son premier catalyseur (la machine), sur l'onglet de
     * la catégorie.
     */
    private static final class ViewRequest implements IRecipesGui {
        private final IJeiRuntime rt;
        private final Screen parent;
        boolean opened;

        ViewRequest(IJeiRuntime rt, Screen parent) {
            this.rt = rt;
            this.parent = parent;
        }

        @Override
        public void show(List<IFocus<?>> focuses) {
            for (IFocus<?> focus : focuses) {
                Optional<ItemStack> stack = focus.getTypedValue().getItemStack();
                String id = stack.map(WmiJeiBridge::itemId).orElse(null);
                if (id != null) {
                    open(focus.getRole() == RecipeIngredientRole.OUTPUT
                            ? WmiRecipeScreen.ViewMode.RECIPES : WmiRecipeScreen.ViewMode.USES, id);
                    return;
                }
            }
        }

        @Override
        public void showTypes(List<IRecipeType<?>> types) {
            for (IRecipeType<?> type : types) {
                Optional<ItemStack> station = rt.getRecipeManager().createCraftingStationLookup(type).getItemStack().findFirst();
                String id = station.map(WmiJeiBridge::itemId).orElse(null);
                if (id != null) {
                    opened = true;
                    WmiClient.overlay().openWorkstation(Minecraft.getInstance().gui.screen(), id,
                            type.getUid().toString());
                    return;
                }
            }
        }

        @Override
        public <T> void showRecipes(IRecipeCategory<T> category, List<T> recipes, List<IFocus<?>> focuses) {
            if (!focuses.isEmpty()) {
                show(focuses);
            }
            if (!opened) {
                showTypes(List.of(category.getRecipeType()));
            }
        }

        @Override
        public <T> Optional<T> getIngredientUnderMouse(mezz.jei.api.ingredients.IIngredientType<T> type) {
            return Optional.empty();
        }

        @Override
        public Optional<Screen> getParentScreen() {
            return Optional.ofNullable(parent);
        }

        private void open(WmiRecipeScreen.ViewMode mode, String itemId) {
            opened = true;
            WmiClient.overlay().openModal(Minecraft.getInstance().gui.screen(), mode, itemId);
        }
    }
}
