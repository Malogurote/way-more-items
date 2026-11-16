package waymoreitems.core;

import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiApi;
import waymoreitems.api.WmiIngredient;
import waymoreitems.api.WmiRecipe;
import waymoreitems.api.WmiRegistration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Registre interne de Way More Items, alimenté par les extensions des mods ({@link WmiRegistration}), les mises en
 * page JSON des ressources et les mises en page de Minecraft (Java pur).
 *
 * <p>Une mise en page JSON ({@code assets/<modid>/wmi_layouts/}) prime sur celle d'une extension, qui prime sur
 * celle de WMI : les mises en page JSON sont tenues à part et remplacées à chaque rechargement des ressources.</p>
 */
public final class WmiRegistry {
    private static final Map<String, RecipeLayoutSpec> LAYOUTS = new LinkedHashMap<>();
    private static volatile Map<String, RecipeLayoutSpec> jsonLayouts = Map.of();
    private static final List<Supplier<List<WmiRecipe>>> RECIPE_SUPPLIERS = new CopyOnWriteArrayList<>();
    private static final Map<String, List<String>> INFO = new ConcurrentHashMap<>();
    private static final Set<String> TREE_EXCLUDED = ConcurrentHashMap.newKeySet();

    static {
        registerBuiltInLayouts();
    }

    private WmiRegistry() {
    }

    /** Vue {@link WmiRegistration} remise aux extensions. */
    public static WmiRegistration registration() {
        return REGISTRATION;
    }

    private static final WmiRegistration REGISTRATION = new WmiRegistration() {
        @Override
        public String apiVersion() {
            return WmiApi.VERSION;
        }

        @Override
        public void addLayout(RecipeLayoutSpec layout) {
            registerLayout(layout);
        }

        @Override
        public void addRecipes(Supplier<List<WmiRecipe>> supplier) {
            RECIPE_SUPPLIERS.add(Objects.requireNonNull(supplier, "supplier"));
        }

        @Override
        public void addIngredientKind(WmiIngredient.Kind kind) {
            Objects.requireNonNull(kind, "kind");
            // Une seule table des genres : celle du noyau (IngredientRef), qui sert aussi aux fluides
            IngredientRef.registerKind(new IngredientRef.Kind(kind.kind(), kind.unit(), kind.bigUnit(), kind.bigFactor()));
        }

        @Override
        public void addInfo(String ingredientKey, List<String> lines) {
            Objects.requireNonNull(ingredientKey, "ingredientKey");
            Objects.requireNonNull(lines, "lines");
            INFO.merge(ingredientKey, List.copyOf(lines), (a, b) -> {
                List<String> all = new ArrayList<>(a);
                all.addAll(b);
                return List.copyOf(all);
            });
        }

        @Override
        public void excludeFromCraftTree(String recipeTypeId) {
            TREE_EXCLUDED.add(Objects.requireNonNull(recipeTypeId, "recipeTypeId"));
        }
    };

    /** Inscrit ou remplace la mise en page d'un type de recette (extension ou WMI). */
    public static synchronized void registerLayout(RecipeLayoutSpec spec) {
        Objects.requireNonNull(spec, "spec");
        LAYOUTS.put(spec.recipeTypeId(), spec);
    }

    /** Remplace l'ensemble des mises en page lues dans les ressources. */
    public static void setJsonLayouts(Map<String, RecipeLayoutSpec> layouts) {
        jsonLayouts = Map.copyOf(layouts);
    }

    /** Mise en page du type {@code recipeTypeId} : JSON, puis extension, puis WMI ; {@code null} si aucune. */
    public static synchronized RecipeLayoutSpec layout(String recipeTypeId) {
        RecipeLayoutSpec json = jsonLayouts.get(recipeTypeId);
        return json != null ? json : LAYOUTS.get(recipeTypeId);
    }

    public static synchronized Collection<RecipeLayoutSpec> allLayouts() {
        Map<String, RecipeLayoutSpec> all = new LinkedHashMap<>(LAYOUTS);
        all.putAll(jsonLayouts);
        return Collections.unmodifiableCollection(new ArrayList<>(all.values()));
    }

    /** Fournisseurs de recettes des extensions. */
    public static List<Supplier<List<WmiRecipe>>> recipeSuppliers() {
        return Collections.unmodifiableList(RECIPE_SUPPLIERS);
    }

