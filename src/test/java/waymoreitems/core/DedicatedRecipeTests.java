package waymoreitems.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Vérifications de {@link DedicatedRecipeReader} sur des données au format des recettes du jeu (procédés en monde,
 * démontages, munitions, recettes calculées à la volée), avec un registre simulé.
 */
final class DedicatedRecipeTests {
    private DedicatedRecipeTests() {
    }

    private static final Set<String> ITEMS = Set.of("minecraft:snowball", "minecraft:grass_block", "minecraft:dirt",
            "minecraft:ice", "minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:obsidian", "minecraft:tnt",
            "minecraft:ender_pearl", "minecraft:crafting_table", "ae2:entropy_manipulator", "ae2:matter_cannon",
            "ae2:matter_ball", "ae2:charged_certus_quartz_crystal", "ae2:certus_quartz_dust", "ae2:certus_quartz_crystal",
            "ae2:singularity", "ae2:quantum_entangled_singularity", "ae2:crafting_unit", "ae2:cell_component_16k",
            "ae2:16k_crafting_storage", "ae2:fluid_storage_cell_16k", "ae2:fluid_cell_housing",
            "ae2:fluid_storage_cell_256k", "ae2:cell_component_256k", "ae2:cable_anchor", "ae2:facade");

    private static final DedicatedRecipeReader.Resolver FAKE = new DedicatedRecipeReader.Resolver() {
        @Override
        public List<String> itemsOfTag(String tagId) {
            return "c:ender_pearls".equals(tagId) ? List.of("minecraft:ender_pearl") : List.of();
        }

        @Override
        public boolean itemExists(String itemId) {
            return ITEMS.contains(itemId);
        }

        @Override
        public String itemOfBlock(String blockId) {
            return ITEMS.contains(blockId) ? blockId : "";
        }

        @Override
        public String itemOfFluid(String fluidId) {
            return switch (fluidId) {
                case "minecraft:water", "minecraft:flowing_water" -> "minecraft:water_bucket";
                case "minecraft:lava" -> "minecraft:lava_bucket";
                default -> "";
            };
        }

        @Override
        public List<String> itemsOfFluidTag(String tagId) {
            return "minecraft:water".equals(tagId) ? List.of("minecraft:water_bucket") : List.of();
        }

        @Override
        public String fluidName(String fluidId) {
            return "minecraft:water".equals(fluidId) ? "Water" : fluidId;
        }

        @Override
        public String text(String key) {
            return key;
        }
    };

    @SuppressWarnings("unchecked")
    private static DedicatedRecipeReader.Result read(String json) {
        return DedicatedRecipeReader.read("test:r", (Map<String, Object>) MiniJson.parse(json), FAKE);
    }

    private static boolean hasNote(DedicatedRecipeReader.Result r, String key) {
        return r != null && r.notes().stream().anyMatch(n -> n.key().equals(key));
    }

