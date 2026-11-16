package waymoreitems.api;

/**
 * Point d'entrée d'un mod dans Way More Items, découvert par {@link WmiPlugin}.
 *
 * <p>{@link #register} est appelée une fois, côté client, avant la première indexation des recettes. Les
 * fournisseurs de recettes enregistrés sont ensuite interrogés à chaque réindexation (connexion à un monde,
 * rechargement des recettes ou des étiquettes).</p>
 */
public interface WmiExtension {
    /** Identifiant de l'extension, pour les journaux (en général l'identifiant du mod). */
    String id();

    /** Déclare les mises en page, recettes, genres d'ingrédients, fiches et règles de l'arbre du mod. */
    void register(WmiRegistration registration);
}
