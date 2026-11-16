package waymoreitems.core;

import waymoreitems.api.RecipeLayoutSpec;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lecture d'une mise en page de recette déclarée en JSON dans les ressources d'un mod (Java pur), sans Java côté mod.
 *
 * <p>Emplacement : {@code assets/<modid>/wmi_layouts/<chemin>.json} ; type de recette par défaut
 * {@code <modid>:<chemin>}. Format :</p>
 * <pre>{@code
 * {
 *   "format": "wmi_recipe_layout", "version": 1,
 *   "recipe_type": "examplemod:grinding",
 *   "title": "container.examplemod.grinder",
 *   "size": [150, 64],
 *   "workstations": ["examplemod:grinder"],
 *   "slots": [
 *     {"role": "input", "index": 0, "x": 28, "y": 12},
 *     {"role": "catalyst", "index": 0, "x": 28, "y": 46},
 *     {"role": "output", "index": 0, "x": 104, "y": 26, "large": true}
 *   ],
 *   "arrows": [{"x": 62, "y": 26, "width": 24, "height": 17}],
 *   "gauges": [
 *     {"kind": "energy", "x": 4, "y": 6, "width": 6, "height": 50},
 *     {"kind": "fluid", "role": "input", "index": 1, "x": 130, "y": 6, "width": 16, "height": 50}
 *   ],
 *   "flame": {"x": 29, "y": 32}
 * }
 * }</pre>
 * <p>Seuls {@code format} et {@code slots} sont obligatoires ; {@code width}, {@code height} des flèches valent
 * 24 x 17 par défaut ; {@code size} vaut 150 x 64.</p>
 */
public final class RecipeLayoutJson {
    /** Valeur attendue du champ {@code format}. */
    public static final String FORMAT = "wmi_recipe_layout";
    /** Version du format lue par cette version de WMI. */
    public static final int VERSION = 1;
    /** Dossier des mises en page dans les ressources d'un mod. */
    public static final String DIRECTORY = "wmi_layouts";

    private RecipeLayoutJson() {
    }

    /**
     * Analyse {@code json} ; lève {@link IllegalArgumentException} (message en anglais, pour le journal) si le
     * texte est invalide ou incomplet.
     *
     * @param defaultTypeId type de recette déduit du chemin du fichier, utilisé si {@code recipe_type} est absent
     */
    public static RecipeLayoutSpec parse(String json, String defaultTypeId) {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
        }
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("root must be an object");
        }
        if (!FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("\"format\" must be \"" + FORMAT + "\"");
        }
        int version = m.containsKey("version") ? integer(m.get("version"), "version") : VERSION;
        if (version > VERSION) {
            throw new IllegalArgumentException("format version " + version + " is newer than " + VERSION);
        }
        String type = m.get("recipe_type") instanceof String s ? s : defaultTypeId;
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("missing \"recipe_type\"");
        }
        String title = m.get("title") instanceof String s ? s : type;
        RecipeLayoutSpec.Builder b = RecipeLayoutSpec.builder(type, title);
        if (m.get("size") instanceof List<?> size) {
            if (size.size() != 2) {
                throw new IllegalArgumentException("\"size\" must be [width, height]");
            }
            b.size(integer(size.get(0), "size"), integer(size.get(1), "size"));
        }
        for (Object w : list(m, "workstations")) {
            if (!(w instanceof String s)) {
                throw new IllegalArgumentException("\"workstations\" must hold strings");
            }
            b.workstation(s);
        }
        List<?> slots = list(m, "slots");
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("\"slots\" must not be empty");
        }
        for (Object o : slots) {
            Map<?, ?> s = object(o, "slots");
            RecipeLayoutSpec.SlotRole role = role(s.get("role"));
            int index = s.containsKey("index") ? integer(s.get("index"), "index") : 0;
            int x = integer(s.get("x"), "x");
            int y = integer(s.get("y"), "y");
            switch (role) {
                case INPUT -> b.inputSlot(index, x, y);
                case CATALYST -> b.catalystSlot(index, x, y);
                case OUTPUT -> b.outputSlot(index, x, y, Boolean.TRUE.equals(s.get("large")));
            }
        }
        for (Object o : list(m, "arrows")) {
            Map<?, ?> a = object(o, "arrows");
            b.arrow(integer(a.get("x"), "x"), integer(a.get("y"), "y"),
                    a.containsKey("width") ? integer(a.get("width"), "width") : 24,
                    a.containsKey("height") ? integer(a.get("height"), "height") : 17);
        }
        for (Object o : list(m, "gauges")) {
            Map<?, ?> g = object(o, "gauges");
            String kind = g.get("kind") instanceof String k ? k.toLowerCase(Locale.ROOT) : "";
            int x = integer(g.get("x"), "x");
            int y = integer(g.get("y"), "y");
            int w = integer(g.get("width"), "width");
            int h = integer(g.get("height"), "height");
            switch (kind) {
                case "energy" -> b.energyGauge(x, y, w, h);
                case "fluid" -> b.fluidGauge(g.containsKey("role") ? role(g.get("role")) : RecipeLayoutSpec.SlotRole.INPUT,
                        g.containsKey("index") ? integer(g.get("index"), "index") : 0, x, y, w, h);
                default -> throw new IllegalArgumentException("unknown gauge kind: " + g.get("kind"));
            }
        }
        if (m.get("flame") instanceof Map<?, ?> f) {
            b.flame(integer(f.get("x"), "x"), integer(f.get("y"), "y"));
        }
        if (Boolean.TRUE.equals(m.get("energy_bar"))) {
            b.energyBar(true);
        }
        return b.build();
    }

    /**
     * Type de recette déduit d'un fichier : {@code assets/<ns>/wmi_layouts/<chemin>.json} donne {@code <ns>:<chemin>}.
     *
     * @param namespace espace de noms de la ressource
     * @param path      chemin de la ressource (ex. {@code "wmi_layouts/grinding.json"})
     */
    public static String typeIdFor(String namespace, String path) {
        String p = path.startsWith(DIRECTORY + "/") ? path.substring(DIRECTORY.length() + 1) : path;
        if (p.endsWith(".json")) {
            p = p.substring(0, p.length() - 5);
        }
        return namespace + ":" + p;
    }

    private static RecipeLayoutSpec.SlotRole role(Object v) {
        if (v instanceof String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "input":
                    return RecipeLayoutSpec.SlotRole.INPUT;
                case "catalyst":
                    return RecipeLayoutSpec.SlotRole.CATALYST;
                case "output":
                    return RecipeLayoutSpec.SlotRole.OUTPUT;
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("\"role\" must be input, catalyst or output, not " + v);
    }

    private static List<?> list(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException("\"" + key + "\" must be an array");
    }

    private static Map<?, ?> object(Object v, String where) {
        if (v instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException("\"" + where + "\" must hold objects");
    }

    private static int integer(Object v, String key) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= 100_000) {
            return n.intValue();
        }
        throw new IllegalArgumentException("\"" + key + "\" must be an integer, not " + v);
    }
}
