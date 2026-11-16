package waymoreitems.core;

import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Vérifications du noyau de Way More Items en Java pur, exécutées par JUnit 5 ({@link CoreTests}, une vérification
 * par cas) : {@code gradle test}.
 */
final class WmiTests {
    private static BiConsumer<String, Boolean> sink = (name, ok) -> { };

    private WmiTests() {
    }

    private static void check(String name, boolean ok) {
        sink.accept(name, ok);
    }

    private static List<CatalogItem> sampleItems() {
        return List.of(
                new CatalogItem("minecraft:iron_ore", "minecraft", "Minecraft", "Minerai de fer", "01_ores", 64, Set.of("c:ores/iron"), "Se trouve sous terre"),
                new CatalogItem("minecraft:raw_iron", "minecraft", "Minecraft", "Fer brut", "02_raw", 64, Set.of("c:raw_materials/iron"), ""),
                new CatalogItem("minecraft:iron_ingot", "minecraft", "Minecraft", "Lingot de fer", "03_ingots", 64, Set.of("c:ingots", "c:ingots/iron"), "Métal de base"),
                new CatalogItem("minecraft:coal", "minecraft", "Minecraft", "Charbon", "02_raw", 64, Set.of("minecraft:coals"), "Combustible 1600 ticks"),
                new CatalogItem("minecraft:charcoal", "minecraft", "Minecraft", "Charbon de bois", "02_raw", 64, Set.of("minecraft:coals"), "Combustible 1600 ticks"),
                new CatalogItem("minecraft:copper_ingot", "minecraft", "Minecraft", "Lingot de cuivre", "03_ingots", 64, Set.of("c:ingots", "c:ingots/copper"), ""),
                new CatalogItem("exemplemod:refined_iron_ingot", "exemplemod", "Exemple Mod", "Lingot de fer raffiné", "03_ingots", 64, Set.of("c:ingots", "c:ingots/refined_iron"), "Fer recuit pour machines"),
                new CatalogItem("exemplemod:coal_dust", "exemplemod", "Exemple Mod", "Poudre de charbon", "04_dusts", 64, Set.of("c:dusts", "c:dusts/coal"), "Obtenue au Macérateur"),
                new CatalogItem("exemplemod:steel_ingot", "exemplemod", "Exemple Mod", "Lingot d'acier", "03_ingots", 64, Set.of("c:ingots", "c:ingots/steel"), "Alliage haute résistance"),
                new CatalogItem("exemplemod:steel_block", "exemplemod", "Exemple Mod", "Bloc d'acier", "01_blocks", 64, Set.of("c:storage_blocks/steel"), ""),
                new CatalogItem("exemplemod:steel_plate", "exemplemod", "Exemple Mod", "Plaque d'acier", "05_plates", 64, Set.of("c:plates", "c:plates/steel"), "Pour cuve haute pression"),
                new CatalogItem("exemplemod:rubber", "exemplemod", "Exemple Mod", "Caoutchouc", "06_materials", 64, Set.of("c:rubber"), "Isolant électrique"),
                new CatalogItem("exemplemod:copper_cable", "exemplemod", "Exemple Mod", "Câble de cuivre", "07_cables", 64, Set.of("exemplemod:cables"), "32 EU/t, perte 0.2/bloc"),
                new CatalogItem("exemplemod:insulated_copper_cable", "exemplemod", "Exemple Mod", "Câble de cuivre isolé", "07_cables", 64, Set.of("exemplemod:cables"), "32 EU/t isolé"),
                new CatalogItem("exemplemod:electronic_circuit", "exemplemod", "Exemple Mod", "Circuit électronique", "08_components", 64, Set.of("c:circuits/basic"), "Composant de base"),
                new CatalogItem("exemplemod:macerator", "exemplemod", "Exemple Mod", "Macérateur", "09_machines", 64, Set.of(), "Broie les minerais en deux poudres"),
                new CatalogItem("exemplemod:compressor", "exemplemod", "Exemple Mod", "Compresseur", "09_machines", 64, Set.of(), "Compresse les lingots et poudres"),
                new CatalogItem("exemplemod:great_blast_furnace_block", "exemplemod", "Exemple Mod", "Bloc de grand haut-fourneau", "09_machines", 64, Set.of(), "Multibloc 3x3x4 sans énergie"),
                new CatalogItem("minecraft:redstone", "minecraft", "Minecraft", "Poudre de redstone", "04_dusts", 64, Set.of("c:dusts/redstone"), ""),
                new CatalogItem("minecraft:diamond_pickaxe", "minecraft", "Minecraft", "Pioche en diamant", "10_tools", 1, Set.of("minecraft:pickaxes"), "Outil non empilable")
        );
    }

