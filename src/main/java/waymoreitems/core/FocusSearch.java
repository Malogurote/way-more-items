package waymoreitems.core;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Recherche, dans un arbre d'éléments d'interface, du premier élément qui capte la saisie (champ de texte actif),
 * en profondeur et en ordre, sans descendre au-delà d'une profondeur donnée.
 */
public final class FocusSearch {

    private FocusSearch() {
    }

    /**
     * @param roots    éléments de premier niveau
     * @param captures vrai si l'élément capte la saisie
     * @param children enfants d'un élément conteneur, ou {@code null} s'il n'en a pas
     * @param maxDepth profondeur maximale explorée (0 = premier niveau seul)
     * @return le premier élément qui capte la saisie, ou {@code null}
     */
    public static <T> T find(List<? extends T> roots, Predicate<? super T> captures,
                             Function<? super T, ? extends List<? extends T>> children, int maxDepth) {
        return find(roots, captures, children, maxDepth, 0);
    }

    private static <T> T find(List<? extends T> nodes, Predicate<? super T> captures,
                              Function<? super T, ? extends List<? extends T>> children, int maxDepth, int depth) {
        for (T node : nodes) {
            if (captures.test(node)) {
                return node;
            }
            if (depth < maxDepth) {
                List<? extends T> inner = children.apply(node);
                if (inner != null) {
                    T found = find(inner, captures, children, maxDepth, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }
}
