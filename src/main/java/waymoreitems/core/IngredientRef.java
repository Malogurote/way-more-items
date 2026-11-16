package waymoreitems.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Désignation typée d'un ingrédient (objet, fluide ou autre genre déclaré par un mod), Java pur.
 *
 * <p>Dans tout le noyau, un ingrédient est désigné par une clé en chaîne : un objet par son identifiant nu
 * ({@code "minecraft:iron_ingot"}), tout autre genre par {@code "<genre>@<identifiant>"}
 * ({@code "fluid@minecraft:water"}). Le caractère {@code '@'} est interdit dans les identifiants de Minecraft, ce qui
 * rend la clé sans ambiguïté. Les quantités des fluides sont exprimées en millibuckets (mB) dans les champs
 * {@code count} existants.</p>
 *
 * @param kind genre de l'ingrédient ({@link #ITEM}, {@link #FLUID} ou un genre enregistré par {@link #registerKind})
 * @param id   identifiant de registre de l'ingrédient (ex. {@code "minecraft:water"})
 */
public record IngredientRef(String kind, String id) {
    /** Genre des objets : la clé est l'identifiant nu. */
    public static final String ITEM = "item";
    /** Genre des fluides : quantités en millibuckets. */
    public static final String FLUID = "fluid";
    /** Séparateur entre le genre et l'identifiant dans une clé. */
    public static final char SEPARATOR = '@';
    /** Contenance d'un seau, en millibuckets. */
    public static final int BUCKET_VOLUME = 1000;

    /**
     * Description d'un genre d'ingrédient.
     *
     * @param kind       nom du genre (minuscules, sans {@code '@'})
     * @param unit       symbole de l'unité des quantités (ex. {@code "mB"}), vide pour un simple nombre
     * @param bigUnit    symbole de l'unité de grande quantité (ex. {@code "B"}), vide si aucune
     * @param bigFactor  nombre d'unités dans une grande unité (ex. 1000), 0 si aucune
     */
    public record Kind(String kind, String unit, String bigUnit, int bigFactor) {
        public Kind {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty() || kind.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException("genre invalide : " + kind);
            }
            unit = unit == null ? "" : unit;
            bigUnit = bigUnit == null ? "" : bigUnit;
        }
    }

    private static final Map<String, Kind> KINDS = new ConcurrentHashMap<>();

    static {
        registerKind(new Kind(ITEM, "", "", 0));
        registerKind(new Kind(FLUID, "mB", "B", BUCKET_VOLUME));
    }

    public IngredientRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (kind.isEmpty() || kind.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("genre invalide : " + kind);
        }
    }

    /** Déclare (ou remplace) un genre d'ingrédient, par exemple un gaz d'un mod tiers. */
    public static void registerKind(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        KINDS.put(kind.kind(), kind);
    }

    /** Description du genre {@code kind}, ou {@code null} s'il n'est pas déclaré. */
    public static Kind kindInfo(String kind) {
        return kind == null ? null : KINDS.get(kind);
    }

    /** Référence d'un objet. */
    public static IngredientRef item(String itemId) {
        return new IngredientRef(ITEM, itemId);
    }

    /** Référence d'un fluide. */
    public static IngredientRef fluid(String fluidId) {
        return new IngredientRef(FLUID, fluidId);
    }

    /** Clé en chaîne de cette référence (identifiant nu pour un objet). */
    public String key() {
        return ITEM.equals(kind) ? id : kind + SEPARATOR + id;
    }

    /** Vrai si cette référence désigne un objet. */
    public boolean isItem() {
        return ITEM.equals(kind);
    }

    /** Vrai si cette référence désigne un fluide. */
    public boolean isFluid() {
        return FLUID.equals(kind);
    }

    /** Clé d'un fluide (ex. {@code "fluid@minecraft:water"}). */
    public static String fluidKey(String fluidId) {
        return FLUID + SEPARATOR + Objects.requireNonNull(fluidId, "fluidId");
    }

    /** Lit une clé ; une clé sans {@code '@'} désigne un objet. */
    public static IngredientRef parse(String key) {
        Objects.requireNonNull(key, "key");
        int at = key.indexOf(SEPARATOR);
        if (at <= 0) {
            return new IngredientRef(ITEM, key);
        }
        return new IngredientRef(key.substring(0, at), key.substring(at + 1));
    }

    /** Genre désigné par une clé (rapide, sans allocation pour un objet). */
    public static String kindOf(String key) {
        if (key == null) {
            return ITEM;
        }
        int at = key.indexOf(SEPARATOR);
        return at <= 0 ? ITEM : key.substring(0, at);
    }

    /** Vrai si la clé désigne un fluide. */
    public static boolean isFluid(String key) {
        return key != null && key.startsWith(FLUID + SEPARATOR);
    }

    /** Vrai si la clé désigne un objet. */
    public static boolean isItem(String key) {
        return key != null && key.indexOf(SEPARATOR) <= 0;
    }

    /** Identifiant de registre désigné par une clé (sans le genre). */
    public static String idOf(String key) {
        if (key == null) {
            return "";
        }
        int at = key.indexOf(SEPARATOR);
        return at <= 0 ? key : key.substring(at + 1);
    }

    /**
     * Quantité lisible selon le genre de {@code key} : un nombre nu pour un objet, {@code "250 mB"} ou {@code "2 B"}
     * (nombre entier de grandes unités) pour un fluide. Les symboles d'unités sont internationaux.
     */
    public static String formatAmount(String key, long amount) {
        Kind k = kindInfo(kindOf(key));
        if (k == null || k.unit().isEmpty()) {
            return Long.toString(amount);
        }
        if (k.bigFactor() > 0 && !k.bigUnit().isEmpty() && amount >= k.bigFactor() && amount % k.bigFactor() == 0) {
            return (amount / k.bigFactor()) + " " + k.bigUnit();
        }
        return amount + " " + k.unit();
    }
}
