package waymoreitems.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lecture dédiée des recettes que la lecture générique ne sait pas interpréter : procédés en monde, démontages,
 * recettes calculées à la volée. La recette arrive sous la forme de ses données (JSON produit par le codec de son
 * sérialiseur, analysé par {@link MiniJson}), sans aucune dépendance envers le mod qui la déclare. Le type est reconnu
 * par l'identifiant du sérialiseur (champ {@code type}).
 *
 * <p>Ce qui n'est pas une fabrication (munitions, améliorations calculées à la volée) devient une fiche
 * d'information : une recette sans produit, accompagnée de lignes de texte ({@link Note}), qui n'apparaît que dans les
 * usages de ses objets et n'est jamais retenue par l'arbre de fabrication.</p>
 */
public final class DedicatedRecipeReader {
    private static final System.Logger LOGGER = System.getLogger(DedicatedRecipeReader.class.getName());
    /** Sérialiseurs pris en charge. */
    public static final Set<String> SERIALIZERS = Set.of(
            "ae2:entropy", "ae2:transform", "ae2:crafting_unit_transform", "ae2:storage_cell_disassembly",
            "ae2:storage_cell_upgrade", "ae2:matter_cannon", "ae2:facade", "ae2:add_item_upgrade",
            "ae2:remove_item_upgrade");

    private static final String CRAFTING_TABLE = "minecraft:crafting_table";

    private DedicatedRecipeReader() {
    }

    /** Accès aux données du jeu nécessaires à la lecture (fourni par l'adaptateur, simulé dans les tests). */
    public interface Resolver {
        /** Objets d'une étiquette d'objets (sans {@code #}), vide si inconnue. */
        List<String> itemsOfTag(String tagId);

        /** Vrai si l'objet existe. */
        boolean itemExists(String itemId);

        /** Objet d'un bloc, ou {@code ""} s'il n'en a pas (air, feu). */
        String itemOfBlock(String blockId);

        /** Seau (ou autre récipient) d'un fluide, ou {@code ""}. */
        String itemOfFluid(String fluidId);

        /** Récipients des fluides d'une étiquette de fluides (sans {@code #}). */
        List<String> itemsOfFluidTag(String tagId);

        /** Nom traduit d'un fluide (identifiant à défaut). */
        String fluidName(String fluidId);

        /** Texte traduit d'une clé de langue. */
        String text(String key);
    }

