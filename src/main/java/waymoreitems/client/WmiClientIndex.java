package waymoreitems.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.level.block.EntityBlock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiRegistry;
import waymoreitems.core.CatalogItem;
import waymoreitems.core.CoverageReport;
import waymoreitems.core.ItemCategories;
import waymoreitems.core.RecipeEntry;
import waymoreitems.core.RecipeIndex;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Extracteur universel des objets et des recettes côté client (Minecraft 26.3, NeoForge).
 * Diffère automatiquement l'indexation des recettes et des tags jusqu'à ce que {@code mc.level}
 * et les tags du registre d'objets soient liés côté client.
 */
public final class WmiClientIndex {
    private static final Logger LOGGER = LogUtils.getLogger();
    public record CreativeTabEntry(String id, String title, ItemStack icon, Set<String> itemIds) {
    }

    private static volatile RecipeIndex currentIndex = new RecipeIndex(List.of(), List.of());
    private static volatile List<CreativeTabEntry> currentCreativeTabs = List.of();
    private static volatile List<CoverageReport.Sample> coverageSamples;
    private static volatile RecipeMap pendingRecipeMap;
    private static volatile boolean needsRebuild = true;
    private static volatile boolean builtWithBoundTags = false;
    /** Table de traduction de Minecraft en vigueur lors de la dernière construction de l'index. */
    private static volatile Language builtWithLanguage;
    private static int ticksWaitingForTags = 0;
    /** Piles d'affichage par identifiant ; table remplacée d'un bloc à chaque publication d'un index (fil de rendu seul). */
    private static Map<String, ItemStack> stackById = new HashMap<>();
    /** Construction en cours, étalée sur plusieurs ticks ({@code null} si aucune). */
    private static @Nullable IndexBuild build;
    /** Temps de construction par tick quand un index est déjà affiché (le jeu reste fluide pendant la reconstruction). */
    private static final long TICK_BUDGET_NANOS = 6_000_000L;
    /** Temps par tick pour la première construction, quand le catalogue est encore vide. */
    private static final long FIRST_BUILD_BUDGET_NANOS = 30_000_000L;
    private static final ExecutorService INDEX_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WMI index");
        t.setDaemon(true);
        return t;
    });

    private WmiClientIndex() {
    }

    public static List<CreativeTabEntry> creativeTabs() {
        if (currentCreativeTabs.isEmpty() && build == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                rebuildNow();
            }
        }
        return currentCreativeTabs;
    }

    /**
     * Index courant. S'il est vide et qu'aucune construction n'est en cours, il est construit d'un bloc ; pendant une
     * construction étalée, l'index précédent reste servi jusqu'à son remplacement.
     */
    public static RecipeIndex get() {
        if (currentIndex.allItems().isEmpty() && build == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                rebuildNow();
            }
        }
        return currentIndex;
    }

    /** Index courant, sans jamais déclencher de reconstruction (vide tant que l'indexation différée n'a pas eu lieu). */
    public static RecipeIndex peek() {
        return currentIndex;
    }

    /** Recettes connues du jeu à la dernière indexation, lues ou non ({@code null} : aucune recette reçue). */
    public static List<CoverageReport.Sample> coverageSamples() {
        return coverageSamples;
    }

    /** Recette reçue du serveur portant cet identifiant ({@code espace:chemin}), ou {@code null}. */
    public static net.minecraft.world.item.crafting.@Nullable Recipe<?> recipeById(String recipeId) {
        RecipeMap map = pendingRecipeMap;
        Identifier id = recipeId == null ? null : Identifier.tryParse(recipeId);
        if (map == null || id == null) {
            return null;
        }
        RecipeHolder<?> holder = map.byKey(ResourceKey.create(
                Registries.RECIPE, id));
        return holder == null ? null : holder.value();
    }

    public static void onRecipesSynced(RecipeMap recipeMap) {
        pendingRecipeMap = recipeMap;
        needsRebuild = true;
        ticksWaitingForTags = 0;
    }

    /**
     * Déconnexion ({@code ClientPlayerNetworkEvent.LoggingOut}) : oublie les recettes et l'index du monde quitté, pour
     * qu'un serveur qui n'envoie pas de recettes (sans WMI) n'affiche pas celles du monde précédent.
     */
    public static synchronized void clear() {
        pendingRecipeMap = null;
        currentIndex = new RecipeIndex(List.of(), List.of());
        currentCreativeTabs = List.of();
        coverageSamples = null;
        needsRebuild = true;
        builtWithBoundTags = false;
        builtWithLanguage = null;
        ticksWaitingForTags = 0;
        build = null;
        stackById = new HashMap<>();
    }

    public static void tickClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        boolean tagsBound = BuiltInRegistries.ITEM.getTags().findAny().isPresent();
        if (tagsBound && !builtWithBoundTags) {
            needsRebuild = true;
        }
        // Langue du jeu changée (ou ressources rechargées) : Minecraft remplace sa table de traduction à la fin du
        // rechargement ; les noms indexés, donc la recherche, doivent suivre
        if (Language.getInstance() != builtWithLanguage && !currentIndex.allItems().isEmpty()) {
            WmiNames.reset();
            needsRebuild = true;
            ticksWaitingForTags = 15;
        }
        if (needsRebuild && (tagsBound || ++ticksWaitingForTags >= 15)) {
            // Une construction en cours est abandonnée : ses recettes, ses tags ou sa langue sont dépassés
            build = new IndexBuild(mc);
        }
        IndexBuild b = build;
        if (b != null) {
            b.step(currentIndex.allItems().isEmpty() ? FIRST_BUILD_BUDGET_NANOS : TICK_BUDGET_NANOS, true);
        }
    }

    public static ItemStack stackFor(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Map<String, ItemStack> stacks = stackById;
        ItemStack cached = stacks.get(itemId);
        if (cached != null) {
            return cached;
        }
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (item != null && item != Items.AIR) {
                ItemStack s = new ItemStack(item);
                stacks.put(itemId, s);
                return s;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not build the stack for item", e);
        }
        return ItemStack.EMPTY;
    }

    /** Reconstruit l'index au prochain tick client (changement d'option de langue des noms d'objets). */
    public static void requestRebuild() {
        WmiNames.reset();
        needsRebuild = true;
        ticksWaitingForTags = 0;
    }

    public static void rebuild(RecipeMap recipeMap) {
        onRecipesSynced(recipeMap);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null && BuiltInRegistries.ITEM.getTags().findAny().isPresent()) {
            rebuildNow();
        }
    }

    /** Reconstruit l'index d'un bloc, sur-le-champ (commande de mesure, premier accès à un index vide). */
    public static synchronized void rebuildNow() {
        IndexBuild b = new IndexBuild(Minecraft.getInstance());
        build = b;
        b.step(Long.MAX_VALUE, false);
    }

    /** Vrai pendant la lecture des infobulles par l'index : les ajouts de WMI aux infobulles s'abstiennent. */
    static volatile boolean collectingTooltips = false;

    /**
     * Construction de l'index, découpée pour ne pas figer le jeu sur un gros pack. Lecture des objets (infobulles
     * comprises), des recettes et des extracteurs sur le fil de rendu, par tranches de temps à chaque tick (les
     * objets de Minecraft ne se lisent pas depuis un autre fil) ; indexation des données recueillies
     * ({@link RecipeIndex}, Java pur, sans lien avec le jeu) sur un fil d'arrière-plan ; publication de l'index, des
     * onglets créatifs et des piles d'un bloc, sur le fil de rendu. Une construction remplacée entre-temps
     * ({@link #build}) n'est jamais publiée.
     */
    private static final class IndexBuild {
        private static final int ITEMS = 0;
        private static final int RECIPES = 1;
        private static final int EXTRACTORS = 2;
        private static final int INDEXING = 3;
        private static final int DONE = 4;

        private final Minecraft mc;
        private final ContextMap context;
        private final Item.TooltipContext tooltipCtx;
        private final Map<String, String> modNames = new LinkedHashMap<>();
        private final List<Holder.Reference<Item>> itemRefs;
        private final List<RecipeHolder<?>> holders;
        private int itemPos;
        private int recipePos;
        private int phase = ITEMS;

        private final List<CatalogItem> catalog = new ArrayList<>();
        private final Map<String, ItemStack> stacks = new HashMap<>();
        private final List<RecipeEntry> entries = new ArrayList<>();
        private int recipesRead;
        private final Set<String> knownIds = new HashSet<>();
        // Couverture : chaque recette reçue, lue ou non (rapport de /wmi coverage) ; null si aucune recette reçue
        private final @Nullable List<CoverageReport.Sample> samples;
        private @Nullable CompletableFuture<RecipeIndex> indexing;

        /** Temps passé dans chaque phase (objets, recettes, extracteurs, indexation, publication), en nanosecondes. */
        private final long[] phaseNanos = new long[5];
        private final long startNanos = System.nanoTime();
        private int steps;

        IndexBuild(Minecraft mc) {
            this.mc = mc;
            builtWithLanguage = Language.getInstance();
            builtWithBoundTags = BuiltInRegistries.ITEM.getTags().findAny().isPresent();
            needsRebuild = false;
            ticksWaitingForTags = 0;
            boolean hasLevel = mc != null && mc.level != null;
            this.context = hasLevel ? SlotDisplayContext.fromLevel(mc.level) : ContextMap.EMPTY;
            this.tooltipCtx = hasLevel ? Item.TooltipContext.of(mc.level) : Item.TooltipContext.EMPTY;
            modNames.put("minecraft", "Minecraft");
            WmiPlatform.forEachMod(modNames::put);
            this.itemRefs = BuiltInRegistries.ITEM.listElements().toList();
            RecipeMap map = pendingRecipeMap;
            this.holders = map == null ? List.of() : List.copyOf(map.values());
            this.samples = map == null ? null : new ArrayList<>();
        }

        /**
         * Avance la construction d'environ {@code budgetNanos} au plus (fil de rendu). {@code async} : indexation sur le
         * fil d'arrière-plan, publiée à un tick suivant ; sinon tout est fait et publié avant de rendre la main.
         */
        void step(long budgetNanos, boolean async) {
            steps++;
            long begin = System.nanoTime();
            long deadline = budgetNanos == Long.MAX_VALUE ? Long.MAX_VALUE : begin + budgetNanos;
            if (phase == ITEMS) {
                long t = System.nanoTime();
                collectingTooltips = true;
                try {
                    while (itemPos < itemRefs.size()) {
                        readItem(itemRefs.get(itemPos++));
                        if ((itemPos & 15) == 0 && System.nanoTime() >= deadline) {
                            break;
                        }
                    }
                } finally {
                    collectingTooltips = false;
                }
                phaseNanos[ITEMS] += System.nanoTime() - t;
                if (itemPos < itemRefs.size()) {
                    return;
                }
                // Fluides après les objets (onglet « Fluides » et mot-clé !fluid)
                catalog.addAll(WmiFluids.buildCatalog(modNames));
                phase = RECIPES;
            }
            if (phase == RECIPES) {
                long t = System.nanoTime();
                while (recipePos < holders.size()) {
                    readRecipe(holders.get(recipePos++));
                    if ((recipePos & 15) == 0 && System.nanoTime() >= deadline) {
                        break;
                    }
                }
                phaseNanos[RECIPES] += System.nanoTime() - t;
                if (recipePos < holders.size()) {
                    return;
                }
                phase = EXTRACTORS;
            }
            if (phase == EXTRACTORS) {
                long t = System.nanoTime();
                recipesRead = entries.size();
                // Remplissage et vidange des seaux de fluide
                try {
                    entries.addAll(WmiFluids.containerRecipes());
                } catch (Exception e) {
                    LOGGER.debug("Could not build the fluid container recipes", e);
                }
                for (WmiRegistry.CustomRecipeExtractor ext : WmiRegistry.extractors()) {
                    try {
                        List<RecipeEntry> custom = ext.extractRecipes();
                        if (custom != null) {
                            entries.addAll(custom);
                        }
                    } catch (Exception e) {
                        LOGGER.debug("A custom recipe extractor failed", e);
                    }
                }
                // Source externe (navigateur de recettes installé à côté) : seulement les recettes que WMI n'a pas lues
                for (RecipeEntry e : entries) {
                    knownIds.add(e.id());
                }
                entries.addAll(WmiExternalSources.importRecipes(knownIds).recipes());
                phaseNanos[EXTRACTORS] += System.nanoTime() - t;
                phase = INDEXING;
                if (!async) {
                    long ti = System.nanoTime();
                    RecipeIndex index = new RecipeIndex(catalog, entries);
                    phaseNanos[INDEXING] = System.nanoTime() - ti;
                    publish(index);
                    logTimings();
                    return;
                }
                // Les listes passent au fil d'arrière-plan et ne sont plus modifiées ici
                List<CatalogItem> items = catalog;
                List<RecipeEntry> recipes = entries;
                indexing = CompletableFuture.supplyAsync(() -> {
                    long ti = System.nanoTime();
                    RecipeIndex index = new RecipeIndex(items, recipes);
                    phaseNanos[INDEXING] = System.nanoTime() - ti;
                    return index;
                }, INDEX_EXECUTOR);
                return;
            }
            if (phase == INDEXING && indexing != null && indexing.isDone()) {
                RecipeIndex index;
                try {
                    index = indexing.join();
                } catch (RuntimeException e) {
                    LOGGER.error("WMI : échec de l'indexation", e);
                    phase = DONE;
                    if (build == this) {
                        build = null;
                    }
                    return;
                }
                publish(index);
                logTimings();
            }
        }

        /** Remplace d'un bloc l'index, les piles et les onglets créatifs, si cette construction est toujours la bonne. */
        private void publish(RecipeIndex index) {
            phase = DONE;
            if (build != this) {
                return;
            }
            build = null;
            long t = System.nanoTime();
            currentIndex = index;
            stackById = stacks;
            // Lignes de texte des fiches dédiées : seules restent celles des recettes de ce nouvel index
            WmiDedicatedRecipes.retainOnly(knownIds);
            coverageSamples = samples == null ? null : List.copyOf(samples);
            currentCreativeTabs = buildCreativeTabs(mc);
            phaseNanos[DONE] = System.nanoTime() - t;
        }

        private void readItem(Holder.Reference<Item> ref) {
            Item item = ref.value();
            if (item == Items.AIR) {
                return;
            }
            Identifier key = ref.key().identifier();
            String id = key.toString();
            String ns = key.getNamespace();
            String modName = modNames.getOrDefault(ns, ns);
            ItemStack stack = new ItemStack(item);
            stacks.put(id, stack);
            String displayName = WmiNames.of(stack);
            Set<String> tags = new LinkedHashSet<>();
            ref.tags().forEach(tagKey -> tags.add(tagKey.location().toString()));
            String category = classifyItemCategory(item, stack, key.getPath(), tags);

            StringBuilder tipBuilder = new StringBuilder();
            if (mc != null && mc.player != null) {
                try {
                    List<Component> lines = stack.getTooltipLines(tooltipCtx, mc.player, TooltipFlag.Default.NORMAL);
                    for (int i = 1; i < lines.size(); i++) {
                        if (tipBuilder.length() > 0) {
                            tipBuilder.append(' ');
                        }
                        tipBuilder.append(lines.get(i).getString());
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not read the tooltip of an item", e);
                }
            }

            catalog.add(new CatalogItem(id, ns, modName, displayName, category, Math.max(1, stack.getMaxStackSize()), tags, tipBuilder.toString()));
        }

        private void readRecipe(RecipeHolder<?> holder) {
            boolean read = false;
            try {
                RecipeEntry entry = convertHolder(holder, context);
                if (entry != null && (!entry.outputs().isEmpty() || WmiDedicatedRecipes.isInfo(entry))) {
                    entries.add(entry);
                    read = true;
                }
            } catch (Exception e) {
                LOGGER.debug("A recipe could not be converted and was skipped", e);
            }
            String type;
            try {
                type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
            } catch (Exception e) {
                LOGGER.debug("Could not read the type of a recipe", e);
                type = "?";
            }
            if (samples != null) {
                samples.add(new CoverageReport.Sample(holder.id().identifier().toString(), type, read));
            }
        }

        private void logTimings() {
            WmiPerf.logRebuild(phaseNanos[ITEMS], phaseNanos[RECIPES], phaseNanos[EXTRACTORS], phaseNanos[INDEXING],
                    phaseNanos[DONE], catalog.size(), recipesRead, holders.size(), steps,
                    System.nanoTime() - startNanos);
        }
    }

    private static List<CreativeTabEntry> buildCreativeTabs(Minecraft mc) {
        List<CreativeTabEntry> tabs = new ArrayList<>();
        if (mc != null && mc.level != null) {
            try {
                CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), true, mc.level.registryAccess());
            } catch (Exception e) {
                LOGGER.debug("Could not rebuild the creative tab contents", e);
            }
        }
        try {
            for (Holder.Reference<CreativeModeTab> ref : BuiltInRegistries.CREATIVE_MODE_TAB.listElements().toList()) {
                CreativeModeTab tab = ref.value();
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                    continue;
                }
                Set<String> ids = new LinkedHashSet<>();
                for (ItemStack st : tab.getDisplayItems()) {
                    if (st != null && !st.isEmpty() && st.getItem() != Items.AIR) {
                        ids.add(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                    }
                }
                if (ids.isEmpty()) {
                    continue;
                }
                ItemStack icon = ItemStack.EMPTY;
                try {
                    icon = tab.getIconItem();
                } catch (Exception e) {
                    LOGGER.debug("Could not read the icon of a creative tab", e);
                }
                if (icon == null || icon.isEmpty()) {
                    icon = new ItemStack(Items.COMPASS);
                }
                String title = tab.getDisplayName().getString();
                tabs.add(new CreativeTabEntry(ref.key().identifier().toString(), title, icon, Collections.unmodifiableSet(ids)));
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read the creative tabs", e);
        }
        return Collections.unmodifiableList(tabs);
    }

    private static String classifyItemCategory(Item item, ItemStack stack, String path, Set<String> tags) {
        boolean blockItem = item instanceof BlockItem;
        boolean entityBlock = item instanceof BlockItem bi && bi.getBlock() instanceof EntityBlock;
        return ItemCategories.classify(blockItem, entityBlock, stack.getMaxDamage() > 0, path, tags);
    }

    private static RecipeEntry convertHolder(RecipeHolder<?> holder, ContextMap context) {
        // 0. Lecture dédiée (procédés en monde, démontages, recettes calculées à la volée), par les données de la recette
        RecipeEntry dedicated = WmiDedicatedRecipes.convert(holder);
        if (dedicated != null) {
            return dedicated;
        }
        String recipeId = holder.id().identifier().toString();
        Recipe<?> recipe = holder.value();
        RecipeType<?> type = recipe.getType();
        Identifier typeLoc = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        String typeId = typeLoc != null ? typeLoc.toString() : "minecraft:unknown";

        RecipeLayoutSpec layout = WmiRegistry.layout(typeId);
        String categoryName = resolveCategoryName(typeId, layout);
        List<String> workstations = layout != null ? layout.workstations() : defaultWorkstations(typeId);

        // 1. Lecture prioritaire via Recipe#display() lorsque disponible et complet (résout proprement tous les tags liés)
        try {
            List<RecipeDisplay> displays = recipe.display();
            if (displays != null && !displays.isEmpty()) {
                RecipeEntry fromDisp = convertFromDisplay(recipeId, typeId, categoryName, workstations, displays.get(0), context);
                if (fromDisp != null && !fromDisp.inputs().isEmpty() && !fromDisp.outputs().isEmpty()) {
                    return fromDisp;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read a recipe through its display", e);
        }

        // 2. Types standards de Minecraft (secours direct)
        if (recipe instanceof ShapedRecipe shaped) {
            List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
            List<Boolean> present = new ArrayList<>();
            for (Optional<Ingredient> opt : shaped.getIngredients()) {
                RecipeEntry.IngredientSlot slot = opt.isPresent() ? fromIngredient(opt.get(), 1) : null;
                if (slot != null) {
                    inputs.add(slot);
                }
                present.add(slot != null);
            }
            RecipeEntry.ItemAmount out = extractResultFromObject(shaped);
            if (out != null && !inputs.isEmpty()) {
                return new RecipeEntry(recipeId, typeId, categoryName, workstations, inputs, List.of(), List.of(out), 0, 0, 0,
                        RecipeEntry.shapedCells(shaped.getWidth(), present));
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            RecipeEntry.ItemAmount out = fromTemplate(shapeless.result());
            List<RecipeEntry.IngredientSlot> inputs = extractIngredientsFromFieldsAndMethods(shapeless, 1);
            if (out != null && !inputs.isEmpty()) {
                return new RecipeEntry(recipeId, typeId, categoryName, workstations, inputs, List.of(), List.of(out), 0, 0, 0);
            }
        } else if (recipe instanceof AbstractCookingRecipe cooking) {
            RecipeEntry.IngredientSlot in = fromIngredient(cooking.input(), 1);
            RecipeEntry.ItemAmount out = extractResultFromObject(cooking);
            if (in != null && out != null) {
                int time = cooking.cookingTime();
                return new RecipeEntry(recipeId, typeId, categoryName, workstations, List.of(in), coalCatalyst(), List.of(out), time, 0, 0);
            }
        } else if (recipe instanceof SingleItemRecipe single) {
            RecipeEntry.IngredientSlot in = fromIngredient(single.input(), 1);
            RecipeEntry.ItemAmount out = extractResultFromObject(single);
            if (in != null && out != null) {
                return new RecipeEntry(recipeId, typeId, categoryName, workstations, List.of(in), List.of(), List.of(out), 0, 0, 0);
            }
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
            smithing.templateIngredient().map(ing -> fromIngredient(ing, 1)).ifPresent(inputs::add);
            RecipeEntry.IngredientSlot base = fromIngredient(smithing.baseIngredient(), 1);
            if (base != null) {
                inputs.add(base);
            }
            smithing.additionIngredient().map(ing -> fromIngredient(ing, 1)).ifPresent(inputs::add);
            RecipeEntry.ItemAmount out = extractResultFromObject(smithing);
            if (out != null && !inputs.isEmpty()) {
                return new RecipeEntry(recipeId, typeId, categoryName, workstations, inputs, List.of(), List.of(out), 0, 0, 0);
            }
        }

        // 3. Introspection universelle des records, champs et méthodes de mods tiers
        return convertReflective(recipeId, typeId, categoryName, workstations, recipe);
    }

    private static RecipeEntry convertFromDisplay(
            String recipeId,
            String typeId,
            String categoryName,
            List<String> workstations,
            RecipeDisplay display,
            ContextMap context
    ) {
        List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
        List<RecipeEntry.IngredientSlot> catalysts = new ArrayList<>();
        SlotDisplay resultSlot = null;
        SlotDisplay stationSlot = null;
        int duration = 0;

        List<Integer> cells = List.of();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            // Forme conservée : case de la grille 3 x 3 de chaque ingrédient, cases vides comprises
            List<Boolean> present = new ArrayList<>(shaped.ingredients().size());
            for (SlotDisplay sd : shaped.ingredients()) {
                int before = inputs.size();
                addSlotDisplay(inputs, sd, context);
                present.add(inputs.size() > before);
            }
            cells = RecipeEntry.shapedCells(shaped.width(), present);
            resultSlot = shaped.result();
            stationSlot = shaped.craftingStation();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            for (SlotDisplay sd : shapeless.ingredients()) {
                addSlotDisplay(inputs, sd, context);
            }
            resultSlot = shapeless.result();
            stationSlot = shapeless.craftingStation();
        } else if (display instanceof FurnaceRecipeDisplay furnace) {
            addSlotDisplay(inputs, furnace.ingredient(), context);
            addSlotDisplay(catalysts, furnace.fuel(), context);
            if (catalysts.isEmpty()) {
                catalysts.addAll(coalCatalyst());
            }
            resultSlot = furnace.result();
            stationSlot = furnace.craftingStation();
            duration = furnace.duration();
        } else if (display instanceof StonecutterRecipeDisplay cutter) {
            addSlotDisplay(inputs, cutter.input(), context);
            resultSlot = cutter.result();
            stationSlot = cutter.craftingStation();
        } else if (display instanceof SmithingRecipeDisplay smithing) {
            addSlotDisplay(inputs, smithing.template(), context);
            addSlotDisplay(inputs, smithing.base(), context);
            addSlotDisplay(inputs, smithing.addition(), context);
            resultSlot = smithing.result();
            stationSlot = smithing.craftingStation();
        }

        if (resultSlot == null) {
            return null;
        }
        List<ItemStack> outStacks = resultSlot.resolveForStacks(context);
        RecipeEntry.ItemAmount out;
        if (outStacks.isEmpty()) {
            RecipeEntry.IngredientSlot fluidOut = fluidSlot(resultSlot, context);
            if (fluidOut == null) {
                return null;
            }
            out = new RecipeEntry.ItemAmount(fluidOut.alternatives().get(0), fluidOut.count());
        } else {
            out = fromResultStack(outStacks.get(0));
        }
        if (out == null) {
            return null;
        }

        List<String> ws = new ArrayList<>(workstations);
        if (stationSlot != null) {
            for (ItemStack st : stationSlot.resolveForStacks(context)) {
                String stId = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                if (!ws.contains(stId)) {
                    ws.add(stId);
                }
            }
        }
        return new RecipeEntry(recipeId, typeId, categoryName, ws, inputs, catalysts, List.of(out), duration, 0, 0, cells);
    }

    private static void addSlotDisplay(List<RecipeEntry.IngredientSlot> target, SlotDisplay sd, ContextMap context) {
        if (sd == null || sd instanceof SlotDisplay.Empty) {
            return;
        }
        List<ItemStack> stacks = sd.resolveForStacks(context);
        if (stacks.isEmpty()) {
            RecipeEntry.IngredientSlot fluid = fluidSlot(sd, context);
            if (fluid != null) {
                target.add(fluid);
            }
            return;
        }
        List<String> alts = new ArrayList<>();
        int count = 1;
        for (ItemStack st : stacks) {
            if (!st.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                if (!alts.contains(id)) {
                    alts.add(id);
                }
                count = Math.max(count, st.getCount());
            }
        }
        if (!alts.isEmpty()) {
            target.add(new RecipeEntry.IngredientSlot(alts, count));
        }
    }

    /** Lecteur des cases de fluide de NeoForge ({@code FluidSlotDisplay} et dérivés). */
    private static final net.neoforged.neoforge.fluids.crafting.display.ForFluidStacks<net.neoforged.neoforge.fluids.FluidStack>
            FLUID_STACKS = stack -> stack;

    /** Fluides d'une case d'affichage, ou {@code null} si elle n'en contient pas. */
    private static RecipeEntry.@Nullable IngredientSlot fluidSlot(SlotDisplay sd, ContextMap context) {
        List<String> alts = new ArrayList<>();
        int amount = 0;
        try {
            for (net.neoforged.neoforge.fluids.FluidStack fs : sd.resolve(context, FLUID_STACKS).toList()) {
                if (fs != null && !fs.isEmpty()) {
                    String id = BuiltInRegistries.FLUID.getKey(fs.getFluid()).toString();
                    if (!alts.contains(id)) {
                        alts.add(id);
                    }
                    amount = Math.max(amount, fs.getAmount());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read the fluids of a slot display", e);
        }
        return alts.isEmpty() ? null : RecipeEntry.IngredientSlot.fluids(alts, Math.max(1, amount), "");
    }

    private static RecipeEntry convertReflective(
            String recipeId,
            String typeId,
            String categoryName,
            List<String> workstations,
            Recipe<?> recipe
    ) {
        int count = invokeInt(recipe, "count", 1);
        int duration = invokeInt(recipe, "cookingTime", invokeInt(recipe, "time", invokeInt(recipe, "duration", 0)));
        int energyPerTick = invokeInt(recipe, "energyPerTick", 0);

        long totalEnergy = (long) duration * energyPerTick;

        List<RecipeEntry.IngredientSlot> inputs = extractIngredientsFromFieldsAndMethods(recipe, count);
        List<RecipeEntry.IngredientSlot> catalysts = new ArrayList<>();
        List<RecipeEntry.ItemAmount> outputs = new ArrayList<>();

        RecipeEntry.ItemAmount out = extractResultFromObject(recipe);
        if (out != null) {
            outputs.add(out);
        }


        if (outputs.isEmpty()) {
            return null;
        }
        return new RecipeEntry(recipeId, typeId, categoryName, workstations, inputs, catalysts, outputs, duration, energyPerTick, totalEnergy);
    }

    private static List<RecipeEntry.IngredientSlot> extractIngredientsFromFieldsAndMethods(
            Object recipe,
            int defaultCount
    ) {
        List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
        for (Class<?> c = recipe.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                Class<?> type = f.getType();
                try {
                    if (type == Ingredient.class) {
                        Ingredient ing = (Ingredient) f.get(recipe);
                        int qty = defaultCount;
                        RecipeEntry.IngredientSlot slot = fromIngredient(ing, qty);
                        if (slot != null) {
                            inputs.add(slot);
                        }
                    } else if (type == Optional.class) {
                        Optional<?> opt = (Optional<?>) f.get(recipe);
                        if (opt != null && opt.isPresent() && opt.get() instanceof Ingredient ing) {
                            int qty = 1;
                            RecipeEntry.IngredientSlot slot = fromIngredient(ing, qty);
                            if (slot != null) {
                                inputs.add(slot);
                            }
                        }
                    } else if (List.class.isAssignableFrom(type)) {
                        List<?> list = (List<?>) f.get(recipe);
                        if (list != null) {
                            for (Object elem : list) {
                                if (elem instanceof Ingredient ing) {
                                    RecipeEntry.IngredientSlot slot = fromIngredient(ing, 1);
                                    if (slot != null) {
                                        inputs.add(slot);
                                    }
                                } else if (elem instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof Ingredient ing) {
                                    RecipeEntry.IngredientSlot slot = fromIngredient(ing, 1);
                                    if (slot != null) {
                                        inputs.add(slot);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not read the ingredients of a recipe by reflection", e);
                }
            }
        }
        return inputs;
    }

    private static RecipeEntry.ItemAmount extractResultFromObject(Recipe<?> recipe) {
        try {
            ItemStack assembled = recipe.assemble(null);
            RecipeEntry.ItemAmount amt = fromResultStack(assembled);
            if (amt != null) {
                return amt;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not assemble the result of a recipe", e);
        }
        for (Class<?> c = recipe.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object val = f.get(recipe);
                    if (val instanceof ItemStackTemplate tpl) {
                        RecipeEntry.ItemAmount amt = fromTemplate(tpl);
                        if (amt != null) {
                            return amt;
                        }
                    } else if (val instanceof ItemStack st) {
                        RecipeEntry.ItemAmount amt = fromResultStack(st);
                        if (amt != null) {
                            return amt;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not read a recipe result field by reflection", e);
                }
            }
        }
        return null;
    }

    private static int invokeInt(Object target, String methodName, int fallback) {
        try {
            Method m = target.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            Object val = m.invoke(target);
            if (val instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read a number from a recipe by reflection", e);
        }
        return fallback;
    }

    private static RecipeEntry.IngredientSlot fromIngredient(Ingredient ing, int count) {
        if (ing == null) {
            return null;
        }
        List<String> alts = new ArrayList<>();
        try {
            if (ing.isEmpty()) {
                return null;
            }
            ing.items().forEach(holder -> {
                Item it = holder.value();
                if (it != Items.AIR) {
                    String id = BuiltInRegistries.ITEM.getKey(it).toString();
                    if (!alts.contains(id)) {
                        alts.add(id);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.debug("Could not read the items of an ingredient", e);
            return null;
        }
        if (alts.isEmpty()) {
            return null;
        }
        String tagId = "";
        try {
            var keyOpt = ing.getValues().unwrapKey();
            if (keyOpt.isPresent()) {
                tagId = keyOpt.get().location().toString();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read the tag of an ingredient", e);
        }
        return new RecipeEntry.IngredientSlot(alts, Math.max(1, count), tagId);
    }

    private static RecipeEntry.ItemAmount fromTemplate(ItemStackTemplate tpl) {
        if (tpl == null) {
            return null;
        }
        return fromResultStack(tpl.create());
    }

    private static RecipeEntry.ItemAmount fromResultStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() == Items.AIR) {
            return null;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return new RecipeEntry.ItemAmount(id, Math.max(1, stack.getCount()));
    }

    private static List<RecipeEntry.IngredientSlot> coalCatalyst() {
        return List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:coal", "minecraft:charcoal"), 1, "minecraft:coals"));
    }

    private static String resolveCategoryName(String typeId, RecipeLayoutSpec layout) {
        if (layout != null) {
            return Component.translatable(layout.titleKey()).getString();
        }
        int colon = typeId.indexOf(':');
        String path = colon >= 0 ? typeId.substring(colon + 1) : typeId;
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }

    private static List<String> defaultWorkstations(String typeId) {
        int colon = typeId.indexOf(':');
        if (colon > 0) {
            String candidate = typeId;
            if (BuiltInRegistries.ITEM.containsKey(Identifier.parse(candidate))) {
                return List.of(candidate);
            }
        }
        return Collections.emptyList();
    }
}
