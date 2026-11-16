package waymoreitems.core;

import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiIngredient;
import waymoreitems.api.WmiRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Passage des recettes de l'API publique ({@link WmiRecipe}) aux recettes du noyau ({@link RecipeEntry}), Java pur.
 */
public final class ApiRecipes {
    /** Type des fiches d'information ({@code WmiRegistration.addInfo}) : l'ingrédient seul, suivi de ses lignes. */
    public static final String INFO_TYPE = "wmi:information";

    private ApiRecipes() {
    }

    /** Recettes converties et lignes de texte des cartes, par identifiant de recette. */
    public record Collected(List<RecipeEntry> recipes, Map<String, List<String>> notes, List<String> errors) {
    }

    /**
     * Interroge tous les fournisseurs, convertit leurs recettes et ajoute les fiches d'information. Une erreur
     * d'un fournisseur ou d'une recette est consignée dans {@link Collected#errors()} sans interrompre le reste.
     *
     * @param categoryName nom lisible d'un type de recette (traduction du titre de sa mise en page)
     */
    public static Collected collect(List<Supplier<List<WmiRecipe>>> suppliers, Map<String, List<String>> info,
                                    Function<String, String> categoryName) {
        List<RecipeEntry> out = new ArrayList<>();
        Map<String, List<String>> notes = new java.util.HashMap<>();
        List<String> errors = new ArrayList<>();
        for (Supplier<List<WmiRecipe>> s : suppliers) {
            List<WmiRecipe> recipes;
            try {
                recipes = s.get();
            } catch (RuntimeException e) {
                errors.add("supplier " + s + ": " + e);
                continue;
            }
            if (recipes == null) {
                continue;
            }
            for (WmiRecipe r : recipes) {
                try {
                    out.add(toEntry(r, categoryName));
                    if (!r.notes().isEmpty()) {
                        notes.put(r.id(), r.notes());
                    }
                } catch (RuntimeException e) {
                    errors.add((r == null ? "null" : r.id()) + ": " + e.getMessage());
                }
            }
        }
        for (Map.Entry<String, List<String>> e : new java.util.TreeMap<>(info).entrySet()) {
            String id = INFO_TYPE + "/" + e.getKey();
            out.add(new RecipeEntry(id, INFO_TYPE, categoryName.apply(INFO_TYPE), List.of(),
                    List.of(new RecipeEntry.IngredientSlot(List.of(e.getKey()), 1)), List.of(), List.of(), 0, 0, 0));
            notes.put(id, e.getValue());
        }
        return new Collected(out, notes, errors);
    }

    /** Convertit une recette de l'API ; les postes de travail manquants sont repris de la mise en page du type. */
    public static RecipeEntry toEntry(WmiRecipe r, Function<String, String> categoryName) {
        List<String> workstations = r.workstations();
        if (workstations.isEmpty()) {
            RecipeLayoutSpec layout = WmiRegistry.layout(r.typeId());
            if (layout != null) {
                workstations = layout.workstations();
            }
        }
        List<RecipeEntry.ItemAmount> outputs = new ArrayList<>(r.outputs().size());
        for (WmiRecipe.Output o : r.outputs()) {
            outputs.add(new RecipeEntry.ItemAmount(o.key(), o.amount()));
        }
        int energy = r.energyPerTick();
        return new RecipeEntry(r.id(), r.typeId(), categoryName.apply(r.typeId()), workstations,
                slots(r.inputs()), slots(r.catalysts()), outputs, r.durationTicks(), energy, r.totalEnergy());
    }

    private static List<RecipeEntry.IngredientSlot> slots(List<WmiIngredient> ingredients) {
        List<RecipeEntry.IngredientSlot> out = new ArrayList<>(ingredients.size());
        for (WmiIngredient i : ingredients) {
            out.add(new RecipeEntry.IngredientSlot(i.alternatives(), i.amount(), i.tagId()));
        }
        return out;
    }
}
