package waymoreitems.core;

import java.util.Locale;
import java.util.Set;

/**
 * Classement d'un objet du catalogue en grande catégorie (machines, blocs, outils, matériaux), d'après sa nature,
 * le chemin de son identifiant et ses étiquettes. Les codes sont triables : {@code 01_machines} avant {@code 04_materials}.
 */
public final class ItemCategories {

    public static final String MACHINES = "01_machines";
    public static final String BLOCKS = "02_blocks";
    public static final String TOOLS = "03_tools";
    public static final String MATERIALS = "04_materials";

    private static final String[] MACHINE_WORDS = {
            "machine", "furnace", "generator", "boiler", "turbine", "solar",
            "wind", "pump", "pipe", "cable", "conveyor", "crafter", "anvil", "table", "stonecutter", "grindstone",
            "hopper", "piston", "observer", "dispenser", "dropper", "redstone", "repeater", "comparator", "rail",
            "lever", "button"};
    private static final String[] TOOL_TAG_WORDS = {
            "tools", "weapons", "armors", "enchantable", "swords", "pickaxes", "axes", "shovels", "hoes"};
    private static final String[] TOOL_WORDS = {
            "wrench", "hammer", "sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots",
            "bow", "shield", "shears"};

    private ItemCategories() {
    }

    /**
     * @param blockItem    l'objet pose un bloc
     * @param entityBlock  ce bloc porte une entité de bloc (machine, coffre...)
     * @param damageable   l'objet s'use (durabilité maximale positive)
     * @param path         chemin de l'identifiant, sans l'espace de noms
     * @param tags         étiquettes de l'objet ({@code espace:chemin})
     */
    public static String classify(boolean blockItem, boolean entityBlock, boolean damageable, String path, Set<String> tags) {
        String p = path.toLowerCase(Locale.ROOT);
        if (blockItem) {
            return entityBlock || containsAny(p, MACHINE_WORDS) ? MACHINES : BLOCKS;
        }
        for (String t : tags) {
            if (containsAny(t, TOOL_TAG_WORDS)) {
                return TOOLS;
            }
        }
        if (damageable || containsAny(p, TOOL_WORDS)) {
            return TOOLS;
        }
        return MATERIALS;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
