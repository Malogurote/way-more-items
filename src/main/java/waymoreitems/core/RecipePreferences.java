package waymoreitems.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Recettes choisies par le joueur pour les étapes de l'arbre de fabrication ({@code itemId -> recipeId}), en Java
 * pur. Un choix vaut pour l'objet dans tous les arbres ; un objet sans choix suit la sélection automatique du
 * calculateur. Texte d'enregistrement : {@code objet=recette;objet=recette}.
 */
public final class RecipePreferences {
    private final Map<String, String> byItem = new LinkedHashMap<>();
    private Map<String, String> snapshot = Map.of();

    /** Recette choisie pour {@code itemId}, ou {@code null} (choix automatique). */
    public String get(String itemId) {
        return byItem.get(itemId);
    }

    /**
     * Choisit {@code recipeId} pour {@code itemId} ; {@code null} ou vide rend le choix automatique.
     *
     * @return vrai si le choix a changé
     */
    public boolean set(String itemId, String recipeId) {
        Objects.requireNonNull(itemId, "itemId");
        String old = recipeId == null || recipeId.isEmpty() ? byItem.remove(itemId) : byItem.put(itemId, recipeId);
        boolean changed = !Objects.equals(old, recipeId == null || recipeId.isEmpty() ? null : recipeId);
        if (changed) {
            snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(byItem));
        }
        return changed;
    }

    /** Vue figée des choix, remplacée à chaque modification (utilisable comme clé de cache). */
    public Map<String, String> asMap() {
        return snapshot;
    }

    public boolean isEmpty() {
        return byItem.isEmpty();
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : byItem.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Remplace les choix par ceux de {@code text} (paires illisibles ignorées). */
    public void load(String text) {
        byItem.clear();
        if (text != null && !text.isBlank()) {
            for (String kv : text.split(";")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && eq < kv.length() - 1) {
                    byItem.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(byItem));
    }
}
