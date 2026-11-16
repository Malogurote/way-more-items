package waymoreitems.api;

import java.util.List;
import java.util.function.Supplier;

/**
 * Inscriptions offertes à une {@link WmiExtension}. Toutes les méthodes acceptent d'être appelées plusieurs fois.
 */
public interface WmiRegistration {
    /** Version de l'API à l'exécution (celle de WMI installé), au format de {@link WmiApi#VERSION}. */
    String apiVersion();

    /**
     * Inscrit ou remplace la mise en page d'un type de recette. Équivaut au fichier
     * {@code assets/<modid>/wmi_layouts/<type>.json}, qui prime s'il existe (un pack de ressources peut
     * ainsi corriger une mise en page sans code).
     */
    void addLayout(RecipeLayoutSpec layout);

    /**
     * Inscrit un fournisseur de recettes que WMI ne sait pas lire seul (multiblocs, transformations dans le
     * monde, procédés calculés). Il est rappelé à chaque réindexation, sur le fil du client ; une exception
     * est journalisée et n'interrompt pas l'indexation.
     */
    void addRecipes(Supplier<List<WmiRecipe>> supplier);

    /**
     * Déclare un genre d'ingrédient autre que les objets et les fluides (gaz, essence...), utilisable ensuite
     * dans les clés {@code "<genre>@<identifiant>"} de {@link WmiIngredient}.
     */
    void addIngredientKind(WmiIngredient.Kind kind);

    /**
     * Fiche d'information d'un ingrédient (objet, fluide...), affichée dans ses recettes : chaque ligne est une
     * clé de traduction (ou un texte tel quel si la clé n'existe pas).
     *
     * @param ingredientKey clé de l'ingrédient (identifiant d'objet, ou {@code "fluid@<id>"}...)
     */
    void addInfo(String ingredientKey, List<String> lines);

    /**
     * Arbre de fabrication : les recettes de ce type ne sont jamais choisies comme étape (par exemple un
     * recyclage ou une conversion réversible qui créerait des boucles). Elles restent visibles ailleurs.
     */
    void excludeFromCraftTree(String recipeTypeId);
}
