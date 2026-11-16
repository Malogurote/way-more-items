package waymoreitems.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ingrédient d'une {@link WmiRecipe} : une ou plusieurs alternatives équivalentes et une quantité.
 *
 * <p>Chaque alternative est une clé d'ingrédient : un objet par son identifiant nu ({@code "minecraft:iron_ingot"}),
 * tout autre genre par {@code "<genre>@<identifiant>"} ({@code "fluid@minecraft:water"}). Le caractère {@code '@'}
 * n'existe dans aucun identifiant de Minecraft, ce qui rend la clé sans ambiguïté. Les fluides se comptent en
 * millibuckets.</p>
 *
 * @param alternatives clés acceptées (au moins une)
 * @param amount       quantité requise (objets, ou unités du genre : mB pour les fluides)
 * @param tagId        étiquette d'origine affichée dans l'infobulle (ex. {@code "c:ingots/iron"}), vide sinon
 */
public record WmiIngredient(List<String> alternatives, int amount, String tagId) {
    /** Genre des objets : leur clé est l'identifiant nu. */
    public static final String ITEM = "item";
    /** Genre des fluides : quantités en millibuckets. */
    public static final String FLUID = "fluid";
    /** Séparateur entre le genre et l'identifiant dans une clé. */
    public static final char SEPARATOR = '@';

    /**
     * Genre d'ingrédient déclaré par un mod ({@link WmiRegistration#addIngredientKind}).
     *
     * @param kind      nom du genre (minuscules, sans {@code '@'}), ex. {@code "gas"}
     * @param unit      symbole de l'unité des quantités (ex. {@code "mB"}), vide pour un simple nombre
     * @param bigUnit   symbole de la grande unité (ex. {@code "B"}), vide si aucune
     * @param bigFactor nombre d'unités dans une grande unité (ex. 1000), 0 si aucune
     */
    public record Kind(String kind, String unit, String bigUnit, int bigFactor) {
        public Kind {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty() || kind.indexOf(SEPARATOR) >= 0 || ITEM.equals(kind)) {
                throw new IllegalArgumentException("invalid ingredient kind: " + kind);
            }
            unit = unit == null ? "" : unit;
            bigUnit = bigUnit == null ? "" : bigUnit;
            if (bigFactor < 0) {
                throw new IllegalArgumentException("bigFactor < 0: " + bigFactor);
            }
        }
    }

    public WmiIngredient {
        Objects.requireNonNull(alternatives, "alternatives");
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("an ingredient needs at least one alternative");
        }
        for (String a : alternatives) {
            Objects.requireNonNull(a, "alternative");
        }
        alternatives = List.copyOf(alternatives);
        if (amount < 1) {
            throw new IllegalArgumentException("amount < 1: " + amount);
        }
        tagId = tagId == null ? "" : tagId;
    }

    /** Clé d'un ingrédient de genre {@code kind} : l'identifiant nu pour un objet, {@code "<genre>@<id>"} sinon. */
    public static String key(String kind, String id) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        return ITEM.equals(kind) ? id : kind + SEPARATOR + id;
    }

    /** Genre d'une clé ({@link #ITEM} si elle ne porte pas de genre). */
    public static String kindOf(String key) {
        int at = key.indexOf(SEPARATOR);
        return at < 0 ? ITEM : key.substring(0, at);
    }

    /** Identifiant de registre d'une clé, sans son genre. */
    public static String idOf(String key) {
        int at = key.indexOf(SEPARATOR);
        return at < 0 ? key : key.substring(at + 1);
    }

    /** Un objet. */
    public static WmiIngredient item(String itemId, int count) {
        return new WmiIngredient(List.of(itemId), count, "");
    }

    /** Plusieurs objets équivalents, issus de l'étiquette {@code tagId} (vide si aucune). */
    public static WmiIngredient items(List<String> itemIds, int count, String tagId) {
        return new WmiIngredient(itemIds, count, tagId);
    }

    /** Un fluide, en millibuckets. */
    public static WmiIngredient fluid(String fluidId, int millibuckets) {
        return new WmiIngredient(List.of(key(FLUID, fluidId)), millibuckets, "");
    }

    /** Un ingrédient d'un autre genre déclaré (gaz...). */
    public static WmiIngredient of(String kind, String id, int amount) {
        return new WmiIngredient(List.of(key(kind, id)), amount, "");
    }

    /** Plusieurs alternatives d'un même genre. */
    public static WmiIngredient anyOf(String kind, List<String> ids, int amount, String tagId) {
        List<String> keys = new ArrayList<>(ids.size());
        for (String id : ids) {
            keys.add(key(kind, id));
        }
        return new WmiIngredient(keys, amount, tagId);
    }
}
