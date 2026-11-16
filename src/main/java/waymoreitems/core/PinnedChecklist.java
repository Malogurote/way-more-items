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
import java.util.function.IntFunction;

/**
 * Contenu des Notes (Post-it d'atelier, HUD en jeu), en Java pur : notes textuelles, objectifs de fabrication
 * (objet principal et arbre de ses ingrédients) et objets libres (objet et quantité voulue, sans arbre).
 * <p>Les arbres sont calculés sur un inventaire vide (quantités stables, indépendantes de ce que le joueur possède
 * au moment de l'épinglage) ; la progression se lit ensuite dans l'inventaire courant. Le franchissement du seuil
 * d'une ligne toujours visible (objectif, premier niveau d'ingrédients, objet libre) déclenche le tintement.
 */
public final class PinnedChecklist {
    private final List<NoteEntry> notes = new ArrayList<>();
    private final List<Goal> goals = new ArrayList<>();
    private final Map<String, Integer> inventory = new HashMap<>();
    private final Set<String> completedKeys = new HashSet<>();
    /** Branches dépliées par le joueur (clé de ligne, voir {@link Line#key()}). */
    private final Set<String> expanded = new HashSet<>();
    /** Recettes choisies par le joueur dans l'arbre, valables pour tous les arbres (objectifs compris). */
    private final RecipePreferences recipeChoices = new RecipePreferences();
    private RecipeIndex index;

    /**
     * Note textuelle libre.
     *
     * @param text texte de la note
     * @param done vrai si la note est cochée comme terminée
     */
    public record NoteEntry(String text, boolean done) {
    }

    /**
     * Progression d'un objectif (objet principal ou objet libre), pour les vues à plat.
     *
     * @param itemId    identifiant de l'objet
     * @param current   quantité possédée
     * @param required  quantité voulue
     * @param progress  ratio borné entre {@code 0.0} et {@code 1.0}
     * @param completed vrai si {@code current >= required}
     */
    public record EntryProgress(String itemId, int current, int required, double progress, boolean completed) {
    }

