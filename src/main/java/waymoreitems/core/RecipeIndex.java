package waymoreitems.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Graphe des recettes et index de recherche du catalogue de Way More Items (Java pur).
 * Construit une seule fois à la réception des recettes du serveur ; toutes les requêtes sont sans effet de bord.
 *
 * Syntaxe de recherche (inspirée de NEI/JEI/REI/EMI et enrichie) :
 * - Mot simple : filtre sur le nom traduit ou l'identifiant (insensible à la casse et aux accents).
 * - {@code @mod} : filtre sur l'espace de noms ou le nom du mod.
 * - {@code #tag} : filtre sur les étiquettes (ex. {@code #c:ingots} ou {@code #ingots}).
 * - {@code $texte} : filtre sur le texte de l'infobulle (tooltip).
 * - {@code %procédé} ou {@code ^machine} : filtre sur les objets produits ou traités par une machine ou un type de recette.
 * - {@code -terme} : exclusion (tout préfixe ci-dessus peut être précédé de {@code -}).
 * - {@code A | B} : union (OU logique) entre deux requêtes complètes.
 */
public final class RecipeIndex {
    private final List<CatalogItem> items;
    private final Map<String, CatalogItem> itemById;
    private final List<RecipeEntry> recipes;
    private final Map<String, RecipeEntry> recipeById;
    private final Map<String, List<RecipeEntry>> byOutput;
    private final Map<String, List<RecipeEntry>> byInput;
    private final Map<String, List<RecipeEntry>> byWorkstation;
    private final Map<String, List<RecipeEntry>> byType;
    private final Map<String, Set<String>> machineRelatedItems;

    public RecipeIndex(List<CatalogItem> items, List<RecipeEntry> recipes) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(recipes, "recipes");

        this.items = List.copyOf(items);
        Map<String, CatalogItem> idMap = new LinkedHashMap<>(items.size());
        for (CatalogItem item : this.items) {
            idMap.put(item.id(), item);
        }
        this.itemById = Collections.unmodifiableMap(idMap);

        this.recipes = List.copyOf(recipes);
        Map<String, RecipeEntry> rById = new LinkedHashMap<>(recipes.size());
        Map<String, List<RecipeEntry>> outMap = new HashMap<>();
        Map<String, List<RecipeEntry>> inMap = new HashMap<>();
        Map<String, List<RecipeEntry>> wsMap = new HashMap<>();
        Map<String, List<RecipeEntry>> typeMap = new LinkedHashMap<>();
        Map<String, Set<String>> machItems = new HashMap<>();

        for (RecipeEntry r : this.recipes) {
            rById.put(r.id(), r);
            typeMap.computeIfAbsent(r.typeId(), k -> new ArrayList<>()).add(r);

            String normCat = CatalogItem.normalize(r.categoryName()) + " " + CatalogItem.normalize(r.typeId());
            Set<String> machineKeys = new LinkedHashSet<>();
            machineKeys.add(normCat);

            for (String ws : r.workstations()) {
                wsMap.computeIfAbsent(ws, k -> new ArrayList<>()).add(r);
                CatalogItem wsItem = idMap.get(ws);
                String wsNorm = wsItem != null
                        ? wsItem.normalizedName()
                        : CatalogItem.normalize(ws);
                machineKeys.add(wsNorm);
            }

            Set<String> touchedItems = new LinkedHashSet<>();
            for (RecipeEntry.ItemAmount out : r.outputs()) {
                outMap.computeIfAbsent(out.itemId(), k -> new ArrayList<>()).add(r);
                touchedItems.add(out.itemId());
            }

            Set<String> seenInputs = new LinkedHashSet<>();
            for (RecipeEntry.IngredientSlot slot : r.inputs()) {
                for (String alt : slot.alternatives()) {
                    if (seenInputs.add(alt)) {
                        inMap.computeIfAbsent(alt, k -> new ArrayList<>()).add(r);
                    }
                    touchedItems.add(alt);
                }
            }
            for (RecipeEntry.IngredientSlot cat : r.catalysts()) {
                for (String alt : cat.alternatives()) {
                    if (seenInputs.add(alt)) {
                        inMap.computeIfAbsent(alt, k -> new ArrayList<>()).add(r);
                    }
                    touchedItems.add(alt);
                }
            }

            for (String mk : machineKeys) {
                machItems.computeIfAbsent(mk, k -> new LinkedHashSet<>()).addAll(touchedItems);
            }
        }

        this.recipeById = Collections.unmodifiableMap(rById);
        this.byOutput = freezeMultiMap(outMap);
        this.byInput = freezeMultiMap(inMap);
        this.byWorkstation = freezeMultiMap(wsMap);
        this.byType = freezeMultiMap(typeMap);
        this.machineRelatedItems = machItems;
    }

    private static Map<String, List<RecipeEntry>> freezeMultiMap(Map<String, List<RecipeEntry>> src) {
        Map<String, List<RecipeEntry>> res = new LinkedHashMap<>(src.size());
        for (Map.Entry<String, List<RecipeEntry>> e : src.entrySet()) {
            res.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(res);
    }

    public List<CatalogItem> allItems() {
        return items;
    }

    /**
     * Nombre d'objets et de recettes par groupe (par exemple un onglet de mod). Un objet appartient au groupe
     * de son espace de noms ; une recette appartient à chaque groupe qui contient au moins un de ses produits
     * (une seule fois par groupe). Résultat : groupe, puis {objets, recettes}.
     */
    public Map<String, int[]> countsByGroup(UnaryOperator<String> groupOfNamespace) {
        Map<String, int[]> res = new HashMap<>();
        for (CatalogItem it : items) {
            res.computeIfAbsent(groupOfNamespace.apply(it.namespace()), k -> new int[2])[0]++;
        }
        for (RecipeEntry r : recipes) {
            Set<String> groups = new HashSet<>();
            for (RecipeEntry.ItemAmount out : r.outputs()) {
                CatalogItem it = itemById.get(out.itemId());
                if (it != null) {
                    groups.add(groupOfNamespace.apply(it.namespace()));
                }
            }
            for (String gp : groups) {
                res.computeIfAbsent(gp, k -> new int[2])[1]++;
            }
        }
        return res;
    }

    public CatalogItem item(String id) {
        return itemById.get(id);
    }

    public List<RecipeEntry> allRecipes() {
        return recipes;
    }

    public RecipeEntry recipe(String id) {
        return recipeById.get(id);
    }

    /** Touche R : toutes les recettes qui produisent {@code itemId}. */
    public List<RecipeEntry> recipesForOutput(String itemId) {
        return byOutput.getOrDefault(itemId, List.of());
    }

    /**
     * Touche U : toutes les recettes qui emploient {@code itemId} comme ingrédient, catalyseur,
     * ainsi que celles exécutées par {@code itemId} s'il s'agit d'un poste de travail (machine).
     */
    public List<RecipeEntry> recipesUsing(String itemId) {
        List<RecipeEntry> asInput = byInput.getOrDefault(itemId, List.of());
        List<RecipeEntry> asStation = byWorkstation.getOrDefault(itemId, List.of());
        if (asStation.isEmpty()) {
            return asInput;
        }
        if (asInput.isEmpty()) {
            return asStation;
        }
        Set<RecipeEntry> merged = new LinkedHashSet<>(asInput.size() + asStation.size());
        merged.addAll(asInput);
        merged.addAll(asStation);
        return List.copyOf(merged);
    }

    /** Recettes exécutées par une machine donnée (clic sur l'en-tête de machine ou l'onglet). */
    public List<RecipeEntry> recipesForWorkstation(String workstationItemId) {
        return byWorkstation.getOrDefault(workstationItemId, List.of());
    }

    /** Recettes d'un procédé donné (ex. {@code "exemplemod:macerating"}). */
    public List<RecipeEntry> recipesForType(String typeId) {
        return byType.getOrDefault(typeId, List.of());
    }

    /** Identifiants de tous les types de recettes présents dans l'index, dans l'ordre d'enregistrement. */
    public Set<String> recipeTypes() {
        return byType.keySet();
    }

    /**
     * Recherche dans le catalogue selon la requête {@code query}.
     */
    public List<CatalogItem> search(String query) {
        return search(query, Map.of(), false, null);
    }

    /** Mot-clé de recherche qui ne retient que les objets fabricables avec l'inventaire (remplace le bouton « Fabricable »). */
    public static final String CRAFTABLE_KEYWORD = "!fab";

    /** Vrai si la recherche contient le mot {@link #CRAFTABLE_KEYWORD} (seul, sans tenir compte de la casse). */
    public static boolean hasCraftableKeyword(String query) {
        return hasKeyword(query, CRAFTABLE_KEYWORD);
    }

    /** La recherche sans le mot {@link #CRAFTABLE_KEYWORD}. */
    public static String stripCraftableKeyword(String query) {
        return stripKeyword(query, CRAFTABLE_KEYWORD);
    }

    /** Mot-clé de recherche qui ne retient que les fluides. */
    public static final String FLUID_KEYWORD = "!fluid";

    /** Vrai si la recherche contient le mot-clé {@code keyword} (seul, sans tenir compte de la casse). */
    public static boolean hasKeyword(String query, String keyword) {
        if (query == null) {
            return false;
        }
        for (String w : query.trim().split("[\\s|]+")) {
            if (w.equalsIgnoreCase(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** La recherche sans le mot-clé {@code keyword}. */
    public static String stripKeyword(String query, String keyword) {
        if (query == null) {
            return "";
        }
        return query.replaceAll("(?i)(^|(?<=[\\s|]))" + Pattern.quote(keyword) + "(?=$|[\\s|])", "").trim();
    }

    /**
     * Recherche contextuelle dans le catalogue :
     *
     * @param query               texte de recherche (supporte {@code @mod}, {@code #tag}, {@code $infobulle}, {@code ^machine}, {@code -exclusion}, {@code |} et {@code !fab})
     * @param playerInventory     inventaire courant du joueur ({@code itemId -> quantité})
     * @param onlyCraftable       si vrai, ne retient que les objets fabriquables immédiatement (directement ou en chaîne) avec {@code playerInventory}
     * @param activeWorkstationId si non nul, trie en tête les objets que cette machine peut traiter avec l'inventaire du joueur
     */
    public List<CatalogItem> search(
            String query,
            Map<String, Integer> playerInventory,
            boolean onlyCraftable,
            String activeWorkstationId
    ) {
        Map<String, Integer> inv = playerInventory == null ? Map.of() : playerInventory;
        String q = query == null ? "" : query;
        if (hasCraftableKeyword(q)) {
            onlyCraftable = true;
            q = stripCraftableKeyword(q);
        }
        boolean onlyFluids = hasKeyword(q, FLUID_KEYWORD);
        if (onlyFluids) {
            q = stripKeyword(q, FLUID_KEYWORD);
        }
        List<List<Token>> branches = parseQuery(q);

        List<CatalogItem> matched = new ArrayList<>();
        for (CatalogItem item : items) {
            if (onlyFluids && !item.isFluid()) {
                continue;
            }
            if (!matchesBranches(item, branches)) {
                continue;
            }
            if (onlyCraftable && !isCraftableFromInventory(item.id(), inv)) {
                continue;
            }
            matched.add(item);
        }

        if (activeWorkstationId != null && !activeWorkstationId.isBlank()) {
            Set<String> priorityIds = computeWorkstationPriority(activeWorkstationId, inv);
            if (!priorityIds.isEmpty()) {
                List<CatalogItem> top = new ArrayList<>();
                List<CatalogItem> rest = new ArrayList<>();
                for (CatalogItem it : matched) {
                    if (priorityIds.contains(it.id())) {
                        top.add(it);
                    } else {
                        rest.add(it);
                    }
                }
                top.addAll(rest);
                return Collections.unmodifiableList(top);
            }
        }

        return Collections.unmodifiableList(matched);
    }

    /**
     * Détermine les objets pertinents (produits et entrées) pour la machine ouverte {@code workstationId}
     * lorsque le joueur possède les ingrédients requis.
     */
    private Set<String> computeWorkstationPriority(String workstationId, Map<String, Integer> inv) {
        List<RecipeEntry> stationRecipes = recipesForWorkstation(workstationId);
        if (stationRecipes.isEmpty()) {
            return Set.of();
        }
        Set<String> prioritized = new LinkedHashSet<>();
        for (RecipeEntry r : stationRecipes) {
            if (canExecuteSingleRecipe(r, inv)) {
                for (RecipeEntry.ItemAmount out : r.outputs()) {
                    prioritized.add(out.itemId());
                }
                for (RecipeEntry.IngredientSlot slot : r.inputs()) {
                    for (String alt : slot.alternatives()) {
                        if (inv.getOrDefault(alt, 0) > 0) {
                            prioritized.add(alt);
                        }
                    }
                }
            }
        }
        return prioritized;
    }

    /**
     * Fluides contenus dans les objets de {@code inv} (seaux pleins, etc.), déduits des pseudo-recettes de vidange
     * ({@link RecipeEntry#FLUID_EMPTYING_TYPE}) : {@code cléFluide -> mB}. Un objet vidable de plusieurs façons ne
     * compte que pour la première.
     */
    public Map<String, Integer> fluidsInContainers(Map<String, Integer> inv) {
        if (inv == null || inv.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n <= 0 || !IngredientRef.isItem(e.getKey())) {
                continue;
            }
            for (RecipeEntry r : recipesUsing(e.getKey())) {
                if (!RecipeEntry.FLUID_EMPTYING_TYPE.equals(r.typeId()) || r.inputs().size() != 1
                        || !r.inputs().get(0).matches(e.getKey())) {
                    continue;
                }
                int runs = n / r.inputs().get(0).count();
                for (RecipeEntry.ItemAmount o : r.outputs()) {
                    if (o.isFluid() && runs > 0) {
                        out.merge(o.itemId(), (int) Math.min(Integer.MAX_VALUE, (long) runs * o.count()), Integer::sum);
                    }
                }
                break;
            }
        }
        return out;
    }

    /**
     * Vérifie si une recette individuelle peut être exécutée au moins une fois avec le contenu direct de {@code inv}.
     */
    public boolean canExecuteSingleRecipe(RecipeEntry recipe, Map<String, Integer> inv) {
        if (recipe.inputs().isEmpty()) {
            return true;
        }
        Map<String, Integer> remaining = new HashMap<>(inv);
        for (RecipeEntry.IngredientSlot slot : recipe.inputs()) {
            int needed = slot.count();
            boolean satisfied = false;
            for (String alt : slot.alternatives()) {
                int avail = remaining.getOrDefault(alt, 0);
                if (avail >= needed) {
                    remaining.put(alt, avail - needed);
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    /**
     * Vérifie si un objet peut être fabriqué à partir de {@code inv} (en une étape ou via l'arbre de fabrication).
     */
    public boolean isCraftableFromInventory(String itemId, Map<String, Integer> inv) {
        List<RecipeEntry> prod = recipesForOutput(itemId);
        if (prod.isEmpty()) {
            return false;
        }
        for (RecipeEntry r : prod) {
            if (canExecuteSingleRecipe(r, inv)) {
                return true;
            }
        }
        CraftTreeCalculator.CraftPlan plan = CraftTreeCalculator.plan(this, itemId, 1, inv, Map.of());
        return !plan.root().steps().isEmpty() && plan.missingRaw().isEmpty();
    }

    private boolean matchesBranches(CatalogItem item, List<List<Token>> branches) {
        if (branches.isEmpty()) {
            return true;
        }
        for (List<Token> branch : branches) {
            if (matchesBranch(item, branch)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBranch(CatalogItem item, List<Token> tokens) {
        for (Token token : tokens) {
            boolean hit = matchesSingleToken(item, token.kind(), token.value());
            if (token.negated() ? hit : !hit) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesSingleToken(CatalogItem item, TokenKind kind, String val) {
        return switch (kind) {
            case NAME -> item.normalizedName().contains(val);
            case MOD -> item.normalizedMod().contains(val);
            case TAG -> {
                for (String t : item.normalizedTags()) {
                    if (t.contains(val)) {
                        yield true;
                    }
                }
                yield false;
            }
            case TOOLTIP -> item.normalizedTooltip().contains(val);
            case MACHINE -> {
                for (Map.Entry<String, Set<String>> e : machineRelatedItems.entrySet()) {
                    if (e.getKey().contains(val) && e.getValue().contains(item.id())) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    private enum TokenKind {
        NAME, MOD, TAG, TOOLTIP, MACHINE
    }

    private record Token(TokenKind kind, String value, boolean negated) {
    }

    private static List<List<Token>> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        String[] orParts = rawQuery.split("\\|");
        List<List<Token>> branches = new ArrayList<>();
        for (String part : orParts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] words = trimmed.split("\\s+");
            List<Token> tokens = new ArrayList<>();
            for (String w : words) {
                if (w.isEmpty()) {
                    continue;
                }
                boolean neg = false;
                if (w.startsWith("-") && w.length() > 1) {
                    neg = true;
                    w = w.substring(1);
                }
                TokenKind kind = TokenKind.NAME;
                if (w.length() > 1) {
                    char prefix = w.charAt(0);
                    if (prefix == '@') {
                        kind = TokenKind.MOD;
                        w = w.substring(1);
                    } else if (prefix == '#') {
                        kind = TokenKind.TAG;
                        w = w.substring(1);
                    } else if (prefix == '$') {
                        kind = TokenKind.TOOLTIP;
                        w = w.substring(1);
                    } else if (prefix == '%' || prefix == '^') {
                        kind = TokenKind.MACHINE;
                        w = w.substring(1);
                    }
                }
                String norm = CatalogItem.normalize(w);
                if (!norm.isEmpty()) {
                    tokens.add(new Token(kind, norm, neg));
                }
            }
            if (!tokens.isEmpty()) {
                branches.add(tokens);
            }
        }
        return branches;
    }
}
