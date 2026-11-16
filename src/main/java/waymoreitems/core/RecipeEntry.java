package waymoreitems.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Recette générique dans le graphe de Way More Items (Java pur, indépendant de Minecraft).
 * Représente aussi bien une recette de Minecraft que de n'importe quel autre mod.
 *
 * @param id              identifiant unique de la recette (ex. {@code "exemplemod:great_blast_furnace_steel_ingot"})
 * @param typeId          identifiant du procédé / type de recette (ex. {@code "exemplemod:great_blast_furnace"})
 * @param categoryName    nom lisible du procédé (ex. {@code "Grand haut-fourneau"})
 * @param workstations    identifiants des blocs ou machines qui exécutent ce procédé (ex. {@code ["exemplemod:great_blast_furnace_block"]})
 * @param inputs          ingrédients d'entrée (chaque ingrédient accepte un ou plusieurs objets équivalents, ex. étiquette)
 * @param catalysts       catalyseurs ou outils non consommés (ou consommés partiellement) associés à la recette
 * @param outputs         produits de sortie avec leur quantité
 * @param durationTicks   durée de transformation en ticks (0 si instantané, ex. établi)
 * @param euPerTick       consommation ou production en EU/t (0 si sans énergie)
 * @param totalEnergy     énergie totale en EU (0 si sans énergie)
 * @param inputCells      case de la grille d'artisanat 3 x 3 (0 à 8, ligne par ligne) de chaque ingrédient de
 *                        {@code inputs}, pour les recettes à forme ; vide : ingrédients posés à la suite
 */