    static void run(BiConsumer<String, Boolean> check) {
        DedicatedRecipeReader.Result snow = read("{\"type\":\"ae2:entropy\",\"input\":{\"fluid\":{\"id\":\"minecraft:flowing_water\"}},"
                + "\"mode\":\"cool\",\"output\":{\"drops\":[{\"id\":\"minecraft:snowball\"}]}}");
        check.accept("dédié : entropie, eau courante refroidie donne une boule de neige (seau d'eau en entrée)",
                snow != null && snow.entry().inputs().get(0).matches("minecraft:water_bucket")
                        && snow.entry().produces("minecraft:snowball")
                        && snow.entry().workstations().equals(List.of("ae2:entropy_manipulator"))
                        && hasNote(snow, "wmi.extract.entropy.cool"));

        DedicatedRecipeReader.Result grass = read("{\"type\":\"ae2:entropy\",\"input\":{\"block\":{\"id\":\"minecraft:grass_block\"}},"
                + "\"mode\":\"cool\",\"output\":{\"block\":{\"id\":\"minecraft:dirt\"}}}");
        check.accept("dédié : entropie, bloc vers bloc", grass != null && grass.entry().produces("minecraft:dirt"));

        DedicatedRecipeReader.Result iceWater = read("{\"type\":\"ae2:entropy\",\"input\":{\"block\":{\"id\":\"minecraft:ice\"}},"
                + "\"mode\":\"heat\",\"output\":{\"fluid\":{\"id\":\"minecraft:water\"}}}");
        check.accept("dédié : entropie, fluide produit nommé, jamais compté comme un seau fabriqué",
                iceWater != null && iceWater.isInfo() && hasNote(iceWater, "wmi.extract.entropy.fluid_out")
                        && iceWater.notes().stream().anyMatch(n -> n.args().contains("Water"))
                        && hasNote(iceWater, "wmi.extract.entropy.heat"));

        DedicatedRecipeReader.Result waterAir = read("{\"type\":\"ae2:entropy\",\"input\":{\"fluid\":{\"id\":\"minecraft:water\"}},"
                + "\"mode\":\"heat\",\"output\":{\"block\":{\"id\":\"minecraft:air\"}}}");
        check.accept("dédié : entropie, l'air n'est pas un produit (le bloc disparaît)",
                waterAir != null && waterAir.isInfo() && hasNote(waterAir, "wmi.extract.entropy.vanishes"));

        DedicatedRecipeReader.Result crystals = read("{\"type\":\"ae2:transform\",\"ingredients\":"
                + "[\"ae2:charged_certus_quartz_crystal\",\"ae2:certus_quartz_dust\"],\"result\":{\"count\":2,\"id\":\"ae2:certus_quartz_crystal\"}}");
        check.accept("dédié : transformation dans l'eau par défaut (quantité 2, seau d'eau en catalyseur)",
                crystals != null && crystals.entry().outputCount("ae2:certus_quartz_crystal") == 2
                        && crystals.entry().inputs().size() == 2
                        && crystals.entry().catalysts().get(0).matches("minecraft:water_bucket")
                        && hasNote(crystals, "wmi.extract.transform.water"));

        DedicatedRecipeReader.Result entangled = read("{\"type\":\"ae2:transform\",\"circumstance\":{\"type\":\"explosion\"},"
                + "\"ingredients\":[\"ae2:singularity\",\"#c:ender_pearls\"],\"result\":{\"count\":2,\"id\":\"ae2:quantum_entangled_singularity\"}}");
        check.accept("dédié : transformation par explosion, étiquette résolue",
                entangled != null && entangled.entry().inputs().get(1).matches("minecraft:ender_pearl")
                        && entangled.entry().inputs().get(1).tagId().equals("c:ender_pearls")
                        && entangled.entry().catalysts().get(0).matches("minecraft:tnt")
                        && hasNote(entangled, "wmi.extract.transform.explosion"));

        DedicatedRecipeReader.Result unit = read("{\"type\":\"ae2:crafting_unit_transform\",\"upgrade_item\":\"ae2:cell_component_16k\","
                + "\"upgraded_block\":\"ae2:16k_crafting_storage\"}");
        check.accept("dédié : unité d'artisanat + composant donne le bloc amélioré",
                unit != null && unit.entry().produces("ae2:16k_crafting_storage") && unit.entry().uses("ae2:crafting_unit")
                        && unit.entry().uses("ae2:cell_component_16k"));

        DedicatedRecipeReader.Result dis = read("{\"type\":\"ae2:storage_cell_disassembly\",\"cell\":\"ae2:fluid_storage_cell_16k\","
                + "\"cell_disassembly_items\":[{\"id\":\"ae2:fluid_cell_housing\"},{\"id\":\"ae2:cell_component_16k\"}]}");
        check.accept("dédié : démontage d'une cellule en deux produits",
                dis != null && dis.entry().outputs().size() == 2 && dis.entry().uses("ae2:fluid_storage_cell_16k"));

        DedicatedRecipeReader.Result up = read("{\"type\":\"ae2:storage_cell_upgrade\",\"input_cell\":\"ae2:fluid_storage_cell_16k\","
                + "\"input_component\":\"ae2:cell_component_256k\",\"result_cell\":\"ae2:fluid_storage_cell_256k\","
                + "\"result_component\":\"ae2:cell_component_16k\"}");
        check.accept("dédié : amélioration de cellule, l'ancien composant est rendu",
                up != null && up.entry().produces("ae2:fluid_storage_cell_256k") && up.entry().produces("ae2:cell_component_16k")
                        && up.entry().workstations().contains("minecraft:crafting_table"));

        DedicatedRecipeReader.Result ammo = read("{\"type\":\"ae2:matter_cannon\",\"ammo\":\"ae2:matter_ball\",\"weight\":32.0}");
        check.accept("dédié : munition en fiche d'information (aucun produit, poids)",
                ammo != null && ammo.isInfo() && ammo.entry().uses("ae2:matter_ball")
                        && ammo.entry().workstations().equals(List.of("ae2:matter_cannon"))
                        && ammo.notes().get(0).args().equals(List.of("32.0")));
        check.accept("dédié : munition d'une étiquette vide ignorée",
                read("{\"type\":\"ae2:matter_cannon\",\"ammo\":\"#c:nuggets/aluminum\",\"weight\":26.98}") == null);

        DedicatedRecipeReader.Result facade = read("{\"type\":\"ae2:facade\"}");
        check.accept("dédié : façade, ancres en croix et case centrale libre",
                facade != null && facade.isInfo() && facade.entry().inputCells().equals(List.of(1, 3, 5, 7))
                        && facade.entry().inputAtCell(4) == null && facade.entry().uses("ae2:facade"));
        DedicatedRecipeReader.Result add = read("{\"type\":\"ae2:add_item_upgrade\"}");
        check.accept("dédié : ajout d'amélioration en fiche rattachée à l'établi",
                add != null && add.isInfo() && add.entry().uses("minecraft:crafting_table"));

        check.accept("dédié : type inconnu ou données invalides ignorés",
                read("{\"type\":\"minecraft:crafting_shaped\"}") == null
                        && read("{\"type\":\"ae2:transform\",\"ingredients\":[],\"result\":{\"id\":\"ae2:facade\"}}") == null
                        && read("{\"type\":\"ae2:entropy\",\"input\":3}") == null);
    }
}
