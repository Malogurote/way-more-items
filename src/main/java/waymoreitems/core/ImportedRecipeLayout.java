package waymoreitems.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import waymoreitems.api.RecipeLayoutSpec;

/**
 * Disposition des cases d'une recette décrite par un autre navigateur de recettes (Java pur) : positions des cases
 * dans le plan d'origine, ramenées dans la carte de recette de WMI quand elles y tiennent.
 *
 * <p>Deux résultats : les cases de la grille 3 x 3 de chaque ingrédient (pour les recettes d'artisanat, voir
 * {@link RecipeEntry#inputCells()}), et une mise en page {@link RecipeLayoutSpec} propre à la recette, ou
 * {@code null} quand le plan d'origine est trop grand (la carte retombe alors sur la disposition générique).</p>
 */
public final class ImportedRecipeLayout {
    /** Côté d'une case, fond compris. */
    public static final int SLOT = 18;
    /** Zone utile de la carte de recette (à gauche des durées et énergies), en pixels. */
    public static final int CARD_WIDTH = 132;
    public static final int CARD_HEIGHT = 58;
    /** Hauteur de la carte de recette de WMI. */
    private static final int CARD_FULL_HEIGHT = 62;
    private static final int LEFT_MARGIN = 6;

    /**
     * Une case du plan d'origine.
     *
     * @param role       rôle de la case
     * @param x          abscisse du coin supérieur gauche de la case (fond compris)
     * @param y          ordonnée du coin supérieur gauche
     * @param ingredient indice de l'ingrédient (ou du résultat, ou du catalyseur) dans la recette convertie, ou
     *                   {@code -1} pour une case vide ou dont le contenu n'a pas été retenu (fluide, par exemple)
     */
    public record Slot(RecipeLayoutSpec.SlotRole role, int x, int y, int ingredient) {
    }

    private ImportedRecipeLayout() {
    }

