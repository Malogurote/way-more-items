package waymoreitems.core;

import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiApi;
import waymoreitems.api.WmiIngredient;
import waymoreitems.api.WmiRecipe;
import waymoreitems.api.WmiRegistration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Tests de l'API publique (module wmi-api) et de son raccordement au noyau. */
final class ApiTests {
    private static final Function<String, String> NAMES = t -> t;

    private ApiTests() {
    }

    static void run(BiConsumer<String, Boolean> check) {
        testVersions(check);
        testIngredientKeys(check);
        testRecipes(check);
        testJsonLayouts(check);
        testCollect(check);
        testCraftTreeExclusion(check);
    }

    private static void testVersions(BiConsumer<String, Boolean> check) {
        check.accept("API : version courante compatible", WmiApi.isCompatible(WmiApi.VERSION));
        check.accept("API : VERSION conforme aux constantes",
                WmiApi.VERSION.equals(WmiApi.MAJOR + "." + WmiApi.MINOR + "." + WmiApi.PATCH));
        check.accept("API : autre version majeure refusee", !WmiApi.isCompatible((WmiApi.MAJOR + 1) + ".0.0"));
        check.accept("API : version mineure plus recente refusee",
                !WmiApi.isCompatible(WmiApi.MAJOR + "." + (WmiApi.MINOR + 1) + ".0"));
        check.accept("API : version mal formee refusee",
                !WmiApi.isCompatible("1") && !WmiApi.isCompatible("a.b.c") && !WmiApi.isCompatible(null));
    }