    /**
     * Genre d'ingrédient déclaré ({@code "fluid"} d'office), lu dans la table du noyau ({@link IngredientRef#kindInfo}),
     * ou {@code null} (genre inconnu, ou objet : un objet n'a pas de genre au sens de l'API).
     */
    public static WmiIngredient.Kind ingredientKind(String kind) {
        IngredientRef.Kind k = IngredientRef.kindInfo(kind);
        if (k == null || WmiIngredient.ITEM.equals(k.kind())) {
            return null;
        }
        return new WmiIngredient.Kind(k.kind(), k.unit(), k.bigUnit(), k.bigFactor());
    }

    /** Lignes de la fiche d'information d'un ingrédient (vide si aucune). */
    public static List<String> info(String ingredientKey) {
        return ingredientKey == null ? List.of() : INFO.getOrDefault(ingredientKey, List.of());
    }

    /** Toutes les fiches d'information, par clé d'ingrédient. */
    public static Map<String, List<String>> allInfo() {
        return Map.copyOf(INFO);
    }

    /** Vrai si l'arbre de fabrication ne doit jamais choisir une recette de ce type. */
    public static boolean isExcludedFromCraftTree(String recipeTypeId) {
        return recipeTypeId != null && TREE_EXCLUDED.contains(recipeTypeId);
    }

    private static void registerBuiltInLayouts() {
        // Fiches d'information des extensions : l'ingrédient seul, ses lignes à droite
        registerLayout(RecipeLayoutSpec.builder(ApiRecipes.INFO_TYPE, "wmi.recipe.information")
                .size(150, 54)
                .inputSlot(0, 10, 18)
                .build());

        // Mises en page natives de Minecraft
        registerLayout(RecipeLayoutSpec.builder("minecraft:crafting", "container.crafting")
                .size(150, 64)
                .workstation("minecraft:crafting_table")
                .inputSlot(0, 10, 8).inputSlot(1, 28, 8).inputSlot(2, 46, 8)
                .inputSlot(3, 10, 26).inputSlot(4, 28, 26).inputSlot(5, 46, 26)
                .inputSlot(6, 10, 44).inputSlot(7, 28, 44).inputSlot(8, 46, 44)
                .arrow(74, 26)
                .outputSlot(0, 110, 26, true)
                .build());

        registerLayout(RecipeLayoutSpec.builder("minecraft:smelting", "container.furnace")
                .size(150, 64)
                .workstation("minecraft:furnace")
                .inputSlot(0, 28, 12)
                .flame(29, 32)
                .catalystSlot(0, 28, 46)
                .arrow(62, 26)
                .outputSlot(0, 104, 26, true)
                .build());

        registerLayout(RecipeLayoutSpec.builder("minecraft:blasting", "container.blast_furnace")
                .size(150, 64)
                .workstation("minecraft:blast_furnace")
                .inputSlot(0, 28, 12)
                .flame(29, 32)
                .catalystSlot(0, 28, 46)
                .arrow(62, 26)
                .outputSlot(0, 104, 26, true)
                .build());

        registerLayout(RecipeLayoutSpec.builder("minecraft:smoking", "container.smoker")
                .size(150, 64)
                .workstation("minecraft:smoker")
                .inputSlot(0, 28, 12)
                .flame(29, 32)
                .catalystSlot(0, 28, 46)
                .arrow(62, 26)
                .outputSlot(0, 104, 26, true)
                .build());

        registerLayout(RecipeLayoutSpec.builder("minecraft:stonecutting", "container.stonecutter")
                .size(150, 54)
                .workstation("minecraft:stonecutter")
                .inputSlot(0, 28, 18)
                .arrow(62, 18)
                .outputSlot(0, 104, 18, true)
                .build());

        registerLayout(RecipeLayoutSpec.builder("minecraft:smithing", "container.upgrade")
                .size(150, 54)
                .workstation("minecraft:smithing_table")
                .inputSlot(0, 10, 18)
                .inputSlot(1, 28, 18)
                .inputSlot(2, 46, 18)
                .arrow(74, 18)
                .outputSlot(0, 110, 18, true)
                .build());
    }
}
