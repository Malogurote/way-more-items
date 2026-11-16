package waymoreitems.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Tests des ingrédients non objets (fluides) du noyau. */
final class FluidTests {
    private FluidTests() {
    }

    static void run(BiConsumer<String, Boolean> check) {
        testKeys(check);
        testRecipeFactories(check);
        testIndexAndSearch(check);
        testCraftTree(check);
    }

    private static void testKeys(BiConsumer<String, Boolean> check) {
        String water = IngredientRef.fluidKey("minecraft:water");
        check.accept("cle de fluide formee", "fluid@minecraft:water".equals(water));
        check.accept("cle de fluide reconnue", IngredientRef.isFluid(water) && !IngredientRef.isItem(water));
        check.accept("objet nu reconnu", IngredientRef.isItem("minecraft:bucket") && !IngredientRef.isFluid("minecraft:bucket"));
        IngredientRef ref = IngredientRef.parse(water);
        check.accept("lecture d'une cle de fluide", ref.isFluid() && "minecraft:water".equals(ref.id()) && water.equals(ref.key()));
        IngredientRef item = IngredientRef.parse("minecraft:stone");
        check.accept("lecture d'une cle d'objet", item.isItem() && "minecraft:stone".equals(item.key()));
        check.accept("identifiant sans genre", "minecraft:water".equals(IngredientRef.idOf(water)));
        check.accept("quantite de fluide en mB", "250 mB".equals(IngredientRef.formatAmount(water, 250)));
        check.accept("quantite de fluide en seaux", "2 B".equals(IngredientRef.formatAmount(water, 2000)));
        check.accept("quantite non ronde en mB", "1500 mB".equals(IngredientRef.formatAmount(water, 1500)));
        check.accept("quantite d'objet sans unite", "12".equals(IngredientRef.formatAmount("minecraft:stone", 12)));
        IngredientRef.registerKind(new IngredientRef.Kind("gas", "mB", "", 0));
        check.accept("genre tiers declare", "gas".equals(IngredientRef.kindOf("gas@exemplemod:hydrogen"))
                && "5 mB".equals(IngredientRef.formatAmount("gas@exemplemod:hydrogen", 5)));
        boolean refused = false;
        try {
            new IngredientRef("a@b", "x");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.accept("genre contenant @ refuse", refused);
    }

    private static void testRecipeFactories(BiConsumer<String, Boolean> check) {
        RecipeEntry.IngredientSlot slot = RecipeEntry.IngredientSlot.fluids(List.of("minecraft:water"), 250, "c:water");
        check.accept("case de fluide", slot.isFluid() && slot.matches("fluid@minecraft:water") && slot.count() == 250
                && "c:water".equals(slot.tagId()));
        RecipeEntry.IngredientSlot already = RecipeEntry.IngredientSlot.fluids(List.of("fluid@minecraft:lava"), 1000, "");
        check.accept("cle deja formee non doublee", already.matches("fluid@minecraft:lava"));
        RecipeEntry.ItemAmount out = RecipeEntry.ItemAmount.fluid("minecraft:lava", 1000);
        check.accept("resultat fluide", out.isFluid() && "fluid@minecraft:lava".equals(out.itemId()));
        check.accept("resultat objet non fluide", !new RecipeEntry.ItemAmount("minecraft:stone", 1).isFluid());
    }

    private static List<CatalogItem> items() {
        return List.of(
                new CatalogItem("minecraft:bucket", "minecraft", "Minecraft", "Seau", "tools", 16, Set.of(), ""),
                new CatalogItem("minecraft:water_bucket", "minecraft", "Minecraft", "Seau d'eau", "tools", 1, Set.of(), ""),
                new CatalogItem("minecraft:clay_ball", "minecraft", "Minecraft", "Argile", "misc", 64, Set.of(), ""),
                new CatalogItem("minecraft:dirt", "minecraft", "Minecraft", "Terre", "blocks", 64, Set.of(), ""),
                new CatalogItem("minecraft:mud", "minecraft", "Minecraft", "Boue", "blocks", 64, Set.of(), ""),
                new CatalogItem(IngredientRef.fluidKey("minecraft:water"), "minecraft", "Minecraft", "Eau",
                        CatalogItem.FLUID_CATEGORY, 1, Set.of("c:water"), ""),
                new CatalogItem(IngredientRef.fluidKey("exemplemod:slurry"), "exemplemod", "Exemple Mod", "Boue liquide",
                        CatalogItem.FLUID_CATEGORY, 1, Set.of(), "")
        );
    }

    private static List<RecipeEntry> recipes() {
        String water = IngredientRef.fluidKey("minecraft:water");
        return List.of(
                new RecipeEntry("wmi:fill/water_bucket", RecipeEntry.FLUID_FILLING_TYPE, "Remplissage", List.of(),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:bucket"), 1),
                                RecipeEntry.IngredientSlot.fluids(List.of("minecraft:water"), 1000, "")),
                        List.of(), List.of(new RecipeEntry.ItemAmount("minecraft:water_bucket", 1)), 0, 0, 0),
                new RecipeEntry("wmi:empty/water_bucket", RecipeEntry.FLUID_EMPTYING_TYPE, "Vidange", List.of(),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:water_bucket"), 1)),
                        List.of(), List.of(RecipeEntry.ItemAmount.fluid("minecraft:water", 1000),
                        new RecipeEntry.ItemAmount("minecraft:bucket", 1)), 0, 0, 0),
                // Boue : 1 terre + 250 mB d'eau
                new RecipeEntry("exemplemod:mud", "exemplemod:mixer", "Mélangeur", List.of(),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:dirt"), 1),
                                RecipeEntry.IngredientSlot.fluids(List.of("minecraft:water"), 250, "")),
                        List.of(), List.of(new RecipeEntry.ItemAmount("minecraft:mud", 1)), 20, 0, 0),
                // Boue liquide : 1 argile + 500 mB d'eau -> 400 mB de boue liquide
                new RecipeEntry("exemplemod:slurry", "exemplemod:mixer", "Mélangeur", List.of(),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:clay_ball"), 1),
                                RecipeEntry.IngredientSlot.fluids(List.of(water), 500, "")),
                        List.of(), List.of(RecipeEntry.ItemAmount.fluid("exemplemod:slurry", 400)), 40, 0, 0)
        );
    }

    private static void testIndexAndSearch(BiConsumer<String, Boolean> check) {
        RecipeIndex index = new RecipeIndex(items(), recipes());
        String water = IngredientRef.fluidKey("minecraft:water");
        check.accept("usages de l'eau", index.recipesUsing(water).size() == 3);
        check.accept("origines de l'eau (vidange)", index.recipesForOutput(water).size() == 1);
        List<CatalogItem> fluids = index.search("!fluid");
        check.accept("mot-cle !fluid : fluides seuls", fluids.size() == 2 && fluids.stream().allMatch(CatalogItem::isFluid));
        List<CatalogItem> eau = index.search("!FLUID eau");
        check.accept("mot-cle !fluid combine au nom", eau.size() == 1 && water.equals(eau.get(0).id()));
        check.accept("recherche ordinaire trouve un fluide", index.search("boue liquide").stream().anyMatch(CatalogItem::isFluid));
        check.accept("fiche fluide reconnue", index.item(water).isFluid() && !index.item("minecraft:bucket").isFluid());
        Map<String, Integer> stock = index.fluidsInContainers(Map.of("minecraft:water_bucket", 3, "minecraft:dirt", 5));
        check.accept("fluide des seaux pleins", stock.size() == 1 && stock.get(water) == 3000);
        check.accept("aucun fluide sans seau plein", index.fluidsInContainers(Map.of("minecraft:bucket", 4)).isEmpty());
    }

    private static void testCraftTree(BiConsumer<String, Boolean> check) {
        RecipeIndex index = new RecipeIndex(items(), recipes());
        String water = IngredientRef.fluidKey("minecraft:water");
        String slurry = IngredientRef.fluidKey("exemplemod:slurry");

        CraftTreeCalculator.CraftPlan mud = CraftTreeCalculator.plan(index, "minecraft:mud", 4, Map.of(), Map.of());
        check.accept("eau comptee en ressource de base", mud.missingRaw().getOrDefault(water, 0) == 1000);
        check.accept("seau non propose comme etape", !mud.missingRaw().containsKey("minecraft:water_bucket")
                && !mud.machineBatches().containsKey(RecipeEntry.FLUID_EMPTYING_TYPE));

        CraftTreeCalculator.CraftPlan withBuckets = CraftTreeCalculator.plan(index, "minecraft:mud", 4,
                Map.of("minecraft:water_bucket", 1), Map.of());
        check.accept("seau plein compte comme stock de fluide", !withBuckets.missingRaw().containsKey(water)
                && withBuckets.usedFromStock().getOrDefault(water, 0) == 1000);

        CraftTreeCalculator.CraftPlan fluidStep = CraftTreeCalculator.plan(index, slurry, 1000, Map.of(), Map.of());
        check.accept("fluide produit par une machine : etape", fluidStep.root().recipe() != null
                && fluidStep.root().batches() == 3 && fluidStep.root().crafted() == 1200);
        check.accept("fluide produit : eau en mB", fluidStep.missingRaw().getOrDefault(water, 0) == 1500
                && fluidStep.missingRaw().getOrDefault("minecraft:clay_ball", 0) == 3);

        CraftTreeCalculator.CraftPlan raw = CraftTreeCalculator.plan(index, water, 2000, Map.of(), Map.of());
        check.accept("fluide sans machine : ressource de base", raw.root().recipe() == null
                && raw.missingRaw().getOrDefault(water, 0) == 2000);

        CraftTreeCalculator.CraftPlan bucket = CraftTreeCalculator.plan(index, "minecraft:water_bucket", 1, Map.of(), Map.of());
        check.accept("seau plein : pas de remplissage dans l'arbre", bucket.root().recipe() == null);
    }
}
