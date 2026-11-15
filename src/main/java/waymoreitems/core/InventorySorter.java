package waymoreitems.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moteur de tri d'inventaire et de conteneur à cases verrouillables (Java pur).
 *
 * Règles :
 * - Les cases dont l'indice figure dans {@code lockedSlots} ne sont jamais déplacées ni fusionnées avec le reste.
 * - Les piles d'objets identiques (même {@code stackKey}, qui inclut l'identifiant et les composants de données / NBT)
 *   des cases non verrouillées sont d'abord regroupées jusqu'à leur {@code maxStack} respectif.
 * - Les piles résultantes sont triées selon le critère choisi (par défaut : espace de noms du mod, puis catégorie,
 *   puis nom traduit, puis quantité décroissante).
 * - Les cases non verrouillées restantes sont remplies dans l'ordre, suivies de cases vides ({@code null}).
 * - Si le regroupement produisait plus de piles que de cases libres (case contenant plus qu'une pile pleine), rien
 *   n'est trié : la plage est rendue telle quelle, pour ne jamais perdre d'objet.
 * - {@link #restrictedSlots} désigne les cases à exclure du tri d'un conteneur (cases filtrées ou de capacité réduite).
 */
public final class InventorySorter {

    private InventorySorter() {
    }

    /**
     * Représentation abstraite d'une pile d'objets dans une case d'inventaire.
     *
     * @param itemId      identifiant de base de l'objet (ex. {@code "minecraft:iron_ingot"})
     * @param stackKey    clé complète d'empilement (identifiant + empreinte des composants/enchantements/durabilité)
     * @param namespace   espace de noms du mod (ex. {@code "exemplemod"})
     * @param category    catégorie de tri (ex. {@code "01_blocks"}, {@code "02_ingots"}, {@code "03_tools"})
     * @param displayName nom traduit pour le tri alphabétique
     * @param count       quantité dans cette pile
     * @param maxStack    taille maximale d'une pile de cet objet (ex. 64, 16, 1)
     */
    public record SlotStack(
            String itemId,
            String stackKey,
            String namespace,
            String category,
            String displayName,
            int count,
            int maxStack
    ) {
        public SlotStack {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(stackKey, "stackKey");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(displayName, "displayName");
            if (count < 1) {
                throw new IllegalArgumentException("count < 1: " + count);
            }
            if (maxStack < 1) {
                throw new IllegalArgumentException("maxStack < 1: " + maxStack);
            }
        }

        public SlotStack withCount(int newCount) {
            return new SlotStack(itemId, stackKey, namespace, category, displayName, newCount, maxStack);
        }
    }

    public enum SortOrder {
        MOD_CATEGORY_NAME,
        NAME_ONLY,
        QUANTITY_DESC
    }

    /** Règle d'une case : quantité maximale de {@code candidate} que la case {@code slotIndex} accepte (0 : refusé). */
    @FunctionalInterface
    public interface SlotCapacity {
        int capacity(int slotIndex, SlotStack candidate);
    }

    /**
     * Cases à exclure du tri d'un conteneur, en plus de {@code lockedSlots}. Une case n'est triée que si elle accepte
     * chacun des objets déplaçables (ceux des cases non verrouillées), en pile pleine ; une case qui contient plus
     * qu'une pile pleine est aussi exclue. Contrôle prudent : un objet qui finira par rester en place compte malgré tout
     * parmi les objets à accepter.
     */
    public static Set<Integer> restrictedSlots(List<SlotStack> slots, Set<Integer> lockedSlots, SlotCapacity rule) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(rule, "rule");
        Set<Integer> locked = lockedSlots == null ? Set.of() : lockedSlots;
        Map<String, SlotStack> candidates = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            SlotStack stack = slots.get(i);
            if (!locked.contains(i) && stack != null) {
                candidates.putIfAbsent(stack.stackKey(), stack);
            }
        }
        Set<Integer> restricted = new TreeSet<>();
        for (int i = 0; i < slots.size(); i++) {
            if (locked.contains(i)) {
                continue;
            }
            SlotStack own = slots.get(i);
            if (own != null && own.count() > own.maxStack()) {
                restricted.add(i);
                continue;
            }
            for (SlotStack c : candidates.values()) {
                if (rule.capacity(i, c) < c.maxStack()) {
                    restricted.add(i);
                    break;
                }
            }
        }
        return restricted;
    }

    private static final Comparator<SlotStack> BY_MOD_CAT_NAME = Comparator
            .comparing((SlotStack s) -> s.namespace().equals("minecraft") ? "" : s.namespace())
            .thenComparing(s -> CatalogItem.normalize(s.category()))
            .thenComparing(s -> CatalogItem.normalize(s.displayName()))
            .thenComparing(SlotStack::stackKey)
            .thenComparing(Comparator.comparingInt(SlotStack::count).reversed());

    private static final Comparator<SlotStack> BY_NAME = Comparator
            .comparing((SlotStack s) -> CatalogItem.normalize(s.displayName()))
            .thenComparing(SlotStack::stackKey)
            .thenComparing(Comparator.comparingInt(SlotStack::count).reversed());

    private static final Comparator<SlotStack> BY_QUANTITY = Comparator
            .comparingInt(SlotStack::count).reversed()
            .thenComparing(s -> CatalogItem.normalize(s.displayName()))
            .thenComparing(SlotStack::stackKey);

    /**
     * Trie une plage de cases ({@code slots}, où une case vide vaut {@code null}) en respectant {@code lockedSlots}.
     * Renvoie une nouvelle liste de même taille que {@code slots}.
     */
    public static List<SlotStack> sort(List<SlotStack> slots, Set<Integer> lockedSlots, SortOrder order) {
        Objects.requireNonNull(slots, "slots");
        Set<Integer> locked = lockedSlots == null ? Set.of() : lockedSlots;
        SortOrder sortOrder = order == null ? SortOrder.MOD_CATEGORY_NAME : order;

        // 1. Collecter et fusionner les piles des cases non verrouillées
        Map<String, PrototypeAndTotal> merged = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            if (locked.contains(i)) {
                continue;
            }
            SlotStack stack = slots.get(i);
            if (stack == null) {
                continue;
            }
            PrototypeAndTotal pt = merged.computeIfAbsent(stack.stackKey(), k -> new PrototypeAndTotal(stack));
            pt.totalCount += stack.count();
        }

        // 2. Découper en piles respectant maxStack
        List<SlotStack> compacted = new ArrayList<>();
        for (PrototypeAndTotal pt : merged.values()) {
            int rem = pt.totalCount;
            int max = pt.prototype.maxStack();
            while (rem > 0) {
                int take = Math.min(rem, max);
                compacted.add(pt.prototype.withCount(take));
                rem -= take;
            }
        }

        // 3. Trier les piles selon l'ordre demandé
        Comparator<SlotStack> cmp = switch (sortOrder) {
            case MOD_CATEGORY_NAME -> BY_MOD_CAT_NAME;
            case NAME_ONLY -> BY_NAME;
            case QUANTITY_DESC -> BY_QUANTITY;
        };
        compacted.sort(cmp);

        int free = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (!locked.contains(i)) {
                free++;
            }
        }
        if (compacted.size() > free) {
            return Collections.unmodifiableList(new ArrayList<>(slots));
        }

        // 4. Reconstruire le tableau final en laissant les cases verrouillées exactement à leur place
        List<SlotStack> result = new ArrayList<>(slots.size());
        int cursor = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (locked.contains(i)) {
                result.add(slots.get(i));
            } else if (cursor < compacted.size()) {
                result.add(compacted.get(cursor++));
            } else {
                result.add(null);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static final class PrototypeAndTotal {
        final SlotStack prototype;
        int totalCount;

        PrototypeAndTotal(SlotStack prototype) {
            this.prototype = prototype;
        }
    }
}