    /** Ligne de texte d'une fiche : clé de langue et arguments déjà mis en forme. */
    public record Note(String key, List<String> args) {
        public Note {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /** Recette lue et ses lignes de texte. */
    public record Result(RecipeEntry entry, List<Note> notes) {
        public Result {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** Fiche d'information (aucun produit). */
        public boolean isInfo() {
            return entry.outputs().isEmpty();
        }
    }

    /** Vrai si ce sérialiseur est lu ici. */
    public static boolean handles(String serializerId) {
        return serializerId != null && SERIALIZERS.contains(serializerId);
    }

    /**
     * Lit une recette d'après ses données. Renvoie {@code null} si le type n'est pas pris en charge ou si les données
     * ne permettent pas d'en tirer une recette affichable.
     */
    public static Result read(String recipeId, Map<String, Object> json, Resolver r) {
        if (json == null || r == null) {
            return null;
        }
        String type = str(json.get("type"));
        if (!handles(type)) {
            return null;
        }
        try {
            return switch (type) {
                case "ae2:entropy" -> entropy(recipeId, type, json, r);
                case "ae2:transform" -> transform(recipeId, type, json, r);
                case "ae2:crafting_unit_transform" -> craftingUnit(recipeId, type, json, r);
                case "ae2:storage_cell_disassembly" -> disassembly(recipeId, type, json, r);
                case "ae2:storage_cell_upgrade" -> cellUpgrade(recipeId, type, json, r);
                case "ae2:matter_cannon" -> ammo(recipeId, type, json, r);
                case "ae2:facade" -> facade(recipeId, type, r);
                case "ae2:add_item_upgrade" -> info(recipeId, type, r, "wmi.extract.add_upgrade.title",
                        List.of(), List.of(new Note("wmi.extract.add_upgrade.note", List.of())));
                case "ae2:remove_item_upgrade" -> info(recipeId, type, r, "wmi.extract.remove_upgrade.title",
                        List.of(), List.of(new Note("wmi.extract.remove_upgrade.note", List.of())));
                default -> null;
            };
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.DEBUG, "Could not read the dedicated recipe " + recipeId, e);
            return null;
        }
    }

    // --- Types -------------------------------------------------------------------------------------------------

    private static Result entropy(String id, String type, Map<String, Object> json, Resolver r) {
        String mode = str(json.get("mode"));
        Map<String, Object> in = map(json.get("input"));
        Map<String, Object> out = map(json.get("output"));
        if (in == null || out == null) {
            return null;
        }
        String inItem = blockOrFluidItem(in, r);
        if (inItem.isEmpty()) {
            return null;
        }
        List<Note> notes = new ArrayList<>();
        notes.add(new Note("heat".equals(mode) ? "wmi.extract.entropy.heat" : "wmi.extract.entropy.cool", List.of()));
        List<RecipeEntry.ItemAmount> outputs = new ArrayList<>();
        Map<String, Object> outBlock = map(out.get("block"));
        if (outBlock != null) {
            String outItem = r.itemOfBlock(str(outBlock.get("id")));
            if (!outItem.isEmpty()) {
                outputs.add(new RecipeEntry.ItemAmount(outItem, 1));
            }
        }
        // Fluide produit : nommé, jamais compté comme un seau fabriqué (l'arbre de fabrication en déduirait un seau)
        Map<String, Object> outFluid = map(out.get("fluid"));
        boolean fluidOut = outFluid != null && !str(outFluid.get("id")).isEmpty();
        if (fluidOut) {
            notes.add(new Note("wmi.extract.entropy.fluid_out", List.of(r.fluidName(str(outFluid.get("id"))))));
        }
        for (Object drop : list(out.get("drops"))) {
            RecipeEntry.ItemAmount a = template(drop, r);
            if (a != null) {
                outputs.add(a);
            }
        }
        if (outputs.isEmpty() && !fluidOut) {
            notes.add(new Note("wmi.extract.entropy.vanishes", List.of()));
        }
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.entropy.title"),
                existing(r, "ae2:entropy_manipulator"), List.of(new RecipeEntry.IngredientSlot(List.of(inItem), 1)),
                List.of(), outputs, 0, 0, 0);
        return new Result(e, notes);
    }

    private static Result transform(String id, String type, Map<String, Object> json, Resolver r) {
        List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
        for (Object ing : list(json.get("ingredients"))) {
            RecipeEntry.IngredientSlot slot = ingredient(ing, 1, r);
            if (slot == null) {
                return null;
            }
            inputs.add(slot);
        }
        RecipeEntry.ItemAmount result = template(json.get("result"), r);
        if (inputs.isEmpty() || result == null) {
            return null;
        }
        Map<String, Object> circ = map(json.get("circumstance"));
        String circType = circ == null ? "fluid" : str(circ.get("type"));
        List<RecipeEntry.IngredientSlot> catalysts = new ArrayList<>();
        Note note;
        if ("explosion".equals(circType)) {
            if (r.itemExists("minecraft:tnt")) {
                catalysts.add(new RecipeEntry.IngredientSlot(List.of("minecraft:tnt"), 1));
            }
            note = new Note("wmi.extract.transform.explosion", List.of());
        } else {
            String tag = circ == null ? "minecraft:water" : stripHash(str(circ.get("tag")));
            List<String> containers = r.itemsOfFluidTag(tag);
            if (!containers.isEmpty()) {
                catalysts.add(new RecipeEntry.IngredientSlot(containers, 1, ""));
            }
            note = "minecraft:water".equals(tag)
                    ? new Note("wmi.extract.transform.water", List.of())
                    : new Note("wmi.extract.transform.fluid", List.of(tag));
        }
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.transform.title"), List.of(), inputs, catalysts,
                List.of(result), 0, 0, 0);
        return new Result(e, List.of(note));
    }

