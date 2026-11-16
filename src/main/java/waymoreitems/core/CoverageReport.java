package waymoreitems.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Couverture de la lecture des recettes (Java pur) : pour chaque recette connue du jeu, dit si l'index de Way More
 * Items l'a lue, puis agrège par mod et par type de recette.
 */
public final class CoverageReport {

    /** Une recette connue du jeu : son identifiant, son type, et si l'index l'a lue. */
    public record Sample(String recipeId, String typeId, boolean read) {
        public Sample {
            recipeId = recipeId == null ? "" : recipeId;
            typeId = typeId == null || typeId.isBlank() ? "?" : typeId;
        }

        /** Espace de noms de la recette (le mod), {@code "?"} s'il manque. */
        public String mod() {
            int colon = recipeId.indexOf(':');
            return colon > 0 ? recipeId.substring(0, colon) : "?";
        }
    }

    /** Compte d'un type de recette. */
    public record TypeCoverage(String typeId, int received, int read) {
        public int unread() {
            return received - read;
        }
    }

    /** Compte d'un mod, avec le détail par type (les types les moins bien lus d'abord). */
    public record ModCoverage(String mod, int received, int read, List<TypeCoverage> types) {
        public int unread() {
            return received - read;
        }

        public double percent() {
            return percentOf(read, received);
        }
    }

    private final List<ModCoverage> mods;
    private final int received;
    private final int read;

    private CoverageReport(List<ModCoverage> mods) {
        this.mods = mods;
        int r = 0;
        int l = 0;
        for (ModCoverage m : mods) {
            r += m.received();
            l += m.read();
        }
        this.received = r;
        this.read = l;
    }

    /** Agrège les échantillons : mods classés par recettes non lues décroissantes, puis par nom. */
    public static CoverageReport of(List<Sample> samples) {
        Map<String, Map<String, int[]>> byMod = new TreeMap<>();
        for (Sample s : samples) {
            int[] c = byMod.computeIfAbsent(s.mod(), k -> new TreeMap<>()).computeIfAbsent(s.typeId(), k -> new int[2]);
            c[0]++;
            if (s.read()) {
                c[1]++;
            }
        }
        Comparator<TypeCoverage> typeOrder = Comparator.comparingInt(TypeCoverage::unread).reversed()
                .thenComparing(TypeCoverage::typeId);
        List<ModCoverage> mods = new ArrayList<>();
        byMod.forEach((mod, types) -> {
            List<TypeCoverage> list = new ArrayList<>();
            int r = 0;
            int l = 0;
            for (Map.Entry<String, int[]> t : types.entrySet()) {
                list.add(new TypeCoverage(t.getKey(), t.getValue()[0], t.getValue()[1]));
                r += t.getValue()[0];
                l += t.getValue()[1];
            }
            list.sort(typeOrder);
            mods.add(new ModCoverage(mod, r, l, List.copyOf(list)));
        });
        mods.sort(Comparator.comparingInt(ModCoverage::unread).reversed().thenComparing(ModCoverage::mod));
        return new CoverageReport(List.copyOf(mods));
    }

    public List<ModCoverage> mods() {
        return mods;
    }

    public int received() {
        return received;
    }

    public int read() {
        return read;
    }

    public int unread() {
        return received - read;
    }

    public double percent() {
        return percentOf(read, received);
    }

    /** Pourcentage de {@code part} sur {@code total} (100 si {@code total} vaut 0). */
    public static double percentOf(int part, int total) {
        return total <= 0 ? 100.0 : 100.0 * part / total;
    }

    /** Pourcentage à une décimale, séparateur point (indépendant de la langue du système). */
    public static String formatPercent(double p) {
        return String.format(Locale.ROOT, "%.1f", p);
    }
}
