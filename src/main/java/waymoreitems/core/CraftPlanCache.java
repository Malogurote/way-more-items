package waymoreitems.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans de fabrication déjà calculés, par (objet, quantité, inventaire, choix de recettes), en Java pur. Un plan est
 * immuable : il est rendu tel quel tant que la clé ne change pas. Tout changement d'inventaire ou de choix donne une
 * autre clé ; un nouvel index des recettes vide le cache. Les plus anciens plans sont oubliés au-delà de la capacité.
 */
public final class CraftPlanCache {
    private record Key(String itemId, int amount, Map<String, Integer> inventory, Map<String, String> preferred) {
    }

    private final int capacity;
    private final LinkedHashMap<Key, CraftTreeCalculator.CraftPlan> plans;
    private RecipeIndex index;
    private int computed;

    public CraftPlanCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity < 1: " + capacity);
        }
        this.capacity = capacity;
        this.plans = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, CraftTreeCalculator.CraftPlan> eldest) {
                return size() > CraftPlanCache.this.capacity;
            }
        };
    }

    /** Plan de {@link CraftTreeCalculator#plan}, calculé seulement s'il n'est pas déjà connu pour cette clé. */
    public CraftTreeCalculator.CraftPlan plan(RecipeIndex recipes, String itemId, int amount,
                                              Map<String, Integer> inventory, Map<String, String> preferred) {
        Objects.requireNonNull(recipes, "recipes");
        if (recipes != index) {
            plans.clear();
            index = recipes;
        }
        Key key = new Key(itemId, amount, copyStock(inventory), preferred == null ? Map.of() : Map.copyOf(preferred));
        CraftTreeCalculator.CraftPlan plan = plans.get(key);
        if (plan == null) {
            plan = CraftTreeCalculator.plan(recipes, itemId, amount, key.inventory(), key.preferred());
            computed++;
            plans.put(key, plan);
        }
        return plan;
    }

    /** Inventaire sans les quantités nulles (deux inventaires équivalents donnent la même clé). */
    private static Map<String, Integer> copyStock(Map<String, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return Map.copyOf(out);
    }

    public void clear() {
        plans.clear();
        index = null;
    }

    /** Nombre de plans réellement calculés depuis la création (mesure du cache, tests). */
    public int computedCount() {
        return computed;
    }

    public int size() {
        return plans.size();
    }

    /**
     * Recette suivante (ou précédente, {@code delta < 0}) parmi celles qui produisent {@code stepItemId}, en partant
     * de celle que le plan emploie, en sautant celles que le calculateur écarte (boucle avec un ancêtre) : rend
     * l'identifiant de la première qui, choisie, est réellement employée dans le plan, ou {@code null} s'il n'y en a
     * aucune autre.
     */
    public String cycleRecipe(RecipeIndex recipes, String itemId, int amount, Map<String, Integer> inventory,
                              Map<String, String> preferred, String stepItemId, int delta) {
        List<RecipeEntry> candidates = recipes.recipesForOutput(stepItemId);
        int n = candidates.size();
        if (n < 2 || delta == 0) {
            return null;
        }
        CraftTreeCalculator.CraftPlan current = plan(recipes, itemId, amount, inventory, preferred);
        RecipeEntry used = recipeUsedFor(current.root(), stepItemId);
        int start = used == null ? -1 : candidates.indexOf(used);
        int step = delta > 0 ? 1 : -1;
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod((start < 0 && step < 0 ? 0 : start) + k * step, n);
            RecipeEntry cand = candidates.get(i);
            if (cand.equals(used)) {
                continue;
            }
            Map<String, String> trial = new LinkedHashMap<>(preferred == null ? Map.of() : preferred);
            trial.put(stepItemId, cand.id());
            RecipeEntry now = recipeUsedFor(plan(recipes, itemId, amount, inventory, trial).root(), stepItemId);
            if (cand.equals(now)) {
                return cand.id();
            }
        }
        return null;
    }

    /** Recette employée au premier nœud de {@code itemId} rencontré dans l'arbre (parcours en profondeur), ou {@code null}. */
    public static RecipeEntry recipeUsedFor(CraftTreeCalculator.CraftNode node, String itemId) {
        if (node.itemId().equals(itemId) && node.recipe() != null) {
            return node.recipe();
        }
        for (CraftTreeCalculator.CraftNode child : node.steps()) {
            RecipeEntry r = recipeUsedFor(child, itemId);
            if (r != null) {
                return r;
            }
        }
        return null;
    }
}