    private static Result craftingUnit(String id, String type, Map<String, Object> json, Resolver r) {
        String upgrade = str(json.get("upgrade_item"));
        String block = r.itemOfBlock(str(json.get("upgraded_block")));
        if (!r.itemExists(upgrade) || block.isEmpty()) {
            return null;
        }
        List<RecipeEntry.IngredientSlot> inputs = new ArrayList<>();
        if (r.itemExists("ae2:crafting_unit")) {
            inputs.add(new RecipeEntry.IngredientSlot(List.of("ae2:crafting_unit"), 1));
        }
        inputs.add(new RecipeEntry.IngredientSlot(List.of(upgrade), 1));
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.crafting_unit.title"), List.of(), inputs,
                List.of(), List.of(new RecipeEntry.ItemAmount(block, 1)), 0, 0, 0);
        return new Result(e, List.of(new Note("wmi.extract.crafting_unit.note", List.of()),
                new Note("wmi.extract.crafting_unit.undo", List.of())));
    }

    private static Result disassembly(String id, String type, Map<String, Object> json, Resolver r) {
        String cell = str(json.get("cell"));
        if (!r.itemExists(cell)) {
            return null;
        }
        List<RecipeEntry.ItemAmount> outputs = new ArrayList<>();
        for (Object o : list(json.get("cell_disassembly_items"))) {
            RecipeEntry.ItemAmount a = template(o, r);
            if (a != null) {
                outputs.add(a);
            }
        }
        if (outputs.isEmpty()) {
            return null;
        }
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.disassembly.title"), List.of(),
                List.of(new RecipeEntry.IngredientSlot(List.of(cell), 1)), List.of(), outputs, 0, 0, 0);
        return new Result(e, List.of(new Note("wmi.extract.disassembly.note", List.of())));
    }

    private static Result cellUpgrade(String id, String type, Map<String, Object> json, Resolver r) {
        String inCell = str(json.get("input_cell"));
        String inComp = str(json.get("input_component"));
        String outCell = str(json.get("result_cell"));
        String outComp = str(json.get("result_component"));
        if (!r.itemExists(inCell) || !r.itemExists(inComp) || !r.itemExists(outCell)) {
            return null;
        }
        List<RecipeEntry.ItemAmount> outputs = new ArrayList<>();
        outputs.add(new RecipeEntry.ItemAmount(outCell, 1));
        if (r.itemExists(outComp)) {
            outputs.add(new RecipeEntry.ItemAmount(outComp, 1));
        }
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.cell_upgrade.title"), existing(r, CRAFTING_TABLE),
                List.of(new RecipeEntry.IngredientSlot(List.of(inCell), 1), new RecipeEntry.IngredientSlot(List.of(inComp), 1)),
                List.of(), outputs, 0, 0, 0);
        return new Result(e, List.of(new Note("wmi.extract.cell_upgrade.note", List.of())));
    }

    private static Result ammo(String id, String type, Map<String, Object> json, Resolver r) {
        RecipeEntry.IngredientSlot ammo = ingredient(json.get("ammo"), 1, r);
        if (ammo == null) {
            return null;
        }
        Object w = json.get("weight");
        String weight = w instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue()) : "?";
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.ammo.title"), existing(r, "ae2:matter_cannon"),
                List.of(ammo), List.of(), List.of(), 0, 0, 0);
        return new Result(e, List.of(new Note("wmi.extract.ammo.weight", List.of(weight))));
    }

    private static Result facade(String id, String type, Resolver r) {
        if (!r.itemExists("ae2:cable_anchor")) {
            return null;
        }
        RecipeEntry.IngredientSlot anchor = new RecipeEntry.IngredientSlot(List.of("ae2:cable_anchor"), 1);
        // Ancres en croix autour de la case centrale, laissée au bloc choisi par le joueur
        RecipeEntry e = new RecipeEntry(id, type, r.text("wmi.extract.facade.title"), existing(r, CRAFTING_TABLE),
                List.of(anchor, anchor, anchor, anchor), List.of(), List.of(), 0, 0, 0, List.of(1, 3, 5, 7));
        List<String> ws = existing(r, "ae2:facade");
        return new Result(ws.isEmpty() ? e : withWorkstations(e, ws),
                List.of(new Note("wmi.extract.facade.note", List.of())));
    }

    private static Result info(String id, String type, Resolver r, String titleKey, List<RecipeEntry.IngredientSlot> inputs,
                               List<Note> notes) {
        RecipeEntry e = new RecipeEntry(id, type, r.text(titleKey), existing(r, CRAFTING_TABLE), inputs, List.of(),
                List.of(), 0, 0, 0);
        return new Result(e, notes);
    }

    // --- Données -----------------------------------------------------------------------------------------------

    private static RecipeEntry withWorkstations(RecipeEntry e, List<String> extra) {
        List<String> ws = new ArrayList<>(e.workstations());
        ws.addAll(extra);
        return new RecipeEntry(e.id(), e.typeId(), e.categoryName(), ws, e.inputs(), e.catalysts(), e.outputs(),
                e.durationTicks(), e.energyPerTick(), e.totalEnergy(), e.inputCells());
    }

    /** Objet d'une entrée ou sortie {@code {block:{id}} | {fluid:{id}}}, ou {@code ""}. */
    private static String blockOrFluidItem(Map<String, Object> m, Resolver r) {
        Map<String, Object> block = map(m.get("block"));
        if (block != null) {
            return r.itemOfBlock(str(block.get("id")));
        }
        Map<String, Object> fluid = map(m.get("fluid"));
        if (fluid != null) {
            return r.itemOfFluid(str(fluid.get("id")));
        }
        return "";
    }

    /** Ingrédient au format du jeu : identifiant, {@code #étiquette} ou liste d'identifiants. */
    static RecipeEntry.IngredientSlot ingredient(Object o, int count, Resolver r) {
        if (o instanceof String s) {
            if (s.startsWith("#")) {
                List<String> items = r.itemsOfTag(s.substring(1));
                return items.isEmpty() ? null : new RecipeEntry.IngredientSlot(items, count, s.substring(1));
            }
            return r.itemExists(s) ? new RecipeEntry.IngredientSlot(List.of(s), count) : null;
        }
        if (o instanceof List<?> l) {
            List<String> items = new ArrayList<>();
            for (Object e : l) {
                if (e instanceof String s && r.itemExists(s) && !items.contains(s)) {
                    items.add(s);
                }
            }
            return items.isEmpty() ? null : new RecipeEntry.IngredientSlot(items, count);
        }
        return null;
    }

    /** Pile au format du jeu : {@code {id, count}}. */
    private static RecipeEntry.ItemAmount template(Object o, Resolver r) {
        Map<String, Object> m = map(o);
        if (m == null) {
            return null;
        }
        String id = str(m.get("id"));
        if (!r.itemExists(id)) {
            return null;
        }
        Object c = m.get("count");
        int count = c instanceof Number n ? Math.max(1, n.intValue()) : 1;
        return new RecipeEntry.ItemAmount(id, count);
    }

    private static List<String> existing(Resolver r, String itemId) {
        return r.itemExists(itemId) ? List.of(itemId) : List.of();
    }

    private static String stripHash(String s) {
        return s.startsWith("#") ? s.substring(1) : s;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
