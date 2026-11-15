package waymoreitems.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Interpréteur de la Palette de commande de Way More Items (Java pur).
 * Permet avec une seule barre centrale de :
 * - déclencher une action rapide sur le monde ou le joueur ({@code jour}, {@code nuit}, {@code créatif}, {@code survie}, {@code pluie}, {@code soleil}, {@code soin}) ;
 * - se donner une quantité exacte d'un objet ({@code 64 acier}, {@code give 16 bronze_ingot}) ;
 * - chercher un objet ou ses recettes dans le catalogue ({@code acier}, {@code @exemplemod #ingots}, {@code ^macérateur}).
 */
public final class CommandPalette {

    public enum WorldAction {
        TIME_DAY("time_day", List.of("jour", "day", "matin", "soleil levant")),
        TIME_NOON("time_noon", List.of("midi", "noon", "zenith")),
        TIME_NIGHT("time_night", List.of("nuit", "night", "minuit", "midnight")),
        WEATHER_CLEAR("weather_clear", List.of("beau temps", "clear", "soleil", "sun", "degage")),
        WEATHER_RAIN("weather_rain", List.of("pluie", "rain", "averse")),
        WEATHER_THUNDER("weather_thunder", List.of("orage", "thunder", "tempete", "storm")),
        MODE_SURVIVAL("mode_survival", List.of("survie", "survival", "gm0", "s")),
        MODE_CREATIVE("mode_creative", List.of("creatif", "creative", "crea", "gm1", "c")),
        MODE_ADVENTURE("mode_adventure", List.of("aventure", "adventure", "gm2")),
        MODE_SPECTATOR("mode_spectator", List.of("spectateur", "spectator", "spec", "gm3")),
        HEAL_FEED("heal_feed", List.of("soin", "heal", "soigner", "nourrir", "feed", "vie")),
        SORT_INVENTORY("sort_inventory", List.of("trier", "sort", "ranger"));

        private final String id;
        private final Set<String> keywords;

        WorldAction(String id, List<String> rawKeywords) {
            this.id = id;
            List<String> norm = new ArrayList<>(rawKeywords.size());
            for (String kw : rawKeywords) {
                norm.add(CatalogItem.normalize(kw));
            }
            this.keywords = Set.copyOf(norm);
        }

        public String id() {
            return id;
        }

        public boolean matchesKeyword(String normalizedInput) {
            return keywords.contains(normalizedInput);
        }

        public boolean startsWithKeyword(String normalizedPrefix) {
            if (normalizedPrefix.isEmpty()) {
                return false;
            }
            for (String kw : keywords) {
                if (kw.startsWith(normalizedPrefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    public enum IntentKind {
        EXECUTE_ACTION,
        GIVE_ITEM,
        SEARCH_ITEMS
    }

    /**
     * Intention résolue à partir de la saisie de la palette de commande.
     *
     * @param kind             type d'intention (action monde/joueur, don d'objet, ou recherche catalogue)
     * @param action           action monde/joueur si {@code kind == EXECUTE_ACTION}
     * @param giveCount        quantité demandée si {@code kind == GIVE_ITEM}
     * @param matchingActions  actions suggérées commençant par la saisie
     * @param matchingItems    objets correspondants dans le catalogue
     */
    public record PaletteResult(
            IntentKind kind,
            WorldAction action,
            int giveCount,
            List<WorldAction> matchingActions,
            List<CatalogItem> matchingItems
    ) {
        public PaletteResult {
            Objects.requireNonNull(kind, "kind");
            matchingActions = matchingActions == null ? List.of() : List.copyOf(matchingActions);
            matchingItems = matchingItems == null ? List.of() : List.copyOf(matchingItems);
        }
    }

    private CommandPalette() {
    }

    /**
     * Analyse la saisie {@code rawInput} et renvoie l'intention principale ainsi que les suggestions (actions et objets).
     */
    public static PaletteResult evaluate(RecipeIndex index, String rawInput) {
        Objects.requireNonNull(index, "index");
        if (rawInput == null || rawInput.isBlank()) {
            return new PaletteResult(IntentKind.SEARCH_ITEMS, null, 0, List.of(WorldAction.values()), index.allItems());
        }

        String trimmed = rawInput.trim();
        String normFull = CatalogItem.normalize(trimmed);

        // 1. Correspondance exacte avec une commande d'action (ex. "nuit", "jour", "creatif", "!nuit")
        String actionQuery = normFull.startsWith("!") || normFull.startsWith("/") ? normFull.substring(1).trim() : normFull;
        for (WorldAction act : WorldAction.values()) {
            if (act.matchesKeyword(actionQuery)) {
                List<CatalogItem> items = index.search(trimmed);
                return new PaletteResult(IntentKind.EXECUTE_ACTION, act, 0, List.of(act), items);
            }
        }

        // 2. Syntaxe de don d'objet : "<nombre> <recherche>" ou "give <nombre> <recherche>" ou "give <recherche>"
        GiveParse give = tryParseGive(trimmed);
        if (give != null) {
            List<CatalogItem> candidates = index.search(give.itemQuery());
            if (!candidates.isEmpty()) {
                return new PaletteResult(IntentKind.GIVE_ITEM, null, give.count(), List.of(), candidates);
            }
        }

        // 3. Recherche standard d'objets accompagnée des actions suggérées en préfixe
        List<WorldAction> suggestedActions = new ArrayList<>();
        for (WorldAction act : WorldAction.values()) {
            if (act.startsWithKeyword(actionQuery)) {
                suggestedActions.add(act);
            }
        }
        List<CatalogItem> items = index.search(trimmed);
        return new PaletteResult(IntentKind.SEARCH_ITEMS, null, 0, Collections.unmodifiableList(suggestedActions), items);
    }

    private record GiveParse(int count, String itemQuery) {
    }

    private static GiveParse tryParseGive(String trimmed) {
        String[] parts = trimmed.split("\\s+", 3);
        if (parts.length >= 2) {
            String firstNorm = CatalogItem.normalize(parts[0]);
            if (firstNorm.equals("give") || firstNorm.equals("donner")) {
                if (parts.length == 3) {
                    Integer qty = parsePositiveInt(parts[1]);
                    if (qty != null && !parts[2].isBlank()) {
                        return new GiveParse(qty, parts[2].trim());
                    }
                }
                String rest = trimmed.substring(parts[0].length()).trim();
                if (!rest.isEmpty()) {
                    return new GiveParse(1, rest);
                }
            }
            Integer leadingQty = parsePositiveInt(parts[0]);
            if (leadingQty != null) {
                String rest = trimmed.substring(parts[0].length()).trim();
                if (!rest.isEmpty()) {
                    return new GiveParse(leadingQty, rest);
                }
            }
        }
        return null;
    }

    private static Integer parsePositiveInt(String s) {
        if (s == null || s.isEmpty() || s.length() > 4) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        int val = Integer.parseInt(s);
        return val >= 1 && val <= 6400 ? val : null;
    }
}