    /**
     * Cases de la grille 3 x 3 des ingrédients, si toutes les cases d'entrée (vides comprises) forment une grille
     * régulière de pas {@link #SLOT} d'au plus 3 x 3 ; liste vide sinon. Rend, dans l'ordre des ingrédients
     * {@code 0..inputCount-1}, la case de chacun.
     */
    public static List<Integer> gridCells(List<Slot> slots, int inputCount) {
        List<Slot> inputs = new ArrayList<>();
        for (Slot s : slots) {
            if (s.role() == RecipeLayoutSpec.SlotRole.INPUT) {
                inputs.add(s);
            }
        }
        if (inputCount < 1 || inputs.size() > RecipeEntry.GRID_WIDTH * RecipeEntry.GRID_WIDTH) {
            return List.of();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (Slot s : inputs) {
            minX = Math.min(minX, s.x());
            minY = Math.min(minY, s.y());
        }
        Integer[] cells = new Integer[inputCount];
        boolean[] used = new boolean[RecipeEntry.GRID_WIDTH * RecipeEntry.GRID_WIDTH];
        for (Slot s : inputs) {
            int dx = s.x() - minX;
            int dy = s.y() - minY;
            if (dx % SLOT != 0 || dy % SLOT != 0) {
                return List.of();
            }
            int col = dx / SLOT;
            int row = dy / SLOT;
            if (col >= RecipeEntry.GRID_WIDTH || row >= RecipeEntry.GRID_WIDTH) {
                return List.of();
            }
            int cell = row * RecipeEntry.GRID_WIDTH + col;
            if (used[cell]) {
                return List.of();
            }
            used[cell] = true;
            if (s.ingredient() >= 0) {
                if (s.ingredient() >= inputCount || cells[s.ingredient()] != null) {
                    return List.of();
                }
                cells[s.ingredient()] = cell;
            }
        }
        List<Integer> out = new ArrayList<>();
        for (Integer c : cells) {
            if (c == null) {
                return List.of();
            }
            out.add(c);
        }
        return List.copyOf(out);
    }

    /**
     * Mise en page de la recette dans la carte de WMI, ou {@code null} si le plan ne tient pas dans
     * {@link #CARD_WIDTH} x {@link #CARD_HEIGHT}, ou s'il n'a aucune case. Les cases d'entrée sont désignées par leur
     * case de grille quand {@code cells} n'est pas vide (comme {@link RecipeEntry#inputAtCell(int)} les lit), sinon par
     * leur indice ; une case vide reçoit un indice qui ne désigne rien (cadre vide). Une flèche est posée entre les
     * entrées et les résultats quand l'écart le permet.
     */
    public static RecipeLayoutSpec fit(String typeId, String title, List<Slot> slots, List<Integer> cells) {
        if (slots.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Slot s : slots) {
            minX = Math.min(minX, s.x());
            minY = Math.min(minY, s.y());
            maxX = Math.max(maxX, s.x() + SLOT);
            maxY = Math.max(maxY, s.y() + SLOT);
        }
        int bw = maxX - minX;
        int bh = maxY - minY;
        if (bw > CARD_WIDTH || bh > CARD_HEIGHT) {
            return null;
        }
        // La carte dessine une case à (y - 2) : décalage compensé pour centrer verticalement
        int offX = LEFT_MARGIN - minX;
        int offY = (CARD_FULL_HEIGHT - bh) / 2 + 2 - minY;
        RecipeLayoutSpec.Builder b = RecipeLayoutSpec.builder(typeId, title).size(150, 64);
        int unused = 1000;
        int inRight = Integer.MIN_VALUE;
        int outLeft = Integer.MAX_VALUE;
        int outTop = 0;
        for (Slot s : slots) {
            int x = s.x() + offX;
            int y = s.y() + offY;
            switch (s.role()) {
                case INPUT -> {
                    int index;
                    if (s.ingredient() < 0) {
                        index = unused++;
                    } else if (!cells.isEmpty()) {
                        index = cells.get(s.ingredient());
                    } else {
                        index = s.ingredient();
                    }
                    b.inputSlot(index, x, y);
                    inRight = Math.max(inRight, x + SLOT);
                }
                case CATALYST -> b.catalystSlot(s.ingredient() < 0 ? unused++ : s.ingredient(), x, y);
                case OUTPUT -> {
                    b.outputSlot(s.ingredient() < 0 ? unused++ : s.ingredient(), x, y, false);
                    if (x < outLeft) {
                        outLeft = x;
                        outTop = y;
                    }
                }
            }
        }
        if (inRight != Integer.MIN_VALUE && outLeft != Integer.MAX_VALUE && outLeft - inRight >= 28) {
            b.arrow((inRight + outLeft) / 2 - 12, outTop + 1);
        }
        return b.build();
    }

    /** Clé de partage : deux recettes de même disposition reçoivent la même mise en page. */
    public static String key(String typeId, List<Slot> slots, List<Integer> cells) {
        StringBuilder sb = new StringBuilder(typeId).append('|');
        for (Slot s : slots) {
            sb.append(s.role().ordinal()).append(',').append(s.x()).append(',').append(s.y()).append(',')
                    .append(s.ingredient()).append(';');
        }
        return sb.append('|').append(cells).toString();
    }

    /** Cache de mises en page partagées par clé (une par disposition distincte). */
    public static final class Cache {
        private final Map<String, RecipeLayoutSpec> byKey = new HashMap<>();

        public RecipeLayoutSpec get(String typeId, String title, List<Slot> slots, List<Integer> cells) {
            String k = key(typeId, slots, cells);
            if (byKey.containsKey(k)) {
                return byKey.get(k);
            }
            RecipeLayoutSpec spec = fit(typeId, title, slots, cells);
            byKey.put(k, spec);
            return spec;
        }
    }

    /**
     * Inventaire élargi aux zones d'un écran qui lui sont accolées (panneaux latéraux d'une machine, par exemple) :
     * une zone qui chevauche l'inventaire en hauteur et touche sa largeur à {@code gap} près l'élargit, jusqu'à ce
     * que plus rien ne change. Les zones éloignées, et celles plus larges que l'inventaire, sont ignorées. Rectangles en {@code {x, y, largeur, hauteur}}.
     */
    public static int[] extendWithAdjacent(int[] gui, List<int[]> areas, int gap) {
        int left = gui[0];
        int top = gui[1];
        int right = gui[0] + gui[2];
        int bottom = gui[1] + gui[3];
        boolean changed = true;
        for (int pass = 0; changed && pass < 8; pass++) {
            changed = false;
            for (int[] a : areas) {
                // Zone plus large que l'inventaire : surcouche d'écran, pas un panneau de la machine
                if (a == null || a.length < 4 || a[2] <= 0 || a[3] <= 0 || a[2] > gui[2]) {
                    continue;
                }
                int aL = a[0];
                int aR = a[0] + a[2];
                int aT = a[1];
                int aB = a[1] + a[3];
                boolean overlapsV = aT < bottom && aB > top;
                boolean touchesH = aL <= right + gap && aR >= left - gap;
                if (!overlapsV || !touchesH) {
                    continue;
                }
                if (aL < left) {
                    left = aL;
                    changed = true;
                }
                if (aR > right) {
                    right = aR;
                    changed = true;
                }
            }
        }
        return new int[] {left, top, right - left, bottom - top};
    }
}
