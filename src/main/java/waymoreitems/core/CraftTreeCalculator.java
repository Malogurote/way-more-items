package waymoreitems.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Calculateur d'arbre de fabrication interactif et de matières premières brutes (Java pur).
 * Décompose un produit fini (avec multiplicateur ×1, ×4, ×16, ×64 ou arbitraire) jusqu'aux ressources brutes :
 * - déduit à chaque étape ce que le joueur possède déjà en inventaire ;
 * - réutilise les surplus produits par les lots (ex. recette qui donne 4 câbles quand on en demande 2) ;
 * - évite les boucles infinies sur les recettes cycliques (bloc <-> 9 lingots <-> 9 pépites) ;
 * - permet au joueur de choisir quelle recette préférer pour n'importe quel sous-composant.
 */
public final class CraftTreeCalculator {
    private static final int MAX_DEPTH = 16;

    private CraftTreeCalculator() {
    }

    /**
     * Nœud de l'arbre de fabrication d'un composant.
     *
     * @param itemId        identifiant de l'objet demandé à ce nœud
     * @param needed        quantité totale requise à ce point de l'arbre
     * @param fromInventory quantité prélevée dans l'inventaire du joueur ou le surplus existant (en vert)
     * @param crafted       quantité réellement fabriquée par {@code recipe} (0 si matière brute ou couverte par l'inventaire)
     * @param batches       nombre d'exécutions de {@code recipe}
     * @param missingRaw    quantité manquante non fabriquable (en rouge, matière première à récolter)
     * @param recipe        recette employée (ou {@code null} si matière brute / couverte par l'inventaire)
     * @param cycleDetected vrai si la résolution s'est arrêtée ici pour briser une boucle de recettes
     * @param steps         sous-nœuds des ingrédients nécessaires aux {@code batches} exécutions de {@code recipe}
     */
    public record CraftNode(
            String itemId,
            int needed,
            int fromInventory,
            int crafted,
            int batches,
            int missingRaw,
            RecipeEntry recipe,
            boolean cycleDetected,
            List<CraftNode> steps
    ) {
        public CraftNode {
            Objects.requireNonNull(itemId, "itemId");
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public boolean isRawLeaf() {
            return recipe == null && missingRaw > 0;
        }
    }

    /**
     * Résultat consolidé d'un calcul d'arbre de fabrication.
     *
     * @param root             racine de l'arbre détaillé étape par étape
     * @param totalRawRequired total des matières premières brutes nécessaires (possédées + manquantes)
     * @param usedFromStock    quantités prélevées dans l'inventaire initial du joueur
     * @param missingRaw       matières premières manquantes à récolter dans la nature (alimente le Post-it d'atelier)
     * @param machineBatches   nombre de lots par procédé / machine intermédiaire
     * @param totalEnergyEu    énergie totale cumulée (en EU) sur l'ensemble des étapes
     * @param totalTicks       durée cumulée séquentielle (en ticks) des machines de la chaîne
     */
    public record CraftPlan(
            CraftNode root,
            Map<String, Integer> totalRawRequired,
            Map<String, Integer> usedFromStock,
            Map<String, Integer> missingRaw,
            Map<String, Integer> machineBatches,
            long totalEnergyEu,
            long totalTicks
    ) {
    }

    /**
     * Calcule le plan de fabrication complet pour {@code amount} exemplaires de {@code targetItemId}.
     *
     * @param index            index des recettes
     * @param targetItemId     identifiant de l'objet à fabriquer
     * @param amount           quantité souhaitée (ex. 1, 4, 16, 64)
     * @param initialInventory inventaire actuel du joueur ({@code itemId -> quantité})
     * @param preferredRecipes choix explicites de recettes par objet ({@code itemId -> recipeId})
     */
    public static CraftPlan plan(
            RecipeIndex index,
            String targetItemId,
            int amount,
            Map<String, Integer> initialInventory,
            Map<String, String> preferredRecipes
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(targetItemId, "targetItemId");
        if (amount < 1) {
            throw new IllegalArgumentException("amount < 1: " + amount);
        }

        Context ctx = new Context(
                index,
                initialInventory == null ? Map.of() : initialInventory,
                preferredRecipes == null ? Map.of() : preferredRecipes
        );

        CraftNode root = resolve(ctx, targetItemId, amount, new HashSet<>(), 0, true);

        return new CraftPlan(
                root,
                Collections.unmodifiableMap(ctx.totalRawRequired),
                Collections.unmodifiableMap(ctx.usedFromStock),
                Collections.unmodifiableMap(ctx.missingRaw),
                Collections.unmodifiableMap(ctx.machineBatches),
                ctx.totalEnergyEu,
                ctx.totalTicks
        );
    }

    private static final class Context {
        final RecipeIndex index;
        final Map<String, Integer> availableStock;
        final Map<String, Integer> surplusPool = new HashMap<>();
        final Map<String, String> preferredRecipes;

        final Map<String, Integer> totalRawRequired = new LinkedHashMap<>();
        final Map<String, Integer> usedFromStock = new LinkedHashMap<>();
        final Map<String, Integer> missingRaw = new LinkedHashMap<>();
        final Map<String, Integer> machineBatches = new LinkedHashMap<>();
        long totalEnergyEu;
        long totalTicks;

        Context(RecipeIndex index, Map<String, Integer> initialInventory, Map<String, String> preferredRecipes) {
            this.index = index;
            this.availableStock = new HashMap<>(initialInventory);
            // Fluides des contenants pleins (seaux) comptés comme stock, sauf s'ils sont déjà donnés explicitement
            for (Map.Entry<String, Integer> f : index.fluidsInContainers(initialInventory).entrySet()) {
                this.availableStock.putIfAbsent(f.getKey(), f.getValue());
            }
            this.preferredRecipes = preferredRecipes;
        }

        int takeAvailable(String itemId, int maxNeeded, boolean isRoot) {
            int taken = 0;
            // 1. D'abord puiser dans les surplus générés par des étapes précédentes
            int surp = surplusPool.getOrDefault(itemId, 0);
            if (surp > 0) {
                int useSurp = Math.min(surp, maxNeeded);
                surplusPool.put(itemId, surp - useSurp);
                taken += useSurp;
            }
            // 2. Puiser dans le stock initial du joueur (sauf à la racine si on demande le plan de l'objet lui-même,
            //    à moins qu'aucune recette n'existe)
            if (!isRoot && taken < maxNeeded) {
                int stock = availableStock.getOrDefault(itemId, 0);
                if (stock > 0) {
                    int useStock = Math.min(stock, maxNeeded - taken);
                    availableStock.put(itemId, stock - useStock);
                    usedFromStock.merge(itemId, useStock, Integer::sum);
                    taken += useStock;
                }
            }
            return taken;
        }
    }

    private static CraftNode resolve(
            Context ctx,
            String itemId,
            int needed,
            Set<String> ancestors,
            int depth,
            boolean isRoot
    ) {
        int fromInv = ctx.takeAvailable(itemId, needed, isRoot);
        int remaining = needed - fromInv;
        if (remaining <= 0) {
            return new CraftNode(itemId, needed, fromInv, 0, 0, 0, null, false, List.of());
        }

        if (depth >= MAX_DEPTH || ancestors.contains(itemId)) {
            ctx.totalRawRequired.merge(itemId, needed, Integer::sum);
            ctx.missingRaw.merge(itemId, remaining, Integer::sum);
            return new CraftNode(itemId, needed, fromInv, 0, 0, remaining, null, ancestors.contains(itemId), List.of());
        }

        RecipeEntry chosen = selectRecipe(ctx, itemId, ancestors);
        if (chosen == null) {
            // Matière première brute (aucune recette ou uniquement des recettes de déblocage cycliques)
            if (isRoot) {
                // Si la racine est une matière brute que le joueur possède déjà
                int stock = ctx.availableStock.getOrDefault(itemId, 0);
                if (stock > 0) {
                    int use = Math.min(stock, remaining);
                    ctx.availableStock.put(itemId, stock - use);
                    ctx.usedFromStock.merge(itemId, use, Integer::sum);
                    fromInv += use;
                    remaining -= use;
                }
            }
            ctx.totalRawRequired.merge(itemId, needed, Integer::sum);
            if (remaining > 0) {
                ctx.missingRaw.merge(itemId, remaining, Integer::sum);
            }
            return new CraftNode(itemId, needed, fromInv, 0, 0, remaining, null, false, List.of());
        }

        int outPerBatch = Math.max(1, chosen.outputCount(itemId));
        int batches = (remaining + outPerBatch - 1) / outPerBatch;
        int produced = batches * outPerBatch;
        int surplus = produced - remaining;
        if (surplus > 0) {
            ctx.surplusPool.merge(itemId, surplus, Integer::sum);
        }
        // Enregistrer aussi les co-produits éventuels de la recette dans les surplus
        for (RecipeEntry.ItemAmount out : chosen.outputs()) {
            if (!out.itemId().equals(itemId)) {
                ctx.surplusPool.merge(out.itemId(), batches * out.count(), Integer::sum);
            }
        }

        ctx.machineBatches.merge(chosen.typeId(), batches, Integer::sum);
        ctx.totalEnergyEu += (long) batches * chosen.totalEnergy();
        ctx.totalTicks += (long) batches * chosen.durationTicks();

        // Consolider les ingrédients de cette recette par objet choisi
        Map<String, Integer> consolidatedInputs = new LinkedHashMap<>();
        for (RecipeEntry.IngredientSlot slot : chosen.inputs()) {
            String chosenAlt = pickBestAlternative(ctx, slot.alternatives(), ancestors);
            consolidatedInputs.merge(chosenAlt, slot.count() * batches, Integer::sum);
        }

        Set<String> nextAncestors = new HashSet<>(ancestors);
        nextAncestors.add(itemId);

        List<CraftNode> children = new ArrayList<>(consolidatedInputs.size());
        for (Map.Entry<String, Integer> entry : consolidatedInputs.entrySet()) {
            children.add(resolve(ctx, entry.getKey(), entry.getValue(), nextAncestors, depth + 1, false));
        }

        return new CraftNode(itemId, needed, fromInv, produced, batches, 0, chosen, false, children);
    }

    private static String pickBestAlternative(Context ctx, List<String> alternatives, Set<String> ancestors) {
        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }
        // 1. Préférer un objet déjà présent dans le stock ou le surplus du joueur, jamais un ancêtre (le surplus
        //    de l'objet visé ne doit pas servir à le refabriquer : câble teint -> câble lavé -> câble teint)
        for (String alt : alternatives) {
            if (!ancestors.contains(alt)
                    && (ctx.surplusPool.getOrDefault(alt, 0) > 0 || ctx.availableStock.getOrDefault(alt, 0) > 0)) {
                return alt;
            }
        }
        // 2. Préférer un objet qui ne crée pas de cycle
        for (String alt : alternatives) {
            if (!ancestors.contains(alt)) {
                return alt;
            }
        }
        return alternatives.get(0);
    }

