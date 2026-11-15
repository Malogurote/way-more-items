package waymoreitems.core;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Fiche d'un objet ou d'une variante dans le catalogue de Way More Items (Java pur, sans Minecraft).
 *
 * @param id          identifiant complet (ex. {@code "minecraft:iron_ingot"})
 * @param namespace   espace de noms du mod (ex. {@code "exemplemod"})
 * @param modName     nom lisible du mod (ex. {@code "Exemple Mod"})
 * @param displayName nom traduit de l'objet (ex. {@code "Lingot d'acier"})
 * @param category    catégorie de tri (ex. {@code "ingots"}, {@code "tools"}, {@code "blocks"})
 * @param maxStack    taille maximale d'une pile (1 à 99)
 * @param tags        étiquettes associées sans le {@code '#'} initial (ex. {@code "c:ingots/steel"})
 * @param tooltip     texte concaténé des lignes d'infobulle, pour la recherche par infobulle ({@code $})
 */
public record CatalogItem(
        String id,
        String namespace,
        String modName,
        String displayName,
        String category,
        int maxStack,
        Set<String> tags,
        String tooltip,
        String normalizedName,
        String normalizedMod,
        String normalizedTooltip,
        Set<String> normalizedTags
) {
    public CatalogItem(
            String id,
            String namespace,
            String modName,
            String displayName,
            String category,
            int maxStack,
            Set<String> tags,
            String tooltip
    ) {
        this(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(namespace, "namespace"),
                Objects.requireNonNull(modName, "modName"),
                Objects.requireNonNull(displayName, "displayName"),
                Objects.requireNonNull(category, "category"),
                maxStack,
                 normalizeTagSet(tags),
                tooltip == null ? "" : tooltip,
                // Nom affiché seul : l'identifiant technique (anglais) faussait la recherche dans les autres langues
                normalize(displayName),
                normalize(namespace) + " " + normalize(modName),
                normalize(tooltip == null ? "" : tooltip),
                normalizeTags(tags)
        );
    }

    public CatalogItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(modName, "modName");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(category, "category");
        if (maxStack < 1) {
            throw new IllegalArgumentException("maxStack < 1: " + maxStack);
        }
    }

    private static Set<String> normalizeTagSet(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>(raw.size());
        for (String t : raw) {
            if (t != null && !t.isBlank()) {
                out.add(t.startsWith("#") ? t.substring(1) : t);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> normalizeTags(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>(raw.size());
        for (String t : raw) {
            if (t != null && !t.isBlank()) {
                String clean = t.startsWith("#") ? t.substring(1) : t;
                out.add(normalize(clean));
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Normalise une chaîne pour la recherche insensible à la casse et aux accents.
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }
}
