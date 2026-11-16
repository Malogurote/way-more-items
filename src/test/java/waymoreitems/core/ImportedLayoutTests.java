package waymoreitems.core;

import java.util.List;
import java.util.function.BiConsumer;
import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.RecipeLayoutSpec.SlotRole;

/** Vérifications de la disposition des recettes importées ({@link ImportedRecipeLayout}). */
final class ImportedLayoutTests {
    private ImportedLayoutTests() {
    }

    private static ImportedRecipeLayout.Slot in(int x, int y, int ing) {
        return new ImportedRecipeLayout.Slot(SlotRole.INPUT, x, y, ing);
    }

    private static ImportedRecipeLayout.Slot out(int x, int y, int ing) {
        return new ImportedRecipeLayout.Slot(SlotRole.OUTPUT, x, y, ing);
    }

    static void run(BiConsumer<String, Boolean> check) {
        // Grille 3 x 3 d'artisanat : neuf cases, trois remplies (diagonale), résultat à droite
        List<ImportedRecipeLayout.Slot> grid = new java.util.ArrayList<>();
        int[] ing = {0, -1, -1, -1, 1, -1, -1, -1, 2};
        for (int i = 0; i < 9; i++) {
            grid.add(in((i % 3) * 18, (i / 3) * 18, ing[i]));
        }
        grid.add(out(94, 18, 0));
        List<Integer> cells = ImportedRecipeLayout.gridCells(grid, 3);
        check.accept("import : cases de grille de la diagonale", cells.equals(List.of(0, 4, 8)));

        RecipeLayoutSpec spec = ImportedRecipeLayout.fit("minecraft:crafting", "Crafting", grid, cells);
        check.accept("import : grille 3 x 3 tient dans la carte", spec != null);
        if (spec != null) {
            long inputs = spec.slots().stream().filter(s -> s.role() == SlotRole.INPUT).count();
            check.accept("import : neuf cases d'entrée, vides comprises", inputs == 9);
            RecipeLayoutSpec.SlotPos first = spec.slots().get(0);
            check.accept("import : première case à la marge gauche", first.x() == 6 && first.index() == 0);
            RecipeLayoutSpec.SlotPos center = spec.slots().get(4);
            check.accept("import : case centrale désignée par sa case de grille", center.index() == 4);
            check.accept("import : case vide désignée hors des ingrédients", spec.slots().get(1).index() >= 1000);
            check.accept("import : flèche entre entrées et résultat", spec.arrows().size() == 1
                    && spec.arrows().get(0).x() > 6 + 54 && spec.arrows().get(0).x() + 24 < 6 + 94);
            // Recette sous forme de RecipeEntry : l'ingrédient 1 est lu dans la case 4
            RecipeEntry.IngredientSlot a = new RecipeEntry.IngredientSlot(List.of("m:a"), 1);
            RecipeEntry.IngredientSlot b = new RecipeEntry.IngredientSlot(List.of("m:b"), 1);
            RecipeEntry.IngredientSlot c = new RecipeEntry.IngredientSlot(List.of("m:c"), 1);
            RecipeEntry rec = new RecipeEntry("m:r", "minecraft:crafting", "Crafting", List.of(), List.of(a, b, c),
                    List.of(), List.of(new RecipeEntry.ItemAmount("m:o", 1)), 0, 0, 0, cells);
            check.accept("import : ingrédient lu à sa case", rec.inputAtCell(center.index()) == b
                    && rec.inputAtCell(spec.slots().get(1).index()) == null);
        }

        // Disposition irrégulière (machine) : pas de grille, ingrédients par indice
        List<ImportedRecipeLayout.Slot> machine = List.of(in(0, 0, 0), in(0, 40, 1), in(30, 20, 2), out(90, 20, 0));
        List<Integer> none = ImportedRecipeLayout.gridCells(machine, 3);
        check.accept("import : pas de grille pour une machine", none.isEmpty());
        RecipeLayoutSpec m = ImportedRecipeLayout.fit("mod:press", "Press", machine, none);
        check.accept("import : machine tient dans la carte", m != null && m.slots().get(2).index() == 2);

        // Plan trop grand : disposition générique
        List<ImportedRecipeLayout.Slot> wide = List.of(in(0, 0, 0), out(160, 0, 0));
        check.accept("import : plan trop large refusé", ImportedRecipeLayout.fit("mod:big", "Big", wide, List.of()) == null);
        List<ImportedRecipeLayout.Slot> tall = List.of(in(0, 0, 0), out(0, 60, 0));
        check.accept("import : plan trop haut refusé", ImportedRecipeLayout.fit("mod:big", "Big", tall, List.of()) == null);

        // Cache : même disposition, même objet
        ImportedRecipeLayout.Cache cache = new ImportedRecipeLayout.Cache();
        check.accept("import : mise en page partagée", cache.get("t", "T", machine, none) == cache.get("t", "T", machine, none));

        // Inventaire élargi par un panneau accolé à droite, pas par une zone éloignée ni une zone plus large
        int[] gui = {100, 50, 176, 166};
        int[] ext = ImportedRecipeLayout.extendWithAdjacent(gui,
                List.of(new int[] {277, 60, 24, 80}, new int[] {400, 60, 20, 20}, new int[] {0, 0, 500, 300}), 2);
        check.accept("import : panneau accolé élargit l'inventaire", ext[0] == 100 && ext[2] == 201);
        int[] chain = ImportedRecipeLayout.extendWithAdjacent(gui,
                List.of(new int[] {300, 60, 20, 20}, new int[] {276, 60, 24, 20}), 2);
        check.accept("import : panneaux accolés en chaîne", ext[1] == 50 && chain[0] + chain[2] == 320);
        int[] leftSide = ImportedRecipeLayout.extendWithAdjacent(gui, List.of(new int[] {70, 50, 30, 40}), 2);
        check.accept("import : panneau accolé à gauche", leftSide[0] == 70 && leftSide[2] == 206);
    }
}