public record RecipeEntry(
        String id,
        String typeId,
        String categoryName,
        List<String> workstations,
        List<IngredientSlot> inputs,
        List<IngredientSlot> catalysts,
        List<ItemAmount> outputs,
        int durationTicks,
        int euPerTick,
        long totalEnergy,
        List<Integer> inputCells
) {
    /** Type des pseudo-recettes « remplir un contenant » (seau vide + fluide -> seau plein). */
    public static final String FLUID_FILLING_TYPE = "wmi:fluid_filling";
    /** Type des pseudo-recettes « vider un contenant » (seau plein -> seau vide + fluide). */
    public static final String FLUID_EMPTYING_TYPE = "wmi:fluid_emptying";

    /** Vrai pour une pseudo-recette de remplissage ou de vidange de contenant, que l'arbre de fabrication ignore. */
    public boolean isFluidContainerRecipe() {
        return FLUID_FILLING_TYPE.equals(typeId) || FLUID_EMPTYING_TYPE.equals(typeId);
    }

    /** Largeur de la grille d'artisanat dans laquelle {@link #inputCells} sont numérotées. */
    public static final int GRID_WIDTH = 3;

    /** Recette sans forme : ingrédients posés à la suite. */
    public RecipeEntry(String id, String typeId, String categoryName, List<String> workstations,
                       List<IngredientSlot> inputs, List<IngredientSlot> catalysts, List<ItemAmount> outputs,
                       int durationTicks, int euPerTick, long totalEnergy) {
        this(id, typeId, categoryName, workstations, inputs, catalysts, outputs, durationTicks, euPerTick, totalEnergy, List.of());
    }

    public RecipeEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(categoryName, "categoryName");
        workstations = workstations == null ? List.of() : List.copyOf(workstations);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        catalysts = catalysts == null ? List.of() : List.copyOf(catalysts);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        if (durationTicks < 0) {
            throw new IllegalArgumentException("durationTicks < 0: " + durationTicks);
        }
        inputCells = inputCells == null ? List.of() : List.copyOf(inputCells);
        if (!inputCells.isEmpty()) {
            if (inputCells.size() != inputs.size() || new LinkedHashSet<>(inputCells).size() != inputCells.size()) {
                throw new IllegalArgumentException("inputCells : une case distincte par ingredient attendue");
            }
            for (int c : inputCells) {
                if (c < 0 || c >= GRID_WIDTH * GRID_WIDTH) {
                    throw new IllegalArgumentException("inputCells hors de la grille : " + c);
                }
            }
        }
    }

    /**
     * Cases de la grille 3 x 3 occupées par une recette à forme. {@code present} donne, ligne par ligne sur
     * {@code width} colonnes, les cases non vides de la forme (dans l'ordre où leurs ingrédients figurent dans
     * {@code inputs}). La forme est posée en haut à gauche, comme le fait le livre de recettes. Forme plus large ou plus
     * haute que la grille : liste vide (ingrédients à la suite).
     */
    public static List<Integer> shapedCells(int width, List<Boolean> present) {
        if (width < 1 || width > GRID_WIDTH || present == null || present.size() > width * GRID_WIDTH) {
            return List.of();
        }
        List<Integer> cells = new java.util.ArrayList<>();
        for (int i = 0; i < present.size(); i++) {
            if (Boolean.TRUE.equals(present.get(i))) {
                cells.add((i / width) * GRID_WIDTH + (i % width));
            }
        }
        return List.copyOf(cells);
    }

    /** Ingrédient affiché dans la case {@code cell} de la grille 3 x 3, ou {@code null} si la case est vide. */
    public IngredientSlot inputAtCell(int cell) {
        if (inputCells.isEmpty()) {
            return cell >= 0 && cell < inputs.size() ? inputs.get(cell) : null;
        }
        int j = inputCells.indexOf(cell);
        return j < 0 ? null : inputs.get(j);
    }

    /**
     * Une case d'ingrédient pouvant accepter plusieurs objets alternatifs (étiquette ou liste) en quantité donnée.
     *
     * @param alternatives identifiants des objets acceptés (dans l'ordre de préférence)
     * @param count        quantité requise dans cette case
     * @param tagId        identifiant de l'étiquette d'origine si l'ingrédient vient d'un tag (ou {@code ""})
     */
    public record IngredientSlot(List<String> alternatives, int count, String tagId) {
        public IngredientSlot(List<String> alternatives, int count) {
            this(alternatives, count, "");
        }

        /**
         * Case de fluide : {@code fluidIds} sont des identifiants de registre de fluides (sans genre), convertis en
         * clés {@link IngredientRef#fluidKey}, et {@code millibuckets} la quantité requise.
         */
        public static IngredientSlot fluids(List<String> fluidIds, int millibuckets, String tagId) {
            Objects.requireNonNull(fluidIds, "fluidIds");
            List<String> keys = new java.util.ArrayList<>(fluidIds.size());
            for (String f : fluidIds) {
                keys.add(IngredientRef.isFluid(f) ? f : IngredientRef.fluidKey(f));
            }
            return new IngredientSlot(keys, millibuckets, tagId);
        }

        /** Vrai si cette case accepte des fluides (genre de sa première alternative). */
        public boolean isFluid() {
            return IngredientRef.isFluid(alternatives.get(0));
        }

        public IngredientSlot {
            Objects.requireNonNull(alternatives, "alternatives");
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("alternatives ne peut pas etre vide");
            }
            if (count < 1) {
                throw new IllegalArgumentException("count < 1: " + count);
            }
            alternatives = List.copyOf(alternatives);
            tagId = tagId == null ? "" : (tagId.startsWith("#") ? tagId.substring(1) : tagId);
        }

        public boolean matches(String itemId) {
            return alternatives.contains(itemId);
        }
    }

    /**
     * Un objet et sa quantité (entrée consolidée ou résultat de recette).
     */
    public record ItemAmount(String itemId, int count) {
        public ItemAmount {
            Objects.requireNonNull(itemId, "itemId");
            if (count < 1) {
                throw new IllegalArgumentException("count < 1: " + count);
            }
        }

        /** Quantité de fluide : {@code fluidId} sans genre (ou clé déjà formée), {@code millibuckets} en mB. */
        public static ItemAmount fluid(String fluidId, int millibuckets) {
            Objects.requireNonNull(fluidId, "fluidId");
            return new ItemAmount(IngredientRef.isFluid(fluidId) ? fluidId : IngredientRef.fluidKey(fluidId), millibuckets);
        }

        /** Vrai si ce résultat est un fluide. */
        public boolean isFluid() {
            return IngredientRef.isFluid(itemId);
        }
    }

    /** Renvoie vrai si cette recette produit {@code itemId}. */
    public boolean produces(String itemId) {
        for (ItemAmount out : outputs) {
            if (out.itemId().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    /** Quantité totale de {@code itemId} produite par une exécution de cette recette. */
    public int outputCount(String itemId) {
        int total = 0;
        for (ItemAmount out : outputs) {
            if (out.itemId().equals(itemId)) {
                total += out.count();
            }
        }
        return total;
    }

    /** Renvoie vrai si {@code itemId} intervient comme ingrédient, catalyseur ou poste de travail. */
    public boolean uses(String itemId) {
        for (IngredientSlot slot : inputs) {
            if (slot.matches(itemId)) {
                return true;
            }
        }
        for (IngredientSlot slot : catalysts) {
            if (slot.matches(itemId)) {
                return true;
            }
        }
        return workstations.contains(itemId);
    }

    /** Ensemble de tous les objets acceptés en entrée (hors catalyseurs et postes). */
    public Set<String> allInputItemIds() {
        Set<String> set = new LinkedHashSet<>();
        for (IngredientSlot slot : inputs) {
            set.addAll(slot.alternatives());
        }
        return Collections.unmodifiableSet(set);
    }
}
