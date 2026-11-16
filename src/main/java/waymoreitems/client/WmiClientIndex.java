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
import waymoreitems.core.RecipeEntry;
import waymoreitems.core.RecipeIndex;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
    private static volatile RecipeMap pendingRecipeMap;
    private static volatile boolean needsRebuild = true;
    private static volatile boolean builtWithBoundTags = false;
    /** Table de traduction de Minecraft en vigueur lors de la dernière construction de l'index. */
    private static volatile Language builtWithLanguage;
    private static int ticksWaitingForTags = 0;
    private static final Map<String, ItemStack> STACK_BY_ID = new LinkedHashMap<>();

    private WmiClientIndex() {
    }

    public static List<CreativeTabEntry> creativeTabs() {
        if (currentCreativeTabs.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                rebuildNow();
            }
        }
        return currentCreativeTabs;
    }

    public static RecipeIndex get() {
        if (currentIndex.allItems().isEmpty()) {
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
        needsRebuild = true;
        builtWithBoundTags = false;
        builtWithLanguage = null;
        ticksWaitingForTags = 0;
        STACK_BY_ID.clear();
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
        if (!needsRebuild && !currentIndex.allItems().isEmpty()) {
            return;
        }
        if (tagsBound || ++ticksWaitingForTags >= 15) {
            rebuildNow();
        }
    }

    public static ItemStack stackFor(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack cached = STACK_BY_ID.get(itemId);
        if (cached != null) {
            return cached;
        }
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (item != null && item != Items.AIR) {
                ItemStack s = new ItemStack(item);
                STACK_BY_ID.put(itemId, s);
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

    public static synchronized void rebuildNow() {
        Minecraft mc = Minecraft.getInstance();
        builtWithLanguage = Language.getInstance();
        ContextMap context = (mc != null && mc.level != null) ? SlotDisplayContext.fromLevel(mc.level) : ContextMap.EMPTY;
        boolean tagsBound = BuiltInRegistries.ITEM.getTags().findAny().isPresent();

        List<CatalogItem> catalog = buildCatalog(mc);
        List<RecipeEntry> entries = new ArrayList<>();

        RecipeMap map = pendingRecipeMap;
        // Mesure de couverture par mod : recettes reçues, recettes lues, types de recettes
        Map<String, int[]> coverage = new TreeMap<>();
        Map<String, Set<String>> typesByMod = new TreeMap<>();
        Map<String, Map<String, Integer>> unreadTypes = new TreeMap<>();
        if (map != null) {
            for (RecipeHolder<?> holder : map.values()) {
                String ns = holder.id().identifier().getNamespace();
                int[] c = coverage.computeIfAbsent(ns, k -> new int[2]);
                c[0]++;
                int readBefore = c[1];
                try {
                    RecipeEntry entry = convertHolder(holder, context);
                    if (entry != null && !entry.outputs().isEmpty()) {
                        entries.add(entry);
                        c[1]++;
                        typesByMod.computeIfAbsent(ns, k -> new TreeSet<>()).add(entry.typeId());
                    }
                } catch (Exception e) {
                    LOGGER.debug("A recipe could not be converted and was skipped", e);
                }
                if (c[1] == readBefore) {
                    String type;
                    try {
                        type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
                    } catch (Exception e) {
                        LOGGER.debug("Could not read the type of a recipe", e);
                        type = "?";
                    }
                    unreadTypes.computeIfAbsent(ns, k -> new TreeMap<>()).merge(type, 1, Integer::sum);
                }
            }
        }
        if (!unreadTypes.isEmpty()) {
            LOGGER.info("WMI : recettes non lues par mod et par type : " + unreadTypes);
        }
        if (!coverage.isEmpty()) {
            StringBuilder report = new StringBuilder("WMI : recettes lues par mod (lues/reçues, types) :");
            coverage.forEach((ns, c) -> report.append(' ').append(ns).append(' ').append(c[1]).append('/').append(c[0])
                    .append(" (").append(typesByMod.getOrDefault(ns, Set.of()).size()).append(") ;"));
            LOGGER.info(report.toString());
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

        currentIndex = new RecipeIndex(catalog, entries);
        currentCreativeTabs = buildCreativeTabs(mc);
        builtWithBoundTags = tagsBound;
        needsRebuild = false;
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

    /** Vrai pendant la lecture des infobulles par l'index : les ajouts de WMI aux infobulles s'abstiennent. */
    static volatile boolean collectingTooltips = false;

    private static List<CatalogItem> buildCatalog(Minecraft mc) {
        collectingTooltips = true;
        try {
            return buildCatalogLines(mc);
        } finally {
            collectingTooltips = false;
        }
    }

    private static List<CatalogItem> buildCatalogLines(Minecraft mc) {
        STACK_BY_ID.clear();
        Map<String, String> modNames = new LinkedHashMap<>();
        modNames.put("minecraft", "Minecraft");
        WmiPlatform.forEachMod(modNames::put);

        List<CatalogItem> list = new ArrayList<>();
        Item.TooltipContext tooltipCtx = (mc != null && mc.level != null) ? Item.TooltipContext.of(mc.level) : Item.TooltipContext.EMPTY;

        for (Holder.Reference<Item> ref : BuiltInRegistries.ITEM.listElements().toList()) {
            Item item = ref.value();
            if (item == Items.AIR) {
                continue;
            }
            Identifier key = ref.key().identifier();
            String id = key.toString();
            String ns = key.getNamespace();
            String modName = modNames.getOrDefault(ns, ns);
            ItemStack stack = new ItemStack(item);
            STACK_BY_ID.put(id, stack);

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

            list.add(new CatalogItem(id, ns, modName, displayName, category, Math.max(1, stack.getMaxStackSize()), tags, tipBuilder.toString()));
        }
        return list;
    }

    private static String classifyItemCategory(Item item, ItemStack stack, String path, Set<String> tags) {
        String p = path.toLowerCase(Locale.ROOT);
        if (item instanceof BlockItem bi) {
            if (bi.getBlock() instanceof EntityBlock
                    || p.contains("machine") || p.contains("furnace")
                    || p.contains("generator")
                    || p.contains("boiler") || p.contains("turbine") || p.contains("solar")
                    || p.contains("wind") || p.contains("pump") || p.contains("pipe")
                    || p.contains("cable") || p.contains("conveyor") || p.contains("crafter")
                    || p.contains("anvil") || p.contains("table") || p.contains("stonecutter")
                    || p.contains("grindstone") || p.contains("hopper") || p.contains("piston")
                    || p.contains("observer") || p.contains("dispenser") || p.contains("dropper")
                    || p.contains("redstone") || p.contains("repeater") || p.contains("comparator")
                    || p.contains("rail") || p.contains("lever") || p.contains("button")) {
                return "01_machines";
            }
            return "02_blocks";
        }
        for (String t : tags) {
            if (t.contains("tools") || t.contains("weapons") || t.contains("armors")
                    || t.contains("enchantable") || t.contains("swords") || t.contains("pickaxes")
                    || t.contains("axes") || t.contains("shovels") || t.contains("hoes")) {
                return "03_tools";
            }
        }
        if (stack.getMaxDamage() > 0 || p.contains("wrench") || p.contains("hammer")
                || p.contains("sword") || p.contains("pickaxe") || p.contains("axe")
                || p.contains("shovel") || p.contains("hoe") || p.contains("helmet")
                || p.contains("chestplate") || p.contains("leggings") || p.contains("boots")
                || p.contains("bow") || p.contains("shield") || p.contains("shears")) {
            return "03_tools";
        }
        return "04_materials";
    }

    private static RecipeEntry convertHolder(RecipeHolder<?> holder, ContextMap context) {
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
        if (outStacks.isEmpty()) {
            return null;
        }
        RecipeEntry.ItemAmount out = fromResultStack(outStacks.get(0));
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
