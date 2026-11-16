package waymoreitems.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Choix d'une autre recette pour une étape de l'arbre, cache des plans et reprise par les Notes. */
final class CraftTreeChoiceTests {
    private CraftTreeChoiceTests() {
    }

    private static CatalogItem item(String id, String name) {
        return new CatalogItem(id, id.substring(0, id.indexOf(':')), "Test", name, "01", 64, Set.of(), "");
    }

    private static RecipeEntry recipe(String id, String type, String category, String input, int inCount, String output) {
        return new RecipeEntry(id, type, category, List.of(),
                List.of(new RecipeEntry.IngredientSlot(List.of(input), inCount)), List.of(),
                List.of(new RecipeEntry.ItemAmount(output, 1)), 0, 0, 0);
    }

    /** Lingot : four (fer brut, choix automatique), haut fourneau (fer brut), pépites ; gadget = 2 lingots. */
    private static RecipeIndex sample() {
        List<CatalogItem> items = List.of(item("t:raw", "Brut"), item("t:nugget", "Pépite"), item("t:ingot", "Lingot"),
                item("t:gadget", "Gadget"));
        List<RecipeEntry> recipes = List.of(
                recipe("t:ingot_smelt", "minecraft:smelting", "Four", "t:raw", 1, "t:ingot"),
                recipe("t:ingot_blast", "minecraft:blasting", "Haut fourneau", "t:raw", 1, "t:ingot"),
                recipe("t:ingot_nuggets", "minecraft:crafting_shaped", "Fabrication", "t:nugget", 9, "t:ingot"),
                recipe("t:gadget", "minecraft:crafting_shaped", "Fabrication", "t:ingot", 2, "t:gadget"));
        return new RecipeIndex(items, recipes);
    }

    private static String ingotRecipe(CraftTreeCalculator.CraftPlan plan) {
        RecipeEntry r = CraftPlanCache.recipeUsedFor(plan.root(), "t:ingot");
        return r == null ? null : r.id();
    }

    /**
     * Pierre taillée et pierre (Minecraft 26.3) : pierre cuite à partir de pierre taillée, pierre taillée retaillée
     * à partir de pierre au tailleur ou par une machine (entropie d'AE2) ; four = 8 pierres taillées ; briques de
     * pierre retaillées à partir de pierre.
     */
    private static RecipeIndex stoneSample() {
        List<CatalogItem> items = List.of(item("t:cobble", "Pierre taillée"), item("t:stone", "Pierre"),
                item("t:furnace", "Four"), item("t:bricks", "Briques de pierre"));
        List<RecipeEntry> recipes = List.of(
                recipe("t:stone_smelt", "minecraft:smelting", "Four", "t:cobble", 1, "t:stone"),
                recipe("t:cobble_from_stone", "minecraft:stonecutting", "Tailleur", "t:stone", 1, "t:cobble"),
                recipe("t:cobble_entropy", "ae2:entropy", "Manipulation d'entropie", "t:stone", 1, "t:cobble"),
                recipe("t:furnace", "minecraft:crafting_shaped", "Fabrication", "t:cobble", 8, "t:furnace"),
                recipe("t:bricks_from_stone", "minecraft:stonecutting", "Tailleur", "t:stone", 1, "t:bricks"));
        return new RecipeIndex(items, recipes);
    }

    private static void testStonecutterRoundTrip(BiConsumer<String, Boolean> check) {
        RecipeIndex idx = stoneSample();
        CraftPlanCache cache = new CraftPlanCache(8);
        CraftTreeCalculator.CraftPlan furnace = cache.plan(idx, "t:furnace", 1, Map.of(), Map.of());
        check.accept("arbre : la pierre taillée se récolte, sans retaille de la pierre",
                CraftPlanCache.recipeUsedFor(furnace.root(), "t:cobble") == null
                        && furnace.missingRaw().getOrDefault("t:cobble", 0) == 8
                        && !furnace.missingRaw().containsKey("t:stone"));
        CraftTreeCalculator.CraftPlan stone = cache.plan(idx, "t:stone", 1, Map.of(), Map.of());
        check.accept("arbre : la pierre se cuit à partir de pierre taillée",
                stone.missingRaw().getOrDefault("t:cobble", 0) == 1 && !stone.missingRaw().containsKey("t:stone"));
        CraftTreeCalculator.CraftPlan bricks = cache.plan(idx, "t:bricks", 1, Map.of(), Map.of());
        check.accept("arbre : une vraie retaille (briques) reste au tailleur",
                bricks.missingRaw().getOrDefault("t:cobble", 0) == 1);
        CraftTreeCalculator.CraftPlan stocked = cache.plan(idx, "t:furnace", 1, Map.of("t:stone", 8), Map.of());
        RecipeEntry used = CraftPlanCache.recipeUsedFor(stocked.root(), "t:cobble");
        check.accept("arbre : pierre en stock, la retaille en pierre taillée reste proposée",
                used != null && ("t:cobble_from_stone".equals(used.id()) || "t:cobble_entropy".equals(used.id())));
    }

