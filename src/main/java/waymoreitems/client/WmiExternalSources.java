package waymoreitems.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.core.RecipeEntry;

/**
 * Point d'accroche des sources externes de recettes et de zones d'écran (un autre navigateur de recettes installé à
 * côté de WMI). Aucune classe d'un autre mod ici : le greffon facultatif installe sa {@link Bridge} au démarrage ;
 * sans lui, toutes les méthodes rendent une valeur neutre.
 */
public final class WmiExternalSources {

    /** Recettes importées d'une source externe, avec la mise en page propre à certaines d'entre elles. */
    public record Imported(List<RecipeEntry> recipes, Map<String, RecipeLayoutSpec> layoutsByRecipeId) {
        public static final Imported EMPTY = new Imported(List.of(), Map.of());
    }

    /** Source externe (implantée par un greffon de compatibilité). */
    public interface Bridge {
        /** Recettes que WMI n'a pas lues lui-même ({@code knownRecipeIds} : identifiants déjà lus). Fil client. */
        Imported importRecipes(Set<String> knownRecipeIds);

        /** Zones qu'occupe l'écran en plus de son inventaire, en unités du jeu. */
        List<Rect2i> screenAreas(Screen screen);

        /** Clic gauche sur une zone cliquable de l'écran (flèche d'une machine) ; vrai s'il a été traité. */
        boolean clickArea(AbstractContainerScreen<?> screen, double mouseX, double mouseY);
    }

    private static volatile Bridge bridge;
    private static volatile Map<String, RecipeLayoutSpec> layouts = Map.of();

    private WmiExternalSources() {
    }

    public static void install(Bridge b) {
        bridge = b;
    }

    /** Source externe prête : l'index sera reconstruit pour y inclure ses recettes. */
    public static void sourceChanged() {
        WmiClientIndex.requestRebuild();
    }

    static Imported importRecipes(Set<String> knownRecipeIds) {
        Bridge b = bridge;
        if (b == null) {
            layouts = Map.of();
            return Imported.EMPTY;
        }
        Imported imported = b.importRecipes(knownRecipeIds);
        if (imported == null) {
            imported = Imported.EMPTY;
        }
        layouts = Map.copyOf(imported.layoutsByRecipeId());
        return imported;
    }

    /** Mise en page propre à une recette importée, ou {@code null}. */
    static RecipeLayoutSpec layoutFor(String recipeId) {
        return layouts.get(recipeId);
    }

    static List<Rect2i> screenAreas(Screen screen) {
        Bridge b = bridge;
        return b == null ? List.of() : b.screenAreas(screen);
    }

    static boolean clickArea(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        Bridge b = bridge;
        return b != null && b.clickArea(screen, mouseX, mouseY);
    }
}
