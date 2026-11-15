package waymoreitems.api;

import waymoreitems.core.RecipeEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registre public de Way More Items (API développeur, inspirée de la simplicité de JEI).
 *
 * <p>Un mod tiers peut enrichir Way More Items de trois manières :</p>
 * <ol>
 *   <li><b>Sans aucun code (Niveau 0)</b> : WMI lit automatiquement {@code Recipe#display()} de Minecraft 26.3.</li>
 *   <li><b>Par simple JSON (Niveau 1)</b> : déposer {@code assets/<modid>/wmi_layouts/<type>.json}.</li>
 *   <li><b>Par API Java (Niveau 2)</b> : appeler {@link #registerLayout(RecipeLayoutSpec)} ou {@link #registerExtractor(CustomRecipeExtractor)}.</li>
 * </ol>
 */
public final class WmiRegistry {
    private static final Map<String, RecipeLayoutSpec> LAYOUTS = new LinkedHashMap<>();
    private static final List<CustomRecipeExtractor> EXTRACTORS = new CopyOnWriteArrayList<>();

    static {
        registerBuiltInLayouts();
    }

    private WmiRegistry() {
    }

    /**
     * Extracteur permettant à un mod tiers de convertir ses propres recettes ou procédés spéciaux
     * (ex. multiblocs, transformations en monde, générateurs de fluides) en {@link RecipeEntry} pour WMI.
     */
    @FunctionalInterface
    public interface CustomRecipeExtractor {
        List<RecipeEntry> extractRecipes();
    }

    /**
     * Enregistre ou remplace la mise en page visuelle d'un type de recette.
     */
    public static synchronized void registerLayout(RecipeLayoutSpec spec) {
        Objects.requireNonNull(spec, "spec");
        LAYOUTS.put(spec.recipeTypeId(), spec);
    }

    /**
     * Enregistre un extracteur de recettes personnalisées.
     */
    public static void registerExtractor(CustomRecipeExtractor extractor) {
        Objects.requireNonNull(extractor, "extractor");
        EXTRACTORS.add(extractor);
    }

    public static synchronized RecipeLayoutSpec layout(String recipeTypeId) {
        return LAYOUTS.get(recipeTypeId);
    }

    public static synchronized Collection<RecipeLayoutSpec> allLayouts() {
        return Collections.unmodifiableCollection( new ArrayList<>(LAYOUTS.values()));
    }

    public static List<CustomRecipeExtractor> extractors() {
        return Collections.unmodifiableList(EXTRACTORS);
    }

    private static void registerBuiltInLayouts() {
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