    private static List<RecipeEntry> sampleRecipes() {
        return List.of(
                // Fonte : fer brut -> lingot de fer
                new RecipeEntry(
                        "minecraft:iron_ingot_from_smelting",
                        "minecraft:smelting",
                        "Four",
                        List.of("minecraft:furnace", "exemplemod:electric_furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:raw_iron"), 1)),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:coal", "minecraft:charcoal"), 1, "minecraft:coals")),
                        List.of(new RecipeEntry.ItemAmount("minecraft:iron_ingot", 1)),
                        200, 0, 0
                ),
                // Fonte : lingot de fer -> fer raffiné
                new RecipeEntry(
                        "exemplemod:refined_iron_smelting",
                        "minecraft:smelting",
                        "Four",
                        List.of("minecraft:furnace", "exemplemod:electric_furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:iron_ingot"), 1)),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:refined_iron_ingot", 1)),
                        200, 3, 600
                ),
                // Macérateur : charbon -> poudre de charbon
                new RecipeEntry(
                        "exemplemod:macerating_coal_dust",
                        "exemplemod:macerating",
                        "Macérateur",
                        List.of("exemplemod:macerator"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:coal"), 1)),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:coal_dust", 1)),
                        300, 2, 600
                ),
                // Grand haut-fourneau : 1 fer raffiné + 2 poudres de charbon -> 1 lingot d'acier
                new RecipeEntry(
                        "exemplemod:great_blast_furnace_steel_ingot",
                        "exemplemod:great_blast_furnace",
                        "Grand haut-fourneau",
                        List.of("exemplemod:great_blast_furnace_block"),
                        List.of(
                                new RecipeEntry.IngredientSlot(List.of("exemplemod:refined_iron_ingot"), 1),
                                new RecipeEntry.IngredientSlot(List.of("exemplemod:coal_dust"), 2)
                        ),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:coal", "minecraft:charcoal"), 1, "minecraft:coals")),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:steel_ingot", 1)),
                        1200, 0, 0
                ),
                // Cycle bloc d'acier <-> 9 lingots d'acier
                new RecipeEntry(
                        "exemplemod:steel_block_from_ingots",
                        "minecraft:crafting",
                        "Fabrication",
                        List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("exemplemod:steel_ingot"), 9)),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:steel_block", 1)),
                        0, 0, 0
                ),
                new RecipeEntry(
                        "exemplemod:steel_ingots_from_block",
                        "minecraft:crafting",
                        "Fabrication",
                        List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("exemplemod:steel_block"), 1)),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:steel_ingot", 9)),
                        0, 0, 0
                ),
                // Compresseur : 1 lingot d'acier -> 1 plaque d'acier
                new RecipeEntry(
                        "exemplemod:compressing_steel_plate",
                        "exemplemod:compressing",
                        "Compresseur",
                        List.of("exemplemod:compressor"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("exemplemod:steel_ingot"), 1)),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:steel_plate", 1)),
                        300, 2, 600
                ),
                // Câbles avec surplus (3 cuivres -> 6 câbles de cuivre)
                new RecipeEntry(
                        "exemplemod:copper_cable",
                        "minecraft:crafting",
                        "Fabrication",
                        List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:copper_ingot"), 3)),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:copper_cable", 6)),
                        0, 0, 0
                ),
                // Câble isolé : 1 câble de cuivre + 1 caoutchouc -> 1 câble isolé
                new RecipeEntry(
                        "exemplemod:insulated_copper_cable",
                        "minecraft:crafting",
                        "Fabrication",
                        List.of("minecraft:crafting_table"),
                        List.of(
                                new RecipeEntry.IngredientSlot(List.of("exemplemod:copper_cable"), 1),
                                new RecipeEntry.IngredientSlot(List.of("exemplemod:rubber"), 1)
                        ),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:insulated_copper_cable", 1)),
                        0, 0, 0
                ),
                // Circuit électronique : 6 câbles isolés + 2 redstones + 1 fer raffiné -> 1 circuit
                new RecipeEntry(
                        "exemplemod:electronic_circuit",
                        "minecraft:crafting",
                        "Fabrication",
                        List.of("minecraft:crafting_table"),
                        List.of(
                                new RecipeEntry.IngredientSlot(List.of("exemplemod:insulated_copper_cable"), 6),
                                new RecipeEntry.IngredientSlot(List.of("minecraft:redstone"), 2),
                                new RecipeEntry.IngredientSlot(List.of("exemplemod:refined_iron_ingot"), 1)
                        ),
                        List.of(),
                        List.of(new RecipeEntry.ItemAmount("exemplemod:electronic_circuit", 1)),
                        0, 0, 0
                )
        );
    }

    private static void testSearchAndFilters(RecipeIndex index) {
        java.util.Map<String, int[]> counts = index.countsByGroup(ns -> ns);
        check("comptes par onglet : la somme des objets vaut le total",
                counts.values().stream().mapToInt(c -> c[0]).sum() == index.allItems().size());
        check("comptes par onglet : un groupe unique regroupe tous les objets et toutes les recettes",
                index.countsByGroup(ns -> "x").get("x")[0] == index.allItems().size()
                        && index.countsByGroup(ns -> "x").get("x")[1] <= index.allRecipes().size());
        check("recherche vide renvoie tous les objets", index.search("").size() == index.allItems().size());
        check("recherche insensible aux accents (macerateur)",
                index.search("macerateur").stream().anyMatch(i -> i.id().equals("exemplemod:macerator")));
        check("recherche insensible a la casse (ACIER)",
                index.search("ACIER").size() == 3);
        check("recherche par nom : l'identifiant technique (anglais) n'est pas pris en compte",
                index.search("steel").isEmpty() && index.search("pickaxe").isEmpty());
        check("filtre @mod (@exemplemod)",
                index.search("@exemplemod").stream().allMatch(i -> i.namespace().equals("exemplemod")));
        check("filtre #tag (#c:ingots)",
                index.search("#c:ingots").size() == 4);
        check("filtre #tag specifique (#c:ingots/steel)",
                index.search("#c:ingots/steel").size() == 1
                        && index.search("#c:ingots/steel").get(0).id().equals("exemplemod:steel_ingot"));
        Map<String, Integer> fabInv = Map.of("minecraft:raw_iron", 4, "minecraft:coal", 4);
        check("mot-cle !fab : meme resultat que le filtre Fabricable, seul ou avec d'autres mots",
                index.search("!fab", fabInv, false, null).equals(index.search("", fabInv, true, null))
                        && index.search("acier !FAB", fabInv, false, null).equals(index.search("acier", fabInv, true, null))
                        && !index.search("!fab", fabInv, false, null).isEmpty()
                        && RecipeIndex.stripCraftableKeyword("!fab acier | !fab").equals("acier |")
                        && !RecipeIndex.hasCraftableKeyword("!fabrique"));
        check("filtre $infobulle ($isolant)",
                index.search("$isolant").size() == 1
                        && index.search("$isolant").get(0).id().equals("exemplemod:rubber"));
        check("filtre ^machine (^macerateur)",
                index.search("^macerateur").stream().map(CatalogItem::id).toList()
                        .containsAll(List.of("minecraft:coal", "exemplemod:coal_dust")));
        check("exclusion (-@minecraft)",
                index.search("#c:ingots -@minecraft").size() == 2);
        check("union OU (caoutchouc | pioche)",
                index.search("caoutchouc | pioche").size() == 2);

        // Touches R (recettes) et U (usages)
        check("touche R : lingot d'acier a 2 recettes (haut-fourneau et bloc)",
                index.recipesForOutput("exemplemod:steel_ingot").size() == 2);
        check("touche U : charbon sert dans fonte, macerateur et catalyseur haut-fourneau",
                index.recipesUsing("minecraft:coal").size() == 3);
        check("touche U sur une machine (Macerateur) renvoie ses recettes",
                index.recipesUsing("exemplemod:macerator").size() == 1);

        // Catalogue contextuel (machine ouverte + inventaire)
        List<CatalogItem> contextual = index.search(
                "",
                Map.of("minecraft:coal", 4),
                false,
                "exemplemod:macerator"
        );
        check("catalogue contextuel : avec du charbon et le Macerateur ouvert, poudre de charbon et charbon sont en tete",
                contextual.get(0).id().equals("minecraft:coal") || contextual.get(0).id().equals("exemplemod:coal_dust"));

        // Filtre « Que puis-je fabriquer ? »
        List<CatalogItem> craftable = index.search(
                "",
                Map.of("minecraft:copper_ingot", 3, "exemplemod:rubber", 6, "minecraft:redstone", 2, "minecraft:iron_ingot", 1),
                true,
                null
        );
        check("filtre fabriquable : circuit electronique est fabriquable en chaine",
                craftable.stream().anyMatch(i -> i.id().equals("exemplemod:electronic_circuit")));
        check("filtre fabriquable : plaque d'acier n'est pas fabriquable sans charbon",
                craftable.stream().noneMatch(i -> i.id().equals("exemplemod:steel_plate")));
    }

    private static void testCraftTreeAndCycles(RecipeIndex index) {
        // 1. Chaîne complète d'une plaque d'acier sans stock initial (doit ignorer la recette cyclique du bloc d'acier)
        CraftTreeCalculator.CraftPlan steelPlan = CraftTreeCalculator.plan(
                index,
                "exemplemod:steel_plate",
                4,
                Map.of(),
                Map.of()
        );
        check("arbre acier x4 : aucune boucle infinie, exige 4 fers bruts et 8 charbons",
                steelPlan.missingRaw().getOrDefault("minecraft:raw_iron", 0) == 4
                        && steelPlan.missingRaw().getOrDefault("minecraft:coal", 0) == 8);
        check("arbre acier x4 : machines intermediaires comptabilisees (4 compresseur, 4 haut-fourneau, 8 macerateur)",
                steelPlan.machineBatches().getOrDefault("exemplemod:compressing", 0) == 4
                        && steelPlan.machineBatches().getOrDefault("exemplemod:great_blast_furnace", 0) == 4
                        && steelPlan.machineBatches().getOrDefault("exemplemod:macerating", 0) == 8);
        check("arbre acier x4 : energie totale exacte",
                steelPlan.totalEnergyEu() == (4L * 600 + 8L * 600 + 4L * 600));

        // 2. Déduction du stock existant : si le joueur possède déjà 1 bloc d'acier, il doit l'utiliser !
        CraftTreeCalculator.CraftPlan fromBlockStock = CraftTreeCalculator.plan(
                index,
                "exemplemod:steel_plate",
                4,
                Map.of("exemplemod:steel_block", 1),
                Map.of()
        );
        check("arbre acier x4 avec 1 bloc d'acier en stock : decompresse le bloc, 0 minerai manquant",
                fromBlockStock.missingRaw().isEmpty()
                        && fromBlockStock.usedFromStock().getOrDefault("exemplemod:steel_block", 0) == 1);

        // 3. Réutilisation des surplus : 1 circuit demande 6 câbles isolés -> 6 câbles de cuivre (1 seul lot de 3 lingots de cuivre = 6 câbles)
        CraftTreeCalculator.CraftPlan circuitPlan = CraftTreeCalculator.plan(
                index,
                "exemplemod:electronic_circuit",
                1,
                Map.of("exemplemod:rubber", 2),
                Map.of()
        );
        check("arbre circuit x1 : 1 seul lot de cables de cuivre (3 lingots de cuivre) pour 6 cables isoles",
                circuitPlan.missingRaw().getOrDefault("minecraft:copper_ingot", 0) == 3);
        check("arbre circuit x1 : deduit les 2 caoutchoucs du stock, il en manque 4",
                circuitPlan.usedFromStock().getOrDefault("exemplemod:rubber", 0) == 2
                        && circuitPlan.missingRaw().getOrDefault("exemplemod:rubber", 0) == 4);
    }

    /**
     * Cristal récolté dans le monde (cas du certus d'AE2) : ses seules recettes partent de variantes décoratives de
     * son bloc, elles-mêmes faites de cristaux. L'arbre le traite en matière première au lieu de passer par le bloc.
     */
    private static void testRoundTripCrystal() {
        List<CatalogItem> items = List.of(
                new CatalogItem("t:crystal", "t", "T", "Cristal", "05_gems", 64, Set.of(), ""),
                new CatalogItem("t:block", "t", "T", "Bloc", "06_blocks", 64, Set.of(), ""),
                new CatalogItem("t:bricks", "t", "T", "Briques", "06_blocks", 64, Set.of(), ""),
                new CatalogItem("t:chiseled", "t", "T", "Bloc sculpté", "06_blocks", 64, Set.of(), ""),
                new CatalogItem("t:gadget", "t", "T", "Gadget", "08_components", 64, Set.of(), "")
        );
        List<RecipeEntry> recipes = List.of(
                craft("t:block", 1, "t:crystal", 4),
                craft("t:crystal_from_block", "t:crystal", 4, "t:block", 1),
                craft("t:bricks", 1, "t:block", 1),
                craft("t:chiseled", 1, "t:bricks", 1),
                craft("t:crystal_from_chiseled", "t:crystal", 4, "t:chiseled", 1),
                craft("t:gadget", 1, "t:crystal", 2)
        );
        RecipeIndex idx = new RecipeIndex(items, recipes);
        CraftTreeCalculator.CraftPlan plan = CraftTreeCalculator.plan(idx, "t:gadget", 1, java.util.Map.of(), java.util.Map.of());
        CraftTreeCalculator.CraftNode crystal = plan.root().steps().isEmpty() ? null : plan.root().steps().get(0);
        check("arbre : cristal tiré seulement de variantes de son bloc traité en matière première",
                crystal != null && crystal.recipe() == null && plan.missingRaw().getOrDefault("t:crystal", 0) == 2
                        && !plan.missingRaw().containsKey("t:block"));
        CraftTreeCalculator.CraftPlan bricks = CraftTreeCalculator.plan(idx, "t:bricks", 1, java.util.Map.of(), java.util.Map.of());
        check("arbre : les briques gardent leur recette (bloc, puis cristaux récoltés)",
                bricks.root().recipe() != null && bricks.missingRaw().getOrDefault("t:crystal", 0) == 4);
    }

    private static RecipeEntry craft(String id, int outCount, String input, int inCount) {
        return craft(id, id, outCount, input, inCount);
    }

    private static RecipeEntry craft(String id, String output, int outCount, String input, int inCount) {
        return new RecipeEntry(id, "minecraft:crafting_shaped", "Fabrication", List.of("minecraft:crafting_table"),
                List.of(new RecipeEntry.IngredientSlot(List.of(input), inCount)), List.of(),
                List.of(new RecipeEntry.ItemAmount(output, outCount)), 0, 0, 0);
    }

    /** L'arbre préfère le fer brut au four, écarte minerais en bloc et pépites, et traite la redstone en matière brute. */
    private static void testNaturalRecipeChoice() {
        List<CatalogItem> items = List.of(
                new CatalogItem("minecraft:deepslate_iron_ore", "minecraft", "Minecraft", "Minerai de fer des abîmes", "01_ores", 64, Set.of(), ""),
                new CatalogItem("minecraft:raw_iron", "minecraft", "Minecraft", "Fer brut", "02_raw", 64, Set.of(), ""),
                new CatalogItem("minecraft:iron_nugget", "minecraft", "Minecraft", "Pépite de fer", "02_raw", 64, Set.of(), ""),
                new CatalogItem("minecraft:iron_ingot", "minecraft", "Minecraft", "Lingot de fer", "03_ingots", 64, Set.of(), ""),
                new CatalogItem("minecraft:redstone_ore", "minecraft", "Minecraft", "Minerai de redstone", "01_ores", 64, Set.of(), ""),
                new CatalogItem("minecraft:redstone", "minecraft", "Minecraft", "Poudre de redstone", "04_dusts", 64, Set.of(), ""),
                new CatalogItem("test:gadget", "test", "Test", "Gadget", "08_components", 64, Set.of(), "")
        );
        List<RecipeEntry> recipes = List.of(
                new RecipeEntry("minecraft:iron_ingot_from_blasting_deepslate_iron_ore", "minecraft:blasting", "Haut fourneau", List.of("minecraft:blast_furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:deepslate_iron_ore"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:iron_ingot", 1)), 100, 0, 0),
                new RecipeEntry("minecraft:iron_ingot_from_blasting_raw_iron", "minecraft:blasting", "Haut fourneau", List.of("minecraft:blast_furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:raw_iron"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:iron_ingot", 1)), 100, 0, 0),
                new RecipeEntry("minecraft:iron_ingot_from_nuggets", "minecraft:crafting_shaped", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:iron_nugget"), 9)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:iron_ingot", 1)), 0, 0, 0),
                new RecipeEntry("minecraft:iron_ingot", "minecraft:smelting", "Four", List.of("minecraft:furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:raw_iron"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:iron_ingot", 1)), 200, 0, 0),
                new RecipeEntry("minecraft:redstone_from_blasting_redstone_ore", "minecraft:blasting", "Haut fourneau", List.of("minecraft:blast_furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:redstone_ore"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:redstone", 1)), 100, 0, 0),
                new RecipeEntry("test:gadget", "minecraft:crafting_shaped", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:iron_ingot"), 1),
                                new RecipeEntry.IngredientSlot(List.of("minecraft:redstone"), 2)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("test:gadget", 1)), 0, 0, 0)
        );
        RecipeIndex idx = new RecipeIndex(items, recipes);
        CraftTreeCalculator.CraftPlan plan = CraftTreeCalculator.plan(idx, "test:gadget", 1, java.util.Map.of(), java.util.Map.of());
        CraftTreeCalculator.CraftNode ingot = null;
        CraftTreeCalculator.CraftNode dust = null;
        for (CraftTreeCalculator.CraftNode child : plan.root().steps()) {
            if (child.itemId().equals("minecraft:iron_ingot")) {
                ingot = child;
            } else if (child.itemId().equals("minecraft:redstone")) {
                dust = child;
            }
        }
        check("arbre : lingot de fer par le four à partir du fer brut",
                ingot != null && ingot.recipe() != null && ingot.recipe().id().equals("minecraft:iron_ingot"));
        check("arbre : ni minerai en bloc ni pépites dans les matières à réunir",
                !plan.missingRaw().containsKey("minecraft:deepslate_iron_ore") && !plan.missingRaw().containsKey("minecraft:iron_nugget")
                        && plan.missingRaw().getOrDefault("minecraft:raw_iron", 0) == 1);
        check("arbre : poudre de redstone traitée en matière brute (minée directement)",
                dust != null && dust.recipe() == null && plan.missingRaw().getOrDefault("minecraft:redstone", 0) == 2);
        check("arbre : minerai de redstone absent du plan", !plan.missingRaw().containsKey("minecraft:redstone_ore"));

        // Cuivre : fondre le cuivre brut plutôt que décompresser un bloc ciré (bloc + rayon de miel -> 9 lingots)
        List<CatalogItem> cuItems = List.of(
                new CatalogItem("minecraft:raw_copper", "minecraft", "Minecraft", "Cuivre brut", "02_raw", 64, Set.of(), ""),
                new CatalogItem("minecraft:copper_ingot", "minecraft", "Minecraft", "Lingot de cuivre", "03_ingots", 64, Set.of(), ""),
                new CatalogItem("minecraft:copper_block", "minecraft", "Minecraft", "Bloc de cuivre", "01_blocks", 64, Set.of(), ""),
                new CatalogItem("minecraft:honeycomb", "minecraft", "Minecraft", "Rayon de miel", "02_raw", 64, Set.of(), ""),
                new CatalogItem("minecraft:waxed_copper_block", "minecraft", "Minecraft", "Bloc de cuivre ciré", "01_blocks", 64, Set.of(), "")
        );
        List<RecipeEntry> cuRecipes = List.of(
                new RecipeEntry("minecraft:copper_ingot_from_waxed_copper_block", "minecraft:crafting_shapeless", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:waxed_copper_block"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:copper_ingot", 9)), 0, 0, 0),
                new RecipeEntry("minecraft:waxed_copper_block_from_honeycomb", "minecraft:crafting_shapeless", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:copper_block"), 1),
                                new RecipeEntry.IngredientSlot(List.of("minecraft:honeycomb"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:waxed_copper_block", 1)), 0, 0, 0),
                new RecipeEntry("minecraft:copper_ingot", "minecraft:smelting", "Four", List.of("minecraft:furnace"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:raw_copper"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("minecraft:copper_ingot", 1)), 200, 0, 0)
        );
        CraftTreeCalculator.CraftPlan cuPlan = CraftTreeCalculator.plan(new RecipeIndex(cuItems, cuRecipes),
                "minecraft:copper_ingot", 3, java.util.Map.of(), java.util.Map.of());
        check("arbre : lingot de cuivre fondu à partir du cuivre brut",
                cuPlan.root().recipe() != null && cuPlan.root().recipe().id().equals("minecraft:copper_ingot")
                        && cuPlan.missingRaw().getOrDefault("minecraft:raw_copper", 0) == 3
                        && !cuPlan.missingRaw().containsKey("minecraft:honeycomb"));

        // Teinture et lavage : câble bleu = 8 câbles fluix + teinture ; câble fluix = 4 câbles fins, ou lavage
        // de 8 câbles teints (n'importe quelle couleur) avec un seau d'eau. Le lavage ne doit pas reprendre le
        // câble bleu visé (ni son surplus) : l'arbre passe par les câbles fins.
        List<CatalogItem> cabItems = List.of(
                new CatalogItem("demo:blue_cable", "demo", "Demo", "Câble bleu", "07_cables", 64, Set.of("demo:colored_cables"), ""),
                new CatalogItem("demo:red_cable", "demo", "Demo", "Câble rouge", "07_cables", 64, Set.of("demo:colored_cables"), ""),
                new CatalogItem("demo:fluix_cable", "demo", "Demo", "Câble fluix", "07_cables", 64, Set.of(), ""),
                new CatalogItem("demo:thin_cable", "demo", "Demo", "Câble fin", "07_cables", 64, Set.of(), ""),
                new CatalogItem("minecraft:blue_dye", "minecraft", "Minecraft", "Teinture bleue", "02_raw", 64, Set.of(), ""),
                new CatalogItem("minecraft:water_bucket", "minecraft", "Minecraft", "Seau d'eau", "02_raw", 1, Set.of(), "")
        );
        List<String> colored = List.of("demo:blue_cable", "demo:red_cable");
        List<RecipeEntry> cabRecipes = List.of(
                new RecipeEntry("demo:blue_cable", "minecraft:crafting_shaped", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("demo:fluix_cable"), 8),
                                new RecipeEntry.IngredientSlot(List.of("minecraft:blue_dye"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("demo:blue_cable", 8)), 0, 0, 0),
                new RecipeEntry("demo:red_cable", "minecraft:crafting_shaped", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("demo:fluix_cable"), 8)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("demo:red_cable", 8)), 0, 0, 0),
                new RecipeEntry("demo:fluix_cable_wash", "minecraft:crafting_shaped", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(colored, 8, "demo:colored_cables"),
                                new RecipeEntry.IngredientSlot(List.of("minecraft:water_bucket"), 1)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("demo:fluix_cable", 8)), 0, 0, 0),
                new RecipeEntry("demo:fluix_cable", "minecraft:crafting_shapeless", "Fabrication", List.of("minecraft:crafting_table"),
                        List.of(new RecipeEntry.IngredientSlot(List.of("demo:thin_cable"), 4)), List.of(),
                        List.of(new RecipeEntry.ItemAmount("demo:fluix_cable", 1)), 0, 0, 0)
        );
        CraftTreeCalculator.CraftPlan cabPlan = CraftTreeCalculator.plan(new RecipeIndex(cabItems, cabRecipes),
                "demo:blue_cable", 10, java.util.Map.of(), java.util.Map.of());
        CraftTreeCalculator.CraftNode fluix = cabPlan.root().steps().get(0);
        check("arbre : le cable fluix n'est pas obtenu en lavant des cables teints (pas de boucle)",
                fluix.itemId().equals("demo:fluix_cable") && fluix.recipe() != null
                        && fluix.recipe().id().equals("demo:fluix_cable")
                        && cabPlan.missingRaw().getOrDefault("demo:thin_cable", 0) == 64
                        && !cabPlan.missingRaw().containsKey("minecraft:water_bucket"));
    }

    private static void testPinnedChecklist(RecipeIndex index) {
        CraftTreeCalculator.CraftPlan plan = CraftTreeCalculator.plan(
                index,
                "exemplemod:steel_plate",
                2,
                Map.of(),
                Map.of()
        );
        PinnedChecklist hud = new PinnedChecklist();
        hud.useIndex(index);
        hud.pinPlan("exemplemod:steel_plate", 2, plan);
        List<PinnedChecklist.Line> l1 = hud.lines(1);
        check("notes : arbre = objet principal puis premier niveau (lingot d'acier x2), non depliable a 1 niveau",
                !hud.isEmpty() && l1.size() == 2
                        && l1.get(0).kind() == PinnedChecklist.LineKind.GOAL && l1.get(0).required() == 2
                        && l1.get(1).itemId().equals("exemplemod:steel_ingot") && l1.get(1).required() == 2
                        && l1.get(1).depth() == 1 && !l1.get(1).expandable());

        // Profondeur 3 : la branche du lingot d'acier se déplie (fer raffiné x2, poudre de charbon x4)
        String ingotKey = l1.get(1).key();
        check("notes : branche depliable quand la profondeur le permet", hud.lines(3).get(1).expandable()
                && !hud.lines(3).get(1).expanded() && hud.lines(3).size() == 2);
        hud.toggleExpanded(ingotKey);
        List<PinnedChecklist.Line> l3 = hud.lines(3);
        Map<String, Integer> depth2 = new LinkedHashMap<>();
        for (PinnedChecklist.Line l : l3) {
            if (l.depth() == 2) {
                depth2.put(l.itemId(), l.required());
            }
        }
        check("notes : sous-ingredients en quantites totales (fer raffine 2, poudre de charbon 4)",
                depth2.getOrDefault("exemplemod:refined_iron_ingot", 0) == 2
                        && depth2.getOrDefault("exemplemod:coal_dust", 0) == 4 && depth2.size() == 2);
        check("notes : profondeur 1 ignore les branches depliees", hud.lines(1).size() == 2);

        // Collecte : tintement une seule fois quand le premier niveau est réuni
        List<String> ding1 = hud.updateInventory(Map.of("exemplemod:steel_ingot", 1));
        List<String> ding2 = hud.updateInventory(Map.of("exemplemod:steel_ingot", 2));
        List<String> ding3 = hud.updateInventory(Map.of("exemplemod:steel_ingot", 3));
        check("notes : tintement quand le lingot d'acier atteint 2/2, une seule fois",
                ding1.isEmpty() && ding2.equals(List.of("exemplemod:steel_ingot")) && ding3.isEmpty());
        check("notes : objectif pret quand tout le premier niveau est reuni", hud.isAllCompleted());

        // Second épinglage du même objet : quantité cumulée et arbre recalculé
        hud.pinPlan("exemplemod:steel_plate", 1, plan);
        check("notes : second epinglage cumule (3 plaques, 3 lingots)", hud.goalCount() == 1
                && hud.lines(1).get(0).required() == 3 && hud.lines(1).get(1).required() == 3);

        // Objet libre : après les arbres
        hud.pinOrIncrementItem("minecraft:torch", 16);
        hud.pinOrIncrementItem("minecraft:torch", 4);
        List<PinnedChecklist.Line> withFree = hud.lines(1);
        PinnedChecklist.Line last = withFree.get(withFree.size() - 1);
        check("notes : objet libre apres les arbres, quantites cumulees (20)",
                last.kind() == PinnedChecklist.LineKind.ITEM && last.required() == 20 && hud.goalCount() == 2);

        // Sauvegarde et relecture
        PinnedChecklist reloaded = new PinnedChecklist();
        reloaded.loadGoals(hud.serializeGoals());
        check("notes : objectif relu sans arbre tant que l'index manque", reloaded.lines(1).size() == 2);
        reloaded.useIndex(index);
        check("notes : sauvegarde relue a l'identique (arbre recalcule)",
                reloaded.serializeGoals().equals(hud.serializeGoals()) && reloaded.lines(1).size() == withFree.size());
        reloaded.loadGoals("X\tabc\n\tI\t\nI\t0\tminecraft:dirt");
        check("notes : lignes de sauvegarde illisibles ignorees", reloaded.goalCount() == 2);

        hud.unpinItem("minecraft:torch");
        hud.removeGoal(0);
        check("notes : objectifs retires", hud.goalCount() == 0 && hud.isEmpty());

        // Notes textuelles libres directement en jeu
        hud.addNote("Construire le haut fourneau");
        check("post-it : note textuelle libre ajoutee", hud.notes().size() == 1 && !hud.notes().get(0).done());
        hud.toggleNote(0);
        check("post-it : note textuelle libre cochee", hud.notes().get(0).done());
        hud.removeNote(0);
        check("post-it : note textuelle libre supprimee", hud.notes().isEmpty());
    }

    private static void testCommandPalette(RecipeIndex index) {
        CommandPalette.PaletteResult night = CommandPalette.evaluate(index, "nuit");
        check("palette : 'nuit' declenche l'action TIME_NIGHT",
                night.kind() == CommandPalette.IntentKind.EXECUTE_ACTION
                        && night.action() == CommandPalette.WorldAction.TIME_NIGHT);

        CommandPalette.PaletteResult creative = CommandPalette.evaluate(index, "créatif");
        check("palette : 'créatif' (avec accent) declenche MODE_CREATIVE",
                creative.kind() == CommandPalette.IntentKind.EXECUTE_ACTION
                        && creative.action() == CommandPalette.WorldAction.MODE_CREATIVE);

        CommandPalette.PaletteResult give64 = CommandPalette.evaluate(index, "64 acier");
        check("palette : '64 acier' declenche GIVE_ITEM x64 sur les objets d'acier",
                give64.kind() == CommandPalette.IntentKind.GIVE_ITEM
                        && give64.giveCount() == 64
                        && give64.matchingItems().size() == 3);

        CommandPalette.PaletteResult giveCmd = CommandPalette.evaluate(index, "give 16 macérateur");
        check("palette : 'give 16 macérateur' declenche GIVE_ITEM x16 sur le Macerateur",
                giveCmd.kind() == CommandPalette.IntentKind.GIVE_ITEM
                        && giveCmd.giveCount() == 16
                        && giveCmd.matchingItems().get(0).id().equals("exemplemod:macerator"));

        CommandPalette.PaletteResult search = CommandPalette.evaluate(index, "cuivre");
        check("palette : 'cuivre' cherche dans le catalogue",
                search.kind() == CommandPalette.IntentKind.SEARCH_ITEMS
                        && search.matchingItems().size() == 3);
    }

    private static void testInventorySorter() {
        List<InventorySorter.SlotStack> slots = new ArrayList<>();
        // Case 0 verrouillée (ex. épée ou pioche en barre rapide)
        InventorySorter.SlotStack pickaxe = new InventorySorter.SlotStack(
                "minecraft:diamond_pickaxe", "minecraft:diamond_pickaxe#dmg0", "minecraft", "10_tools", "Pioche en diamant", 1, 1);
        // Deux demi-piles d'acier dispersées (cases 1 et 4) + du fer (case 2) + case 3 verrouillée vide
        InventorySorter.SlotStack steelA = new InventorySorter.SlotStack(
                "exemplemod:steel_ingot", "exemplemod:steel_ingot", "exemplemod", "03_ingots", "Lingot d'acier", 20, 64);
        InventorySorter.SlotStack iron = new InventorySorter.SlotStack(
                "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft", "03_ingots", "Lingot de fer", 10, 64);
        InventorySorter.SlotStack steelB = new InventorySorter.SlotStack(
                "exemplemod:steel_ingot", "exemplemod:steel_ingot", "exemplemod", "03_ingots", "Lingot d'acier", 30, 64);

        slots.add(pickaxe); // 0 : verrouillée
        slots.add(steelA);  // 1 : libre
        slots.add(iron);    // 2 : libre
        slots.add(null);    // 3 : verrouillée (vide)
        slots.add(steelB);  // 4 : libre
        slots.add(null);    // 5 : libre

        List<InventorySorter.SlotStack> sorted = InventorySorter.sort(
                slots,
                Set.of(0, 3),
                InventorySorter.SortOrder.MOD_CATEGORY_NAME
        );

        check("tri : taille inchangee", sorted.size() == 6);
        check("tri : case 0 verrouillee intacte", sorted.get(0) == pickaxe);
        check("tri : case 3 verrouillee vide reste vide", sorted.get(3) == null);
        check("tri : minecraft trie avant les autres mods en case 1",
                sorted.get(1) != null && sorted.get(1).itemId().equals("minecraft:iron_ingot") && sorted.get(1).count() == 10);
        check("tri : les deux piles d'acier (20 + 30) sont fusionnees en 50 en case 2",
                sorted.get(2) != null && sorted.get(2).itemId().equals("exemplemod:steel_ingot") && sorted.get(2).count() == 50);
        check("tri : cases restantes vides", sorted.get(4) == null && sorted.get(5) == null);
    }

    /** Tri d'un conteneur : cases filtrées ou de capacité réduite exclues, aucune perte d'objet. */
    private static void testInventorySorterRestrictions() {
        InventorySorter.SlotStack potion = new InventorySorter.SlotStack(
                "minecraft:potion", "minecraft:potion#0", "minecraft", "02_equipment", "Potion", 1, 1);
        InventorySorter.SlotStack dirt = new InventorySorter.SlotStack(
                "minecraft:dirt", "minecraft:dirt#0", "minecraft", "01_blocks", "Terre", 10, 64);
        List<InventorySorter.SlotStack> slots = new ArrayList<>();
        slots.add(potion); // 0 : case réservée aux potions
        slots.add(null);   // 1 : case libre
        slots.add(dirt);   // 2 : case libre
        slots.add(null);   // 3 : case libre mais limitée à 16 objets
        InventorySorter.SlotCapacity rule = (i, c) -> switch (i) {
            case 0 -> c.itemId().equals("minecraft:potion") ? 1 : 0;
            case 3 -> 16;
            default -> 64;
        };
        Set<Integer> restricted = InventorySorter.restrictedSlots(slots, Set.of(), rule);
        check("tri conteneur : case filtree exclue", restricted.contains(0));
        check("tri conteneur : case de capacite reduite exclue", restricted.contains(3));
        check("tri conteneur : cases ordinaires triees", !restricted.contains(1) && !restricted.contains(2));
        check("tri conteneur : case verrouillee non examinee",
                !InventorySorter.restrictedSlots(slots, Set.of(0, 3), rule).contains(0));
        List<InventorySorter.SlotStack> sorted = InventorySorter.sort(slots, restricted, InventorySorter.SortOrder.NAME_ONLY);
        check("tri conteneur : la potion reste dans sa case", sorted.get(0) == potion && sorted.get(3) == null);
        check("tri conteneur : la terre remonte en premiere case libre", sorted.get(1) != null
                && sorted.get(1).itemId().equals("minecraft:dirt") && sorted.get(1).count() == 10 && sorted.get(2) == null);

        // Case contenant plus qu'une pile pleine (stockage d'un mod) : exclue, et jamais de pile perdue au regroupement
        InventorySorter.SlotStack bulk = new InventorySorter.SlotStack(
                "minecraft:cobblestone", "minecraft:cobblestone#0", "minecraft", "01_blocks", "Pierre", 500, 64);
        List<InventorySorter.SlotStack> big = new ArrayList<>();
        big.add(bulk);
        big.add(null);
        check("tri conteneur : case surchargee exclue",
                InventorySorter.restrictedSlots(big, Set.of(), (i, c) -> 1024).contains(0));
        List<InventorySorter.SlotStack> unchanged = InventorySorter.sort(big, Set.of(), InventorySorter.SortOrder.NAME_ONLY);
        check("tri : plus de piles que de cases libres, plage rendue telle quelle",
                unchanged.get(0) == bulk && unchanged.get(1) == null);
    }

    /** Réglages : lecture clé par clé avec repli, écriture atomique, copie de secours d'un fichier illisible. */
    private static void testSettingsFile() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("hudScale", "abc");
        p.setProperty("ratio", "NaN");
        p.setProperty("big", "9.5");
        p.setProperty("flag", "oui");
        p.setProperty("order", "NAME_ONLY");
        p.setProperty("badOrder", "XYZ");
        p.setProperty("savedNotes", "1Note faite\n0Note a faire");
        SettingsFile s = SettingsFile.of(p);
        check("reglages : nombre illisible = valeur par defaut de la cle seule", s.decimal("hudScale", 1.0, 0.75, 2.5) == 1.0);
        check("reglages : NaN refuse", s.decimal("ratio", -1) == -1);
        check("reglages : borne superieure appliquee", s.decimal("big", 1.0, 0.75, 2.5) == 2.5);
        check("reglages : booleen illisible = defaut", s.bool("flag", true) && !s.bool("flag", false));
        check("reglages : enumeration lue ou repli",
                s.enumValue("order", InventorySorter.SortOrder.class, InventorySorter.SortOrder.QUANTITY_DESC) == InventorySorter.SortOrder.NAME_ONLY
                        && s.enumValue("badOrder", InventorySorter.SortOrder.class, InventorySorter.SortOrder.QUANTITY_DESC) == InventorySorter.SortOrder.QUANTITY_DESC);
        check("reglages : notes lues malgre les valeurs illisibles", s.string("savedNotes", "").startsWith("1Note faite"));
        check("reglages : entier borne", SettingsFile.empty().integer("absent", 18, 12, 32) == 18);

        java.nio.file.Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("wmi-settings");
            java.nio.file.Path f = dir.resolve("wmi-client.properties");
            SettingsFile.storeAtomic(f, p, "test");
            check("reglages : ecriture atomique sans fichier temporaire restant",
                    java.nio.file.Files.isRegularFile(f) && !java.nio.file.Files.exists(dir.resolve("wmi-client.properties.tmp")));
            SettingsFile back = SettingsFile.load(f);
            check("reglages : relecture fidele (notes sur plusieurs lignes)",
                    !back.wasUnreadable() && back.string("savedNotes", "").equals("1Note faite\n0Note a faire"));
            // Séquence \\u incomplète : Properties.load lève une exception, le fichier est sauvegardé à côté
            java.nio.file.Files.writeString(f, "savedNotes=0ma note\\u12", java.nio.charset.StandardCharsets.ISO_8859_1);
            SettingsFile broken = SettingsFile.load(f);
            check("reglages : fichier illisible signale et copie de secours creee", broken.wasUnreadable()
                    && java.nio.file.Files.isRegularFile(dir.resolve("wmi-client.properties" + SettingsFile.UNREADABLE_SUFFIX)));
            check("reglages : fichier illisible = valeurs par defaut", broken.decimal("hudScale", 1.0) == 1.0);
            check("reglages : fichier absent = valeurs par defaut, sans erreur",
                    !SettingsFile.load(dir.resolve("absent.properties")).wasUnreadable());
        } catch (java.io.IOException e) {
            check("reglages : entrees et sorties sur fichier temporaire (" + e + ")", false);
        } finally {
            if (dir != null) {
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path x : files.toList()) {
                        java.nio.file.Files.deleteIfExists(x);
                    }
                    java.nio.file.Files.deleteIfExists(dir);
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }

    /** Forme des recettes conservée : cases vides et largeur. */
    private static void testShapedRecipeCells() {
        // Pioche : « III », « .S. », « .S. »
        List<Integer> pick = RecipeEntry.shapedCells(3, List.of(true, true, true, false, true, false, false, true, false));
        check("forme : pioche, batons dans la colonne du milieu", pick.equals(List.of(0, 1, 2, 4, 7)));
        // Établi 2 x 2 : posé en haut à gauche de la grille 3 x 3, comme le livre de recettes
        check("forme : recette 2 x 2 sur deux lignes", RecipeEntry.shapedCells(2, List.of(true, true, true, true)).equals(List.of(0, 1, 3, 4)));
        check("forme : plus large que la grille, ingredients a la suite", RecipeEntry.shapedCells(4, List.of(true, true, true, true)).isEmpty());

        RecipeEntry.IngredientSlot ingot = new RecipeEntry.IngredientSlot(List.of("minecraft:iron_ingot"), 1);
        RecipeEntry.IngredientSlot stick = new RecipeEntry.IngredientSlot(List.of("minecraft:stick"), 1);
        RecipeEntry r = new RecipeEntry("minecraft:iron_pickaxe", "minecraft:crafting", "Fabrication", List.of(),
                List.of(ingot, ingot, ingot, stick, stick), List.of(),
                List.of(new RecipeEntry.ItemAmount("minecraft:iron_pickaxe", 1)), 0, 0, 0, pick);
        check("forme : case 4 = baton, case 3 vide, case 7 = baton",
                r.inputAtCell(4) == stick && r.inputAtCell(3) == null && r.inputAtCell(7) == stick && r.inputAtCell(8) == null);
        check("forme : les ingredients restent comptes tels quels", r.inputs().size() == 5 && r.allInputItemIds().size() == 2);
        RecipeEntry seq = new RecipeEntry("x:y", "minecraft:crafting", "Fabrication", List.of(), List.of(ingot, stick), List.of(),
                List.of(new RecipeEntry.ItemAmount("x:y", 1)), 0, 0, 0);
        check("forme : recette sans forme, ingredients a la suite", seq.inputAtCell(1) == stick && seq.inputAtCell(2) == null);
        boolean refused;
        try {
            new RecipeEntry("x:z", "minecraft:crafting", "Fabrication", List.of(), List.of(ingot, stick), List.of(),
                    List.of(new RecipeEntry.ItemAmount("x:z", 1)), 0, 0, 0, List.of(0, 0));
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("forme : deux ingredients dans la meme case refuses", refused);
    }

    /** MiniJson : profondeur d'imbrication bornée, erreur au lieu d'un débordement de pile. */
    private static void testMiniJsonDepth() {
        String ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH);
        boolean parsed;
        try {
            MiniJson.parse(ok);
            parsed = true;
        } catch (IllegalArgumentException e) {
            parsed = false;
        }
        check("json : " + MiniJson.MAX_DEPTH + " niveaux admis", parsed);
        String deep = "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000);
        String msg;
        try {
            MiniJson.parse(deep);
            msg = "";
        } catch (IllegalArgumentException e) {
            msg = e.getMessage();
        } catch (StackOverflowError e) {
            msg = "stack overflow";
        }
        check("json : imbrication excessive refusee par une erreur lisible", msg.contains("too_deep|" + MiniJson.MAX_DEPTH));
    }

    private static void testWmiRegistryLayouts() {
        // Un mod tiers inscrit sa mise en page : WMI la retrouve (WMI n'en déclare aucune pour d'autres mods)
        check("API WmiRegistry : aucune mise en page déclarée d'office pour un mod tiers",
                WmiRegistry.layout("exemplemod:grinding") == null);
        WmiRegistry.registerLayout(RecipeLayoutSpec.builder("exemplemod:grinding", "container.exemplemod.grinder")
                .workstation("exemplemod:grinder")
                .inputSlot(0, 28, 26)
                .arrow(62, 26)
                .outputSlot(0, 104, 26, true)
                .energyBar(true)
                .build());
        RecipeLayoutSpec spec = WmiRegistry.layout("exemplemod:grinding");
        check("API WmiRegistry : mise en page d'un mod tiers inscrite et retrouvée",
                spec != null && spec.showEnergyBar() && spec.slots().size() == 2
                        && spec.workstations().contains("exemplemod:grinder"));
        check("API WmiRegistry : mises en page de Minecraft présentes d'office",
                WmiRegistry.layout("minecraft:smelting") != null);
    }

    /** Bouton « + » des recettes : ingrédients épinglés dans les Notes. */
    private static void testPinRecipeInputs() {
        RecipeEntry rec = new RecipeEntry(
                "exemplemod:torch_like", "minecraft:crafting_shaped", "Artisanat", List.of("minecraft:crafting_table"),
                List.of(
                        new RecipeEntry.IngredientSlot(List.of("minecraft:coal", "minecraft:charcoal"), 1, "minecraft:coals"),
                        new RecipeEntry.IngredientSlot(List.of("minecraft:stick"), 1),
                        new RecipeEntry.IngredientSlot(List.of("minecraft:stick"), 1)),
                List.of(new RecipeEntry.IngredientSlot(List.of("minecraft:furnace"), 1)),
                List.of(new RecipeEntry.ItemAmount("minecraft:torch", 4)),
                0, 0, 0);
        PinnedChecklist notes = new PinnedChecklist();
        int added = notes.pinRecipeInputs(rec, 2);
        notes.updateInventory(Map.of("minecraft:charcoal", 5, "minecraft:stick", 1));
        Map<String, PinnedChecklist.Line> req = new LinkedHashMap<>();
        for (PinnedChecklist.Line l : notes.lines(1)) {
            req.put(l.itemId(), l);
        }
        check("bouton + : objectif = resultat (8 torches), deux ingredients", added == 2 && req.size() == 3
                && req.get("minecraft:torch").kind() == PinnedChecklist.LineKind.GOAL && req.get("minecraft:torch").required() == 8);
        check("bouton + : variantes de l'etiquette comptees ensemble (5 charbons de bois pour 2 charbons)",
                req.get("minecraft:coal").required() == 2 && req.get("minecraft:coal").current() == 5 && req.get("minecraft:coal").completed());
        check("bouton + : cases identiques cumulees (2 batons x 2 = 4), catalyseur ignore",
                req.get("minecraft:stick").required() == 4 && !req.containsKey("minecraft:furnace"));
        notes.pinRecipeInputs(rec, 1);
        int sticks = 0;
        for (PinnedChecklist.Line l : notes.lines(1)) {
            if (l.itemId().equals("minecraft:stick")) {
                sticks = l.required();
            }
        }
        check("bouton + une seconde fois : les quantites s'ajoutent (4 + 2)", sticks == 6 && notes.goalCount() == 1);
    }

    /** Palettes dérivées d'une couleur : style transparent conservé, contraste du texte. */
    private static void testThemePalette() {
        ThemePalette light = ThemePalette.fromBase(0xE8B4C0);
        ThemePalette dark = ThemePalette.fromBase(0x4A1624);
        check("palette : base claire = theme clair, base sombre = theme sombre", light.light() && !dark.light());
        check("palette : fonds translucides (alpha < 0xFF)", (light.panelBg() >>> 24) < 0xFF && (dark.panelBg() >>> 24) < 0xFF
                && (light.slotBg() >>> 24) < 0xFF && (dark.cardBg() >>> 24) < 0xFF);
        check("palette : texte fonce sur clair, clair sur sombre",
                ThemePalette.luminance(light.textMain()) < 0.3 && ThemePalette.luminance(dark.textMain()) > 0.8);
        float[] hsv = ThemePalette.rgbToHsv(0x3366CC);
        check("couleur : aller-retour TSV sans perte", ThemePalette.hsvToRgb(hsv[0], hsv[1], hsv[2]) == 0x3366CC);
    }

    /** Lance toutes les vérifications ; chacune est transmise à {@code check} avec son nom et son résultat. */
    static void runAll(BiConsumer<String, Boolean> check) {
        sink = check;
        RecipeIndex index = new RecipeIndex(sampleItems(), sampleRecipes());
        testSearchAndFilters(index);
        testCraftTreeAndCycles(index);
        testNaturalRecipeChoice();
        testRoundTripCrystal();
        testPinnedChecklist(index);
        testCommandPalette(index);
        testInventorySorter();
        testInventorySorterRestrictions();
        testSettingsFile();
        testMiniJsonDepth();
        testShapedRecipeCells();
        testWmiRegistryLayouts();
        testPinRecipeInputs();
        testThemePalette();
        GuiLayoutTests.run(WmiTests::check);
        LangTests.run(WmiTests::check);
        CoreLogicTests.run(WmiTests::check);
    }
}