    static void run(BiConsumer<String, Boolean> check) {
        testStonecutterRoundTrip(check);
        RecipeIndex idx = sample();
        CraftPlanCache cache = new CraftPlanCache(8);

        // Cache : même clé, même plan, un seul calcul ; inventaire équivalent (quantité nulle) = même clé
        CraftTreeCalculator.CraftPlan a = cache.plan(idx, "t:gadget", 1, Map.of(), Map.of());
        CraftTreeCalculator.CraftPlan b = cache.plan(idx, "t:gadget", 1, Map.of("t:raw", 0), Map.of());
        check.accept("cache de l'arbre : même clé, plan réutilisé sans recalcul", a == b && cache.computedCount() == 1);
        check.accept("cache de l'arbre : choix automatique = four", "t:ingot_smelt".equals(ingotRecipe(a)));

        // Invalidation : quantité, inventaire, choix de recette, nouvel index
        cache.plan(idx, "t:gadget", 2, Map.of(), Map.of());
        cache.plan(idx, "t:gadget", 1, Map.of("t:raw", 1), Map.of());
        CraftTreeCalculator.CraftPlan chosen = cache.plan(idx, "t:gadget", 1, Map.of(), Map.of("t:ingot", "t:ingot_nuggets"));
        check.accept("cache de l'arbre : quantité, inventaire et choix donnent chacun un nouveau calcul", cache.computedCount() == 4);
        check.accept("arbre : la recette choisie est employée (pépites)",
                "t:ingot_nuggets".equals(ingotRecipe(chosen)) && chosen.missingRaw().getOrDefault("t:nugget", 0) == 18);
        RecipeIndex other = sample();
        CraftTreeCalculator.CraftPlan fresh = cache.plan(other, "t:gadget", 1, Map.of(), Map.of());
        check.accept("cache de l'arbre : un nouvel index vide le cache", fresh != a && cache.size() == 1);
        CraftPlanCache small = new CraftPlanCache(2);
        small.plan(idx, "t:gadget", 1, Map.of(), Map.of());
        small.plan(idx, "t:gadget", 2, Map.of(), Map.of());
        small.plan(idx, "t:gadget", 3, Map.of(), Map.of());
        check.accept("cache de l'arbre : capacité bornée", small.size() == 2);

        // Défilement des recettes d'une étape : suivante, précédente, retour au début
        String next = cache.cycleRecipe(idx, "t:gadget", 1, Map.of(), Map.of(), "t:ingot", 1);
        String prev = cache.cycleRecipe(idx, "t:gadget", 1, Map.of(), Map.of(), "t:ingot", -1);
        String wrap = cache.cycleRecipe(idx, "t:gadget", 1, Map.of(), Map.of("t:ingot", "t:ingot_nuggets"), "t:ingot", 1);
        check.accept("choix de recette : flèche droite = recette suivante (haut fourneau)", "t:ingot_blast".equals(next));
        check.accept("choix de recette : flèche gauche = recette précédente (pépites)", "t:ingot_nuggets".equals(prev));
        check.accept("choix de recette : après la dernière, retour à la première", "t:ingot_smelt".equals(wrap));
        check.accept("choix de recette : aucune autre recette pour une étape unique",
                cache.cycleRecipe(idx, "t:gadget", 1, Map.of(), Map.of(), "t:gadget", 1) == null);

        // Préférences : enregistrement, relecture, retour au choix automatique
        RecipePreferences prefs = new RecipePreferences();
        boolean changed = prefs.set("t:ingot", "t:ingot_blast");
        boolean same = prefs.set("t:ingot", "t:ingot_blast");
        RecipePreferences reread = new RecipePreferences();
        reread.load(prefs.serialize() + ";illisible");
        check.accept("préférences de l'arbre : relues à l'identique, paire illisible ignorée",
                changed && !same && reread.asMap().equals(Map.of("t:ingot", "t:ingot_blast")));
        Map<String, String> before = prefs.asMap();
        prefs.set("t:ingot", null);
        check.accept("préférences de l'arbre : retour au choix automatique, vue précédente inchangée",
                prefs.get("t:ingot") == null && prefs.isEmpty() && before.size() == 1);

        // Notes : un objectif épinglé suit le choix, enregistré avec lui, puis revient au choix automatique
        PinnedChecklist notes = new PinnedChecklist();
        notes.useIndex(idx);
        notes.pinPlan("t:gadget", 1, cache.plan(idx, "t:gadget", 1, Map.of(), Map.of()));
        boolean hadRaw = treeHas(notes, "t:raw");
        notes.chooseRecipe("t:ingot", "t:ingot_nuggets");
        boolean followsChoice = treeHas(notes, "t:nugget") && !treeHas(notes, "t:raw")
                && notes.serializeGoals().contains("t:ingot=t:ingot_nuggets");
        check.accept("Notes : l'arbre épinglé suit le choix de recette de la fenêtre", hadRaw && followsChoice);
        PinnedChecklist later = new PinnedChecklist();
        later.recipeChoices().load(notes.recipeChoices().serialize());
        later.useIndex(idx);
        later.pinCraft("t:gadget", 1, Map.of(), null);
        check.accept("Notes : un nouvel objectif reprend les choix mémorisés", treeHas(later, "t:nugget"));
        notes.chooseRecipe("t:ingot", null);
        check.accept("Notes : retour au choix automatique (four, fer brut)",
                treeHas(notes, "t:raw") && !treeHas(notes, "t:nugget") && notes.recipeChoices().isEmpty());
    }

    private static boolean treeHas(PinnedChecklist notes, String itemId) {
        for (int i = 0; i < notes.goalCount(); i++) {
            PinnedChecklist.TreeNode tree = notes.goalTree(i);
            if (tree != null && contains(tree, itemId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(PinnedChecklist.TreeNode node, String itemId) {
        if (node.itemId().equals(itemId)) {
            return true;
        }
        for (PinnedChecklist.TreeNode child : node.children()) {
            if (contains(child, itemId)) {
                return true;
            }
        }
        return false;
    }
}
