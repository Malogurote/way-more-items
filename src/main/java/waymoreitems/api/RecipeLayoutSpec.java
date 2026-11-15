package waymoreitems.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Descripteur déclaratif de la mise en page visuelle d'un type de recette dans Way More Items (Java pur).
 *
 * <p>Conçu pour offrir aux développeurs de mods la façon la plus simple possible d'afficher leurs propres machines,
 * soit par code Java en quelques lignes via {@link Builder}, soit par un simple fichier JSON sans aucune ligne de Java
 * ({@code assets/<modid>/wmi_layouts/<recipe_type_path>.json}).</p>
 *
 * @param recipeTypeId   identifiant complet du type de recette (ex. {@code "exemplemod:grinding"})
 * @param titleKey       clé de traduction ou titre de l'onglet (ex. {@code "container.exemplemod.grinder"})
 * @param width          largeur du panneau de recette en pixels (par défaut 150)
 * @param height         hauteur du panneau de recette en pixels (par défaut 64)
 * @param workstations   identifiants des blocs/machines associés à cet onglet
 * @param slots          cases d'entrée, de catalyseur et de sortie positionnées sur le plan
 * @param arrows         flèches de progression animées
 * @param showEnergyBar  vrai s'il faut afficher la jauge et les cotes d'énergie (EU/t et total EU)
 * @param showFlame      vrai s'il faut afficher l'indicateur de combustion aux coordonnées {@code (flameX, flameY)}
 * @param flameX         abscisse de la flamme (si {@code showFlame})
 * @param flameY         ordonnée de la flamme (si {@code showFlame})
 */
public record RecipeLayoutSpec(
        String recipeTypeId,
        String titleKey,
        int width,
        int height,
        List<String> workstations,
        List<SlotPos> slots,
        List<ProgressArrow> arrows,
        boolean showEnergyBar,
        boolean showFlame,
        int flameX,
        int flameY
) {
    public enum SlotRole {
        INPUT,
        CATALYST,
        OUTPUT
    }

    /**
     * Position d'une case sur le plan d'ingénieur de la recette.
     *
     * @param role  rôle de la case ({@link SlotRole#INPUT}, {@link SlotRole#CATALYST}, {@link SlotRole#OUTPUT})
     * @param index indice de l'ingrédient ou du résultat dans la recette (0, 1, 2...)
     * @param x     abscisse en pixels depuis le coin supérieur gauche du plan
     * @param y     ordonnée en pixels depuis le coin supérieur gauche du plan
     * @param large vrai si la case de sortie est encadrée en grand format (26×26 au lieu de 18×18)
     */
    public record SlotPos(SlotRole role, int index, int x, int y, boolean large) {
        public SlotPos {
            Objects.requireNonNull(role, "role");
            if (index < 0) {
                throw new IllegalArgumentException("index < 0: " + index);
            }
        }
    }

    /**
     * Flèche de flux animée entre les entrées et la sortie.
     */
    public record ProgressArrow(int x, int y, int width, int height) {
    }

    public RecipeLayoutSpec {
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(titleKey, "titleKey");
        if (width < 32 || height < 24) {
            throw new IllegalArgumentException("Dimensions trop petites : " + width + "x" + height);
        }
        workstations = workstations == null ? List.of() : List.copyOf(workstations);
        slots = slots == null ? List.of() : List.copyOf(slots);
        arrows = arrows == null ? List.of() : List.copyOf(arrows);
    }

    public static Builder builder(String recipeTypeId, String titleKey) {
        return new Builder(recipeTypeId, titleKey);
    }

    public static final class Builder {
        private final String recipeTypeId;
        private final String titleKey;
        private int width = 150;
        private int height = 64;
        private final List<String> workstations = new ArrayList<>();
        private final List<SlotPos> slots = new ArrayList<>();
        private final List<ProgressArrow> arrows = new ArrayList<>();
        private boolean showEnergyBar;
        private boolean showFlame;
        private int flameX;
        private int flameY;

        private Builder(String recipeTypeId, String titleKey) {
            this.recipeTypeId = Objects.requireNonNull(recipeTypeId, "recipeTypeId");
            this.titleKey = Objects.requireNonNull(titleKey, "titleKey");
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder workstation(String blockOrItemId) {
            this.workstations.add(Objects.requireNonNull(blockOrItemId));
            return this;
        }

        public Builder inputSlot(int index, int x, int y) {
            this.slots.add(new SlotPos(SlotRole.INPUT, index, x, y, false));
            return this;
        }

        public Builder catalystSlot(int index, int x, int y) {
            this.slots.add(new SlotPos(SlotRole.CATALYST, index, x, y, false));
            return this;
        }

        public Builder outputSlot(int index, int x, int y, boolean large) {
            this.slots.add(new SlotPos(SlotRole.OUTPUT, index, x, y, large));
            return this;
        }

        public Builder arrow(int x, int y) {
            this.arrows.add(new ProgressArrow(x, y, 24, 17));
            return this;
        }

        public Builder energyBar(boolean show) {
            this.showEnergyBar = show;
            return this;
        }

        public Builder flame(int x, int y) {
            this.showFlame = true;
            this.flameX = x;
            this.flameY = y;
            return this;
        }

        public RecipeLayoutSpec build() {
            return new RecipeLayoutSpec(
                    recipeTypeId,
                    titleKey,
                    width,
                    height,
                    Collections.unmodifiableList(new ArrayList<>(workstations)),
                    Collections.unmodifiableList(new ArrayList<>(slots)),
                    Collections.unmodifiableList(new ArrayList<>(arrows)),
                    showEnergyBar,
                    showFlame,
                    flameX,
                    flameY
            );
        }
    }
}