    private static void testIngredientKeys(BiConsumer<String, Boolean> check) {
        String water = WmiIngredient.key(WmiIngredient.FLUID, "minecraft:water");
        check.accept("API : cle de fluide au format genre@id", "fluid@minecraft:water".equals(water));
        check.accept("API : cle d'objet nue", "minecraft:stone".equals(WmiIngredient.key(WmiIngredient.ITEM, "minecraft:stone")));
        check.accept("API : genre et identifiant d'une cle",
                WmiIngredient.FLUID.equals(WmiIngredient.kindOf(water)) && "minecraft:water".equals(WmiIngredient.idOf(water))
                        && WmiIngredient.ITEM.equals(WmiIngredient.kindOf("minecraft:stone")));
        boolean refused;
        try {
            new WmiIngredient.Kind("bad@kind", "", "", 0);
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.accept("API : genre contenant @ refuse", refused);
        try {
            new WmiIngredient(List.of(), 1, "");
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.accept("API : ingredient sans alternative refuse", refused);
    }

    private static void testRecipes(BiConsumer<String, Boolean> check) {
        WmiRecipe r = WmiRecipe.builder("examplemod:grinding/iron", "examplemod:grinding")
                .input(WmiIngredient.item("minecraft:raw_iron", 1))
                .input(WmiIngredient.fluid("minecraft:water", 250))
                .output("examplemod:iron_dust", 2)
                .duration(200).energy(8)
                .note("examplemod.note.wet")
                .build();
        check.accept("API : energie totale deduite (duree x energie par tick)", r.totalEnergy() == 1600);
        check.accept("API : produit et notes conserves",
                r.outputs().size() == 1 && r.notes().equals(List.of("examplemod.note.wet")));
        RecipeEntry e = ApiRecipes.toEntry(r, NAMES);
        check.accept("API : conversion vers le noyau (ingredients, fluide, energie)",
                e.inputs().size() == 2 && e.inputs().get(1).alternatives().get(0).equals("fluid@minecraft:water")
                        && e.inputs().get(1).count() == 250 && e.durationTicks() == 200 && e.totalEnergy() == 1600
                        && e.outputs().get(0).count() == 2);
    }

    private static void testJsonLayouts(BiConsumer<String, Boolean> check) {
        String json = """
                {
                  "format": "wmi_recipe_layout", "version": 1,
                  "title": "container.examplemod.grinder",
                  "size": [160, 60],
                  "workstations": ["examplemod:grinder"],
                  "slots": [
                    {"role": "input", "index": 0, "x": 28, "y": 12},
                    {"role": "output", "index": 0, "x": 104, "y": 26, "large": true}
                  ],
                  "arrows": [{"x": 62, "y": 26}],
                  "gauges": [
                    {"kind": "energy", "x": 4, "y": 6, "width": 6, "height": 50},
                    {"kind": "fluid", "role": "input", "index": 1, "x": 140, "y": 6, "width": 16, "height": 50}
                  ],
                  "flame": {"x": 29, "y": 32}
                }
                """;
        String type = RecipeLayoutJson.typeIdFor("examplemod", "wmi_layouts/grinding.json");
        check.accept("JSON : type deduit du chemin", "examplemod:grinding".equals(type));
        RecipeLayoutSpec spec = RecipeLayoutJson.parse(json, type);
        check.accept("JSON : mise en page lue (type, taille, cases, fleche)",
                spec.recipeTypeId().equals("examplemod:grinding") && spec.width() == 160 && spec.height() == 60
                        && spec.slots().size() == 2 && spec.slots().get(1).large() && spec.arrows().get(0).width() == 24);
        check.accept("JSON : jauges et flamme lues",
                spec.gauges().size() == 2 && spec.showEnergyBar() && spec.showFlame()
                        && spec.gauges().get(1).kind() == RecipeLayoutSpec.GaugeKind.FLUID && spec.gauges().get(1).index() == 1);
        check.accept("JSON : format absent refuse", refuses("{\"slots\": [{\"x\": 1, \"y\": 1, \"role\": \"input\"}]}"));
        check.accept("JSON : cases absentes refusees", refuses("{\"format\": \"wmi_recipe_layout\"}"));
        check.accept("JSON : role inconnu refuse",
                refuses("{\"format\": \"wmi_recipe_layout\", \"slots\": [{\"role\": \"fuel\", \"x\": 1, \"y\": 1}]}"));
        check.accept("JSON : version future refusee",
                refuses("{\"format\": \"wmi_recipe_layout\", \"version\": 99, \"slots\": [{\"role\": \"input\", \"x\": 1, \"y\": 1}]}"));
        check.accept("JSON : coordonnee non entiere refusee",
                refuses("{\"format\": \"wmi_recipe_layout\", \"slots\": [{\"role\": \"input\", \"x\": 1.5, \"y\": 1}]}"));
        check.accept("JSON : texte invalide refuse", refuses("{"));

        // Une mise en page JSON prime sur celle d'une extension, puis s'efface au rechargement suivant
        WmiRegistry.registration().addLayout(RecipeLayoutSpec.builder("examplemod:grinding", "from.extension")
                .workstation("examplemod:grinder").inputSlot(0, 1, 1).build());
        WmiRegistry.setJsonLayouts(Map.of(spec.recipeTypeId(), spec));
        check.accept("JSON : prime sur la mise en page d'une extension",
                "container.examplemod.grinder".equals(WmiRegistry.layout("examplemod:grinding").titleKey()));
        WmiRegistry.setJsonLayouts(Map.of());
        check.accept("JSON : rechargement sans fichier, retour a l'extension",
                "from.extension".equals(WmiRegistry.layout("examplemod:grinding").titleKey()));
    }

    private static boolean refuses(String json) {
        try {
            RecipeLayoutJson.parse(json, "examplemod:x");
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void testCollect(BiConsumer<String, Boolean> check) {
        Supplier<List<WmiRecipe>> ok = () -> List.of(WmiRecipe.builder("examplemod:a", "examplemod:grinding")
                .input(WmiIngredient.item("minecraft:stone", 1)).output("minecraft:gravel", 1).note("line").build());
        Supplier<List<WmiRecipe>> broken = () -> {
            throw new IllegalStateException("boom");
        };
        ApiRecipes.Collected c = ApiRecipes.collect(List.of(broken, ok),
                Map.of("minecraft:dirt", List.of("examplemod.info.dirt")), NAMES);
        check.accept("API : fournisseur en echec consigne sans bloquer les autres",
                c.errors().size() == 1 && c.recipes().stream().anyMatch(r -> r.id().equals("examplemod:a")));
        check.accept("API : postes de travail repris de la mise en page",
                c.recipes().stream().filter(r -> r.id().equals("examplemod:a")).findFirst()
                        .map(r -> r.workstations().equals(List.of("examplemod:grinder"))).orElse(false));
        check.accept("API : fiche d'information convertie en recette sans produit",
                c.recipes().stream().anyMatch(r -> r.typeId().equals(ApiRecipes.INFO_TYPE)
                        && r.inputs().get(0).alternatives().equals(List.of("minecraft:dirt")) && r.outputs().isEmpty())
                        && c.notes().get(ApiRecipes.INFO_TYPE + "/minecraft:dirt").equals(List.of("examplemod.info.dirt")));
        check.accept("API : lignes de texte d'une recette gardees", c.notes().get("examplemod:a").equals(List.of("line")));
        check.accept("API : mise en page des fiches presente d'office", WmiRegistry.layout(ApiRecipes.INFO_TYPE) != null);

        WmiRegistration reg = WmiRegistry.registration();
        reg.addIngredientKind(new WmiIngredient.Kind("gas", "mB", "B", 1000));
        check.accept("API : genre d'ingredient declare", "mB".equals(WmiRegistry.ingredientKind("gas").unit())
                && WmiRegistry.ingredientKind(WmiIngredient.FLUID) != null);
        reg.addInfo("examplemod:widget", List.of("a"));
        reg.addInfo("examplemod:widget", List.of("b"));
        check.accept("API : fiches d'un meme ingredient cumulees", WmiRegistry.info("examplemod:widget").equals(List.of("a", "b")));
    }

    private static void testCraftTreeExclusion(BiConsumer<String, Boolean> check) {
        List<CatalogItem> items = List.of(
                new CatalogItem("examplemod:plate", "examplemod", "Example", "Plate", "01", 64, Set.of(), ""),
                new CatalogItem("examplemod:scrap", "examplemod", "Example", "Scrap", "01", 64, Set.of(), ""),
                new CatalogItem("minecraft:iron_ingot", "minecraft", "Minecraft", "Iron", "01", 64, Set.of(), ""));
        RecipeEntry recycle = ApiRecipes.toEntry(WmiRecipe.builder("examplemod:recycle", "examplemod:recycling")
                .input(WmiIngredient.item("examplemod:scrap", 1)).output("examplemod:plate", 1).build(), NAMES);
        RecipeEntry press = ApiRecipes.toEntry(WmiRecipe.builder("examplemod:press", "examplemod:pressing")
                .input(WmiIngredient.item("minecraft:iron_ingot", 1)).output("examplemod:plate", 1).build(), NAMES);
        RecipeIndex index = new RecipeIndex(items, List.of(recycle, press));
        WmiRegistry.registration().excludeFromCraftTree("examplemod:recycling");
        CraftTreeCalculator.CraftPlan plan = CraftTreeCalculator.plan(index, "examplemod:plate", 1,
                Map.of("examplemod:scrap", 1), Map.of());
        check.accept("API : type exclu de l'arbre jamais choisi comme etape",
                plan.missingRaw().getOrDefault("minecraft:iron_ingot", 0) == 1
                        && plan.usedFromStock().getOrDefault("examplemod:scrap", 0) == 0);
    }
}