    /**
     * Nœud figé d'un arbre d'ingrédients.
     *
     * @param itemId       objet retenu à ce nœud
     * @param needed       quantité requise pour l'objectif entier
     * @param alternatives objets acceptés à sa place (variantes d'une étiquette), comptés ensemble dans l'inventaire
     * @param children     ingrédients de la recette retenue (vide pour une matière première)
     */
    public record TreeNode(String itemId, int needed, List<String> alternatives, List<TreeNode> children) {
        public TreeNode {
            Objects.requireNonNull(itemId, "itemId");
            alternatives = alternatives == null || alternatives.isEmpty() ? List.of(itemId) : List.copyOf(alternatives);
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /** Nature d'une ligne : objet principal d'un arbre, ingrédient, objet libre. */
    public enum LineKind { GOAL, INGREDIENT, ITEM }

    /**
     * Ligne à afficher.
     *
     * @param key        clé stable (dépliage) : {@code c:<objet>} pour un objectif, suivie du chemin des ingrédients
     *                   ({@code /0/2}) ; {@code i:<objet>} pour un objet libre
     * @param goalIndex  rang de l'objectif dans {@link #goalCount()}
     * @param depth      0 pour un objectif ou un objet libre, 1 et plus pour les ingrédients
     * @param kind       nature de la ligne
     * @param itemId     objet affiché
     * @param current    quantité possédée (variantes comprises)
     * @param required   quantité voulue
     * @param completed  vrai si {@code current >= required}
     * @param expandable vrai si la ligne a des sous-ingrédients que la profondeur autorisée permet de déplier
     * @param expanded   vrai si ces sous-ingrédients sont affichés
     */
    public record Line(String key, int goalIndex, int depth, LineKind kind, String itemId, int current, int required,
                       boolean completed, boolean expandable, boolean expanded) {
    }

    private static final class Goal {
        final String itemId;
        final boolean craft;
        final Map<String, String> preferred = new LinkedHashMap<>();
        int count;
        TreeNode tree;

        Goal(String itemId, boolean craft, int count) {
            this.itemId = itemId;
            this.craft = craft;
            this.count = count;
        }

        String key() {
            return (craft ? "c:" : "i:") + itemId;
        }
    }

    // ==================== INDEX DES RECETTES ====================

    /**
     * Index utilisé pour calculer les arbres. Un nouvel index (recettes resynchronisées, objectifs relus au
     * démarrage) recalcule tous les arbres.
     */
    public void useIndex(RecipeIndex newIndex) {
        if (newIndex == null || newIndex == index) {
            return;
        }
        index = newIndex;
        for (Goal g : goals) {
            if (g.craft) {
                rebuildTree(g, null);
            }
        }
    }

    private void rebuildTree(Goal g, CraftTreeCalculator.CraftNode fallback) {
        if (index != null && !index.recipesForOutput(g.itemId).isEmpty()) {
            Map<String, String> pref = new LinkedHashMap<>(recipeChoices.asMap());
            pref.putAll(g.preferred);
            CraftTreeCalculator.CraftPlan plan = CraftTreeCalculator.plan(index, g.itemId, g.count, Map.of(), pref);
            g.tree = freeze(plan.root());
        } else if (fallback != null) {
            g.tree = freeze(fallback);
        }
    }

    /** Arbre figé d'un nœud calculé : chaque ingrédient garde les variantes acceptées par sa case de recette. */
    private static TreeNode freeze(CraftTreeCalculator.CraftNode node) {
        List<TreeNode> children = new ArrayList<>(node.steps().size());
        for (CraftTreeCalculator.CraftNode child : node.steps()) {
            children.add(withAlternatives(freeze(child), node.recipe()));
        }
        return new TreeNode(node.itemId(), node.needed(), List.of(node.itemId()), children);
    }

    private static TreeNode withAlternatives(TreeNode node, RecipeEntry parentRecipe) {
        if (parentRecipe == null) {
            return node;
        }
        Set<String> alts = new LinkedHashSet<>();
        alts.add(node.itemId());
        for (RecipeEntry.IngredientSlot slot : parentRecipe.inputs()) {
            if (slot.matches(node.itemId())) {
                alts.addAll(slot.alternatives());
            }
        }
        return new TreeNode(node.itemId(), node.needed(), new ArrayList<>(alts), node.children());
    }

    // ==================== CHOIX DE RECETTES ====================

    /** Recettes choisies par le joueur pour les étapes de l'arbre (à enregistrer avec les réglages). */
    public RecipePreferences recipeChoices() {
        return recipeChoices;
    }

    /**
     * Choisit {@code recipeId} pour fabriquer {@code itemId} dans tous les arbres ({@code null} : choix automatique).
     * Les objectifs des Notes dont l'arbre passe par cet objet suivent le choix et sont recalculés.
     *
     * @return vrai si le choix a changé
     */
    public boolean chooseRecipe(String itemId, String recipeId) {
        if (!recipeChoices.set(itemId, recipeId)) {
            return false;
        }
        for (Goal g : goals) {
            if (!g.craft || !(g.preferred.containsKey(itemId) || (g.tree != null && contains(g.tree, itemId)))) {
                continue;
            }
            if (recipeId == null || recipeId.isEmpty()) {
                g.preferred.remove(itemId);
            } else {
                g.preferred.put(itemId, recipeId);
            }
            rebuildTree(g, null);
        }
        refreshCompletion();
        return true;
    }

    private static boolean contains(TreeNode node, String itemId) {
        if (node.itemId().equals(itemId)) {
            return true;
        }
        for (TreeNode child : node.children()) {
            if (contains(child, itemId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== OBJECTIFS ====================

    /**
     * Épingle un objectif de fabrication ({@code count} exemplaires de {@code itemId}). Un objectif existant pour le
     * même objet voit sa quantité augmenter et son arbre recalculé.
     *
     * @param preferred choix de recettes par objet ({@code itemId -> recipeId}), repris dans le calcul de l'arbre
     * @param fallback  arbre pour la quantité totale de l'objectif, employé si aucun index n'est disponible
     *                  (peut être {@code null} ou rendre {@code null})
     */
    public void pinCraft(String itemId, int count, Map<String, String> preferred,
                         IntFunction<CraftTreeCalculator.CraftNode> fallback) {
        Objects.requireNonNull(itemId, "itemId");
        Goal g = findGoal(itemId, true);
        if (g == null) {
            g = new Goal(itemId, true, Math.max(1, count));
            goals.add(g);
        } else {
            g.count += Math.max(1, count);
        }
        if (preferred != null) {
            g.preferred.putAll(preferred);
        }
        rebuildTree(g, fallback == null ? null : fallback.apply(g.count));
        refreshCompletion();
    }

    /**
     * Épingle l'objectif d'un arbre de fabrication (bouton « Épingler » de la fenêtre de l'arbre) : les recettes
     * choisies dans le plan sont conservées pour le calcul de l'arbre des Notes.
     */
    public void pinPlan(String targetItemId, int targetCount, CraftTreeCalculator.CraftPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, String> preferred = new LinkedHashMap<>();
        collectRecipes(plan.root(), preferred);
        int asked = Math.max(1, targetCount);
        pinCraft(targetItemId, asked, preferred, total -> total == asked ? plan.root() : null);
    }

    private static void collectRecipes(CraftTreeCalculator.CraftNode node, Map<String, String> out) {
        if (node.recipe() != null) {
            out.putIfAbsent(node.itemId(), node.recipe().id());
        }
        for (CraftTreeCalculator.CraftNode child : node.steps()) {
            collectRecipes(child, out);
        }
    }

    /**
     * Bouton « + » d'une recette : épingle son résultat comme objectif ({@code batches} exécutions), avec les
     * ingrédients de cette recette. Les catalyseurs ne sont pas comptés.
     *
     * @return le nombre d'ingrédients distincts du premier niveau de l'arbre
     */
    public int pinRecipeInputs(RecipeEntry recipe, int batches) {
        Objects.requireNonNull(recipe, "recipe");
        if (recipe.outputs().isEmpty()) {
            return 0;
        }
        String out = recipe.outputs().get(0).itemId();
        int count = Math.max(1, recipe.outputCount(out)) * Math.max(1, batches);
        pinCraft(out, count, Map.of(out, recipe.id()), total -> recipeOnlyNode(recipe, out, total));
        Goal g = findGoal(out, true);
        return g == null || g.tree == null ? 0 : g.tree.children().size();
    }

    /** Arbre d'un seul niveau tiré de la recette (sans index), pour {@link #pinRecipeInputs}. */
    private static CraftTreeCalculator.CraftNode recipeOnlyNode(RecipeEntry recipe, String out, int count) {
        int perBatch = Math.max(1, recipe.outputCount(out));
        int batches = (count + perBatch - 1) / perBatch;
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (RecipeEntry.IngredientSlot slot : recipe.inputs()) {
            totals.merge(slot.alternatives().get(0), slot.count() * batches, Integer::sum);
        }
        List<CraftTreeCalculator.CraftNode> children = new ArrayList<>();
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            children.add(new CraftTreeCalculator.CraftNode(e.getKey(), e.getValue(), 0, 0, 0, e.getValue(), null, false, List.of()));
        }
        return new CraftTreeCalculator.CraftNode(out, count, 0, batches * perBatch, batches, 0, recipe, false, children);
    }

    /** Ajoute {@code amountToAdd} exemplaires d'un objet libre (sans arbre), ou augmente sa quantité voulue. */
    public void pinOrIncrementItem(String itemId, int amountToAdd) {
        Objects.requireNonNull(itemId, "itemId");
        if (amountToAdd < 1) {
            throw new IllegalArgumentException("amountToAdd < 1");
        }
        Goal g = findGoal(itemId, false);
        if (g == null) {
            goals.add(new Goal(itemId, false, amountToAdd));
        } else {
            g.count += amountToAdd;
        }
        refreshCompletion();
    }

    /** Retire l'objet libre {@code itemId}, ou à défaut l'objectif de fabrication de cet objet. */
    public void unpinItem(String itemId) {
        Goal g = findGoal(itemId, false);
        if (g == null) {
            g = findGoal(itemId, true);
        }
        if (g != null) {
            goals.remove(g);
            refreshCompletion();
        }
    }

    /** Retire l'objectif de rang {@code goalIndex}. */
    public void removeGoal(int goalIndex) {
        if (goalIndex >= 0 && goalIndex < goals.size()) {
            goals.remove(goalIndex);
            refreshCompletion();
        }
    }

    /** Arbre figé de l'objectif de rang {@code goalIndex} ({@code null} pour un objet libre ou sans arbre). */
    TreeNode goalTree(int goalIndex) {
        return goalIndex < 0 || goalIndex >= goals.size() ? null : goals.get(goalIndex).tree;
    }

    public int goalCount() {
        return goals.size();
    }

    private Goal findGoal(String itemId, boolean craft) {
        for (Goal g : goals) {
            if (g.craft == craft && g.itemId.equals(itemId)) {
                return g;
            }
        }
        return null;
    }

    // ==================== NOTES TEXTUELLES ====================

    /** Ajoute une note textuelle libre. */
    public void addNote(String text) {
        if (text != null && !text.isBlank()) {
            notes.add(new NoteEntry(text.trim(), false));
        }
    }

    /** Coche ou décoche une note textuelle libre. */
    public void toggleNote(int index) {
        if (index >= 0 && index < notes.size()) {
            NoteEntry old = notes.get(index);
            notes.set(index, new NoteEntry(old.text(), !old.done()));
        }
    }

    /** Supprime une note textuelle libre. */
    public void removeNote(int index) {
        if (index >= 0 && index < notes.size()) {
            notes.remove(index);
        }
    }

    /** Liste immuable des notes textuelles libres. */
    public List<NoteEntry> notes() {
        return Collections.unmodifiableList(notes);
    }

    /** Vide entièrement les Notes. */
    public void clear() {
        notes.clear();
        goals.clear();
        completedKeys.clear();
        expanded.clear();
    }

    public boolean isEmpty() {
        return goals.isEmpty() && notes.isEmpty();
    }

    // ==================== DÉPLIAGE ====================

    /** Déplie ou replie la branche de la ligne {@code key}. */
    public void toggleExpanded(String key) {
        if (key != null && !expanded.remove(key)) {
            expanded.add(key);
        }
    }

    // ==================== PROGRESSION ====================

    /**
     * Met à jour le suivi avec l'inventaire du joueur et renvoie les objets des lignes toujours visibles qui
     * viennent d'atteindre leur quantité (pour le tintement).
     */
    public List<String> updateInventory(Map<String, Integer> currentInventory) {
        inventory.clear();
        if (currentInventory != null) {
            inventory.putAll(currentInventory);
        }
        return refreshCompletion();
    }

    private List<String> refreshCompletion() {
        List<String> newly = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Line l : lines(1)) {
            seen.add(l.key());
            if (l.completed()) {
                if (completedKeys.add(l.key())) {
                    newly.add(l.itemId());
                }
            } else {
                completedKeys.remove(l.key());
            }
        }
        completedKeys.retainAll(seen);
        return Collections.unmodifiableList(newly);
    }

    private int have(List<String> alternatives) {
        int total = 0;
        for (String alt : alternatives) {
            total += Math.max(0, inventory.getOrDefault(alt, 0));
        }
        return total;
    }

    /**
     * Lignes des objectifs à afficher : les arbres de fabrication d'abord (objet principal puis ingrédients), puis
     * les objets libres. Le premier niveau d'ingrédients est toujours présent ; les niveaux suivants, jusqu'à
     * {@code maxDepth}, seulement pour les branches dépliées.
     *
     * @param maxDepth nombre de niveaux d'ingrédients autorisés (1 = premier niveau seul)
     */
    public List<Line> lines(int maxDepth) {
        int depthLimit = Math.max(1, maxDepth);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < goals.size(); i++) {
            Goal g = goals.get(i);
            if (!g.craft) {
                continue;
            }
            int cur = have(List.of(g.itemId));
            out.add(new Line(g.key(), i, 0, LineKind.GOAL, g.itemId, cur, g.count, cur >= g.count, false, false));
            if (g.tree != null) {
                addChildren(out, g.tree, g.key(), i, 1, depthLimit);
            }
        }
        for (int i = 0; i < goals.size(); i++) {
            Goal g = goals.get(i);
            if (!g.craft) {
                int cur = have(List.of(g.itemId));
                out.add(new Line(g.key(), i, 0, LineKind.ITEM, g.itemId, cur, g.count, cur >= g.count, false, false));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private void addChildren(List<Line> out, TreeNode parent, String parentKey, int goalIndex, int depth, int depthLimit) {
        for (int c = 0; c < parent.children().size(); c++) {
            TreeNode n = parent.children().get(c);
            String key = parentKey + "/" + c;
            boolean expandable = !n.children().isEmpty() && depth < depthLimit;
            boolean open = expandable && expanded.contains(key);
            int cur = have(n.alternatives());
            out.add(new Line(key, goalIndex, depth, LineKind.INGREDIENT, n.itemId(), cur, n.needed(), cur >= n.needed(),
                    expandable, open));
            if (open) {
                addChildren(out, n, key, goalIndex, depth + 1, depthLimit);
            }
        }
    }

    /** Progression à plat des objectifs (objets principaux et objets libres), dans l'ordre des {@link #lines}. */
    public List<EntryProgress> entries() {
        List<EntryProgress> list = new ArrayList<>();
        for (Line l : lines(1)) {
            if (l.depth() == 0) {
                double ratio = l.required() <= 0 ? 1.0 : Math.min(1.0, (double) l.current() / l.required());
                list.add(new EntryProgress(l.itemId(), l.current(), l.required(), ratio, l.completed()));
            }
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Vrai si chaque objectif est prêt : objet libre réuni ; objectif de fabrication dont l'objet est déjà là ou
     * dont tous les ingrédients du premier niveau sont réunis.
     */
    public boolean isAllCompleted() {
        if (goals.isEmpty()) {
            return false;
        }
        List<Line> all = lines(1);
        for (int i = 0; i < goals.size(); i++) {
            boolean main = false;
            boolean ingredientsOk = true;
            boolean hasIngredients = false;
            for (Line l : all) {
                if (l.goalIndex() != i) {
                    continue;
                }
                if (l.depth() == 0) {
                    main = l.completed();
                } else {
                    hasIngredients = true;
                    ingredientsOk &= l.completed();
                }
            }
            if (!main && !(hasIngredients && ingredientsOk)) {
                return false;
            }
        }
        return true;
    }

    // ==================== SAUVEGARDE ====================

    /**
     * Objectif tel qu'il est enregistré : arbre de fabrication ({@code craft}, avec ses choix de recettes) ou objet
     * libre. L'arbre lui-même n'est pas enregistré : il est recalculé à la relecture.
     */
    public record SavedGoal(boolean craft, int count, String itemId, Map<String, String> preferred) {
        public SavedGoal {
            Objects.requireNonNull(itemId, "itemId");
            preferred = preferred == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(preferred));
        }
    }

    /** Objectifs dans l'ordre d'affichage, pour l'enregistrement. */
    public List<SavedGoal> savedGoals() {
        List<SavedGoal> out = new ArrayList<>(goals.size());
        for (Goal g : goals) {
            out.add(new SavedGoal(g.craft, g.count, g.itemId, g.preferred));
        }
        return Collections.unmodifiableList(out);
    }

    /** Ajoute un objectif relu (quantité inférieure à 1 ou objet vide : ignoré). */
    public void addSavedGoal(SavedGoal goal) {
        if (goal == null || goal.count() < 1 || goal.itemId().isBlank()) {
            return;
        }
        if (goal.craft()) {
            pinCraft(goal.itemId(), goal.count(), goal.preferred(), null);
        } else {
            pinOrIncrementItem(goal.itemId(), goal.count());
        }
    }

    /**
     * Objectifs sous forme de texte, une ligne par objectif : {@code C<TAB>quantité<TAB>objet<TAB>objet=recette;...}
     * pour un arbre, {@code I<TAB>quantité<TAB>objet} pour un objet libre. Les arbres sont recalculés à la relecture.
     */
    public String serializeGoals() {
        StringBuilder sb = new StringBuilder();
        for (Goal g : goals) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(g.craft ? 'C' : 'I').append('\t').append(g.count).append('\t').append(g.itemId);
            if (g.craft) {
                sb.append('\t');
                boolean first = true;
                for (Map.Entry<String, String> e : g.preferred.entrySet()) {
                    if (!first) {
                        sb.append(';');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
            }
        }
        return sb.toString();
    }

    /** Relit les objectifs écrits par {@link #serializeGoals()} (lignes illisibles ignorées). */
    public void loadGoals(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String row : text.split("\n")) {
            String[] f = row.split("\t", -1);
            if (f.length < 3 || f[2].isBlank()) {
                continue;
            }
            int count;
            try {
                count = Integer.parseInt(f[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (count < 1) {
                continue;
            }
            if ("I".equals(f[0])) {
                pinOrIncrementItem(f[2], count);
            } else if ("C".equals(f[0])) {
                Map<String, String> pref = new LinkedHashMap<>();
                if (f.length > 3 && !f[3].isBlank()) {
                    for (String kv : f[3].split(";")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0 && eq < kv.length() - 1) {
                            pref.put(kv.substring(0, eq), kv.substring(eq + 1));
                        }
                    }
                }
                pinCraft(f[2], count, pref, null);
            }
        }
    }
}
