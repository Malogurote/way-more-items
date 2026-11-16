package waymoreitems.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recette fournie par un mod ({@link WmiRegistration#addRecipes}), en Java pur.
 *
 * @param id            identifiant unique (ex. {@code "examplemod:grinding/iron_dust"})
 * @param typeId        type de recette, qui choisit l'onglet et la mise en page (ex. {@code "examplemod:grinding"})
 * @param workstations  blocs ou objets qui exécutent la recette (affichés comme catalyseurs de l'onglet)
 * @param inputs        ingrédients consommés
 * @param catalysts     ingrédients non consommés (outil, moule...)
 * @param outputs       produits ; vide pour une simple fiche
 * @param durationTicks durée en ticks (0 : instantané ou inconnu)
 * @param energyPerTick énergie par tick, consommée ou produite (0 : sans énergie)
 * @param totalEnergy   énergie totale (0 : sans énergie, ou durée x énergie par tick si non précisée)
 * @param notes         lignes de texte de la carte de recette : clés de traduction, ou textes tels quels
 */
public record WmiRecipe(
        String id,
        String typeId,
        List<String> workstations,
        List<WmiIngredient> inputs,
        List<WmiIngredient> catalysts,
        List<Output> outputs,
        int durationTicks,
        int energyPerTick,
        long totalEnergy,
        List<String> notes
) {
    /**
     * Produit d'une recette.
     *
     * @param key    clé de l'ingrédient produit (voir {@link WmiIngredient})
     * @param amount quantité produite
     */
    public record Output(String key, int amount) {
        public Output {
            Objects.requireNonNull(key, "key");
            if (amount < 1) {
                throw new IllegalArgumentException("amount < 1: " + amount);
            }
        }

        public static Output item(String itemId, int count) {
            return new Output(itemId, count);
        }

        public static Output fluid(String fluidId, int millibuckets) {
            return new Output(WmiIngredient.key(WmiIngredient.FLUID, fluidId), millibuckets);
        }
    }

    public WmiRecipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(typeId, "typeId");
        workstations = workstations == null ? List.of() : List.copyOf(workstations);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        catalysts = catalysts == null ? List.of() : List.copyOf(catalysts);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        notes = notes == null ? List.of() : List.copyOf(notes);
        if (durationTicks < 0 || energyPerTick < 0 || totalEnergy < 0) {
            throw new IllegalArgumentException("negative duration or energy in " + id);
        }
        if (totalEnergy == 0) {
            totalEnergy = (long) durationTicks * energyPerTick;
        }
    }

    public static Builder builder(String id, String typeId) {
        return new Builder(id, typeId);
    }

    public static final class Builder {
        private final String id;
        private final String typeId;
        private final List<String> workstations = new ArrayList<>();
        private final List<WmiIngredient> inputs = new ArrayList<>();
        private final List<WmiIngredient> catalysts = new ArrayList<>();
        private final List<Output> outputs = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private int durationTicks;
        private int energyPerTick;
        private long totalEnergy;

        private Builder(String id, String typeId) {
            this.id = Objects.requireNonNull(id, "id");
            this.typeId = Objects.requireNonNull(typeId, "typeId");
        }

        public Builder workstation(String blockOrItemId) {
            workstations.add(Objects.requireNonNull(blockOrItemId));
            return this;
        }

        public Builder input(WmiIngredient ingredient) {
            inputs.add(Objects.requireNonNull(ingredient));
            return this;
        }

        public Builder catalyst(WmiIngredient ingredient) {
            catalysts.add(Objects.requireNonNull(ingredient));
            return this;
        }

        public Builder output(Output output) {
            outputs.add(Objects.requireNonNull(output));
            return this;
        }

        public Builder output(String itemId, int count) {
            return output(Output.item(itemId, count));
        }

        public Builder duration(int ticks) {
            this.durationTicks = ticks;
            return this;
        }

        /** Énergie par tick ; l'énergie totale vaut durée x énergie par tick sauf si {@link #totalEnergy} est fixée. */
        public Builder energy(int perTick) {
            this.energyPerTick = perTick;
            return this;
        }

        public Builder totalEnergy(long total) {
            this.totalEnergy = total;
            return this;
        }

        public Builder note(String translationKeyOrText) {
            notes.add(Objects.requireNonNull(translationKeyOrText));
            return this;
        }

        public WmiRecipe build() {
            return new WmiRecipe(id, typeId, workstations, inputs, catalysts, outputs, durationTicks, energyPerTick,
                    totalEnergy, notes);
        }
    }
}