    private static RecipeEntry selectRecipe(Context ctx, String itemId, Set<String> ancestors) {
        List<RecipeEntry> candidates = ctx.index.recipesForOutput(itemId);
        if (candidates.isEmpty()) {
            return null;
        }

        String prefId = ctx.preferredRecipes.get(itemId);
        if (prefId != null) {
            for (RecipeEntry r : candidates) {
                if (r.id().equals(prefId) && !causesImmediateCycle(r, ancestors)) {
                    return r;
                }
            }
        }

        // Écarter les recettes de décompression (1 bloc -> 9 lingots) quand on cherche à obtenir une matière de base,
        // sauf si le joueur possède déjà le bloc en stock !
        RecipeEntry best = null;
        int bestScore = Integer.MIN_VALUE;

        for (RecipeEntry r : candidates) {
            // Remplir ou vider un seau n'est pas une étape de fabrication : le fluide reste une ressource de base
            if (r.isFluidContainerRecipe()) {
                continue;
            }
            if (causesImmediateCycle(r, ancestors)) {
                continue;
            }
            if (WmiRegistry.isExcludedFromCraftTree(r.typeId())) {
                continue;
            }
            if (isUnpackingRecipe(ctx.index, r, itemId) && !hasInputInStock(ctx, r)) {
                continue;
            }
            if (isRoundTrip(ctx.index, r, itemId, ancestors) && !hasInputInStock(ctx, r)) {
                continue;
            }
            int score = scoreRecipe(ctx, r, itemId);
            // Recette de conversion entre variantes (teinture, lavage) qui accepte l'objet lui-même ou un ancêtre :
            // elle ne fait que tourner en rond, une vraie recette de fabrication passe avant.
            if (acceptsAncestor(r, ancestors, itemId)) {
                score -= 400;
            }
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        // Si la meilleure recette exige un minerai en bloc (obtenu seulement avec Toucher de soie), l'objet se récolte
        // directement en minant (redstone, charbon, diamant...) : on le traite comme une matière première.
        if (best != null && needsOreBlock(best) && !hasInputInStock(ctx, best)) {
            return null;
        }
        return best;
    }

    private static boolean acceptsAncestor(RecipeEntry r, Set<String> ancestors, String itemId) {
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            for (String alt : slot.alternatives()) {
                if (alt.equals(itemId) || ancestors.contains(alt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean causesImmediateCycle(RecipeEntry r, Set<String> ancestors) {
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            boolean allInAncestors = true;
            for (String alt : slot.alternatives()) {
                if (!ancestors.contains(alt)) {
                    allInAncestors = false;
                    break;
                }
            }
            if (allInAncestors) {
                return true;
            }
        }
        return false;
    }

    /**
     * Détecte une recette de décompression (ex. 1 bloc d'étain -> 9 lingots d'étain) lorsque le bloc lui-même
     * se fabrique à partir de 9 lingots d'étain.
     */
    private static boolean isUnpackingRecipe(RecipeIndex index, RecipeEntry r, String outputId) {
        if (r.inputs().size() != 1 || r.outputCount(outputId) < 4) {
            return false;
        }
        RecipeEntry.IngredientSlot single = r.inputs().get(0);
        if (single.count() != 1) {
            return false;
        }
        // Un objet qui en rend 9 est un bloc de stockage (y compris ciré, oxydé...) : décompression
        if (r.outputCount(outputId) >= 9) {
            return true;
        }
        for (String inId : single.alternatives()) {
            for (RecipeEntry rev : index.recipesForOutput(inId)) {
                if (rev.uses(outputId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Profondeur et nombre de visites maximaux de la recherche d'allers-retours (garde-fous de performance). */
    private static final int ROUND_TRIP_DEPTH = 6;
    private static final int ROUND_TRIP_BUDGET = 4000;

    /**
     * Vrai si la recette {@code r} est un démontage (un seul objet en entrée, plusieurs exemplaires de l'objet visé
     * en sortie) d'un objet lui-même fabriqué uniquement avec l'objet visé (ou un ancêtre) : cristal de certus tiré
     * d'une variante décorative de son bloc, elle-même faite de cristaux. Généralise la décompression directe (bloc
     * vers lingots) aux chaînes de plusieurs étapes. Si toutes les recettes d'un objet sont de ce genre, il se
     * récolte dans le monde et devient une matière première. Les recettes de 1 pour 1 (variantes décoratives) ne
     * sont jamais concernées : dans un cycle pur, seul le sens du démontage désigne la matière d'origine.
     */
    private static boolean isRoundTrip(RecipeIndex index, RecipeEntry r, String itemId, Set<String> ancestors) {
        if (r.inputs().size() != 1 || r.inputs().get(0).count() != 1 || r.outputCount(itemId) < 2) {
            return false;
        }
        Set<String> sources = new HashSet<>(ancestors);
        sources.add(itemId);
        Set<String> visiting = new HashSet<>();
        visiting.add(itemId);
        return recipeNeeds(index, r, sources, visiting, ROUND_TRIP_DEPTH, new int[]{ROUND_TRIP_BUDGET});
    }

    /** Vrai si une entrée de {@code r} n'admet que des objets fabriqués uniquement à partir de {@code sources}. */
    private static boolean recipeNeeds(RecipeIndex index, RecipeEntry r, Set<String> sources, Set<String> visiting,
                                       int depth, int[] budget) {
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            if (slot.alternatives().isEmpty()) {
                continue;
            }
            boolean all = true;
            for (String alt : slot.alternatives()) {
                if (!madeOnlyFrom(index, alt, sources, visiting, depth - 1, budget)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vrai si l'objet {@code x} ne s'obtient qu'à partir de {@code sources} : toutes ses recettes (hors boucles
     * vers les objets en cours d'examen) en dépendent. Un objet sans recette est naturel : il n'en dépend pas.
     */
    private static boolean madeOnlyFrom(RecipeIndex index, String x, Set<String> sources, Set<String> visiting,
                                        int depth, int[] budget) {
        if (sources.contains(x)) {
            return true;
        }
        if (depth <= 0 || visiting.contains(x) || --budget[0] < 0) {
            return false;
        }
        List<RecipeEntry> recipes = index.recipesForOutput(x);
        if (recipes.isEmpty()) {
            return false;
        }
        visiting.add(x);
        try {
            return allRecipesNeed(index, recipes, sources, visiting, depth, budget);
        } finally {
            visiting.remove(x);
        }
    }

    /** Vrai si au moins une recette compte (hors boucles) et que toutes celles qui comptent dépendent de {@code sources}. */
    private static boolean allRecipesNeed(RecipeIndex index, List<RecipeEntry> recipes, Set<String> sources,
                                          Set<String> visiting, int depth, int[] budget) {
        int counted = 0;
        for (RecipeEntry r : recipes) {
            if (loopsBack(r, sources, visiting)) {
                continue;
            }
            counted++;
            if (!recipeNeeds(index, r, sources, visiting, depth, budget)) {
                return false;
            }
        }
        return counted > 0;
    }

    /** Vrai si une entrée de {@code r} ne renvoie qu'à des objets en cours d'examen (boucle sans nouvelle matière). */
    private static boolean loopsBack(RecipeEntry r, Set<String> sources, Set<String> visiting) {
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            if (slot.alternatives().isEmpty()) {
                continue;
            }
            boolean all = true;
            for (String alt : slot.alternatives()) {
                if (sources.contains(alt) || !visiting.contains(alt)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInputInStock(Context ctx, RecipeEntry r) {
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            for (String alt : slot.alternatives()) {
                if (ctx.availableStock.getOrDefault(alt, 0) >= slot.count()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int scoreRecipe(Context ctx, RecipeEntry r, String outputId) {
        int score = 0;
        if (ctx.index.canExecuteSingleRecipe(r, ctx.availableStock)) {
            score += 1000;
        }
        // Préférer les recettes d'artisanat ou de fonderie standard aux recettes coûteuses si équivalentes
        score += r.outputCount(outputId) * 10;
        score -= r.inputs().size();
        // Moins d'objets consommés : écarte par exemple 9 pépites au profit d'un fer brut
        int consumed = 0;
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            consumed += slot.count();
        }
        score -= consumed;
        // Minerai en bloc (Toucher de soie) : voie peu naturelle
        if (needsOreBlock(r)) {
            score -= 500;
        }
        // Variantes accélérées du four (haut fourneau, fumoir, feu de camp) : le four ordinaire passe d'abord
        if (VARIANT_SMELTING_TYPES.contains(r.typeId())) {
            score -= 3;
        }
        return score;
    }

    private static final Set<String> VARIANT_SMELTING_TYPES = Set.of(
            "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking");

    /** Vrai si une entrée de la recette n'admet que des minerais en bloc (identifiant en {@code ..._ore}). */
    static boolean needsOreBlock(RecipeEntry r) {
        for (RecipeEntry.IngredientSlot slot : r.inputs()) {
            boolean allOres = !slot.alternatives().isEmpty();
            for (String alt : slot.alternatives()) {
                String path = alt.substring(alt.indexOf(':') + 1);
                if (!path.endsWith("_ore")) {
                    allOres = false;
                    break;
                }
            }
            if (allOres) {
                return true;
            }
        }
        return false;
    }
}
