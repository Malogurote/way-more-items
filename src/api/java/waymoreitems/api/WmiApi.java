package waymoreitems.api;

/**
 * Version de l'API publique de Way More Items.
 *
 * <p>Gestion stricte des versions (majeure.mineure.correctif) : une version majeure retire ou change un élément
 * existant ; une version mineure n'ajoute que des éléments ; un correctif ne change aucune signature. Une extension
 * compilée contre la version {@code X.Y.Z} est chargée par toute version d'exécution {@code X.y.z} avec
 * {@code y >= Y} ; sinon elle est ignorée et un avertissement est journalisé.</p>
 */
public final class WmiApi {
    /** Version majeure de l'API. */
    public static final int MAJOR = 1;
    /** Version mineure de l'API. */
    public static final int MINOR = 0;
    /** Version corrective de l'API. */
    public static final int PATCH = 0;
    /**
     * Version complète, à recopier dans {@link WmiPlugin#api()} : la valeur est figée dans la classe de
     * l'extension à la compilation, ce qui permet à WMI de vérifier la compatibilité sans la charger.
     */
    public static final String VERSION = "1.0.0";

    private WmiApi() {
    }

    /**
     * Vrai si une extension compilée contre {@code required} peut être chargée par cette version de l'API.
     * Une version mal formée est refusée.
     */
    public static boolean isCompatible(String required) {
        int[] v = parse(required);
        return v != null && v[0] == MAJOR && v[1] <= MINOR;
    }

    /** Décompose {@code "X.Y.Z"} (le correctif est facultatif), ou {@code null} si la chaîne est mal formée. */
    public static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        int[] out = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i]);
                if (out[i] < 0) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
