package waymoreitems.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Option « Noms d'objets : anglais » pour tout le jeu : les noms d'objets et de blocs, les enchantements et les effets
 * passent en anglais (Minecraft et mods), le reste (menus, biomes, titres des fenêtres) garde la langue du jeu.
 * <p>Mécanisme : Minecraft lit chaque traduction dans {@link Language#getInstance()} ; WMI y superpose, par
 * {@link Language#inject}, une table qui donne la version anglaise de ces seules clés (lue dans les fichiers
 * {@code lang/en_us.json} de toutes les ressources chargées) et renvoie le reste à la table du jeu. Aucun
 * rechargement des ressources : la bascule est immédiate. Après un rechargement (changement de langue, pack de
 * ressources), Minecraft installe une nouvelle table ; {@link #syncGameLanguage()}, appelé à chaque tick, la recouvre.
 */
public final class WmiNames {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Familles de clés passées en anglais. */
    private static final String[] PREFIXES = {"item.", "block.", "enchantment.", "effect."};

    private static @Nullable Language lastBase;
    private static @Nullable Map<String, String> englishNames;

    private WmiNames() {
    }

    /** Oublie l'anglais lu (ressources rechargées). */
    public static synchronized void reset() {
        englishNames = null;
    }

    /** Nom d'une pile, dans la langue en vigueur (anglais pour les objets si l'option est active). */
    public static String of(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    /**
     * Infobulle d'un objet (celle de Minecraft, qui suit la table de traduction en vigueur). {@code x}, {@code y} en
     * unités de WMI : l'infobulle, différée, est placée en unités du jeu.
     */
    public static void tooltip(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
        g.setTooltipForNextFrame(font, stack, WmiUiScale.real(x), WmiUiScale.real(y));
    }

    /**
     * Pose ou retire la table anglaise selon l'option et la langue du jeu (rien à faire si le jeu est déjà en anglais).
     * Appelé à chaque tick client et au changement de l'option ; ne fait rien tant que l'état est déjà le bon.
     */
    public static void syncGameLanguage() {
        Minecraft mc = Minecraft.getInstance();
        Language current = Language.getInstance();
        boolean want = WmiClient.overlay().settings().englishNames()
                && mc.getLanguageManager() != null
                && !Language.DEFAULT.equals(mc.getLanguageManager().getSelected());
        if (current instanceof EnglishNamesLanguage overlay) {
            if (!want) {
                Language.inject(overlay.base);
                refreshSearchTrees(mc);
            }
            return;
        }
        if (!want) {
            return;
        }
        if (current != lastBase) {
            // Nouvelle table du jeu (rechargement) : relire l'anglais, des packs ou des mods ont pu changer
            reset();
            lastBase = current;
        }
        Map<String, String> english = englishNames(mc);
        if (english == null || english.isEmpty()) {
            return;
        }
        Language.inject(new EnglishNamesLanguage(current, english));
        refreshSearchTrees(mc);
    }

    /** Recherche du mode créatif : reconstruite sur les noms en vigueur (comme le fait Minecraft après un changement de langue). */
    private static void refreshSearchTrees(Minecraft mc) {
        if (mc.player != null) {
            mc.player.connection.updateSearchTrees();
        }
    }

    private static synchronized @Nullable Map<String, String> englishNames(Minecraft mc) {
        if (englishNames == null) {
            try {
                ClientLanguage en = ClientLanguage.loadFrom(mc.getResourceManager(), List.of(Language.DEFAULT), false);
                Map<String, String> picked = new HashMap<>();
                for (Map.Entry<String, String> e : en.getLanguageData().entrySet()) {
                    for (String p : PREFIXES) {
                        if (e.getKey().startsWith(p)) {
                            picked.put(e.getKey(), e.getValue());
                            break;
                        }
                    }
                }
                englishNames = picked;
            } catch (RuntimeException e) {
                LOGGER.warn("Could not read the English names", e);
                englishNames = Map.of();
            }
        }
        return englishNames;
    }

    /** Table du jeu dont les noms d'objets, de blocs, d'enchantements et d'effets sont remplacés par l'anglais. */
    private static final class EnglishNamesLanguage extends Language {
        private final Language base;
        private final Map<String, String> english;
        private @Nullable Map<String, String> merged;

        EnglishNamesLanguage(Language base, Map<String, String> english) {
            this.base = base;
            this.english = english;
        }

        @Override
        public String getOrDefault(String elementId, String defaultValue) {
            String en = english.get(elementId);
            return en != null ? en : base.getOrDefault(elementId, defaultValue);
        }

        @Override
        public boolean has(String elementId) {
            return english.containsKey(elementId) || base.has(elementId);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return base.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText logicalOrderText) {
            return base.getVisualOrder(logicalOrderText);
        }

        @Override
        public Map<String, String> getLanguageData() {
            if (merged == null) {
                Map<String, String> m = new HashMap<>(base.getLanguageData());
                m.putAll(english);
                merged = Map.copyOf(m);
            }
            return merged;
        }

        @Override
        public @Nullable Component getComponent(String key) {
            return english.containsKey(key) ? null : base.getComponent(key);
        }
    }
}
