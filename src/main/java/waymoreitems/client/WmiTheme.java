package waymoreitems.client;

import org.jspecify.annotations.Nullable;
import waymoreitems.core.ThemePalette;

import java.util.List;

/**
 * Thème d'interface : préréglages faits main, palettes dérivées d'une couleur de base
 * ({@link ThemePalette#fromBase}) et thème « Personnalisé » à la couleur choisie par le joueur.
 * Tous, sauf « Minecraft », gardent le style flouté et transparent.
 */
public final class WmiTheme {
    public static final String CUSTOM_KEY = "PERSO";

    public static final WmiTheme BLANC = new WmiTheme("BLANC", "wmi.theme.blanc", 0xA8FAFCFF, 0xC6FFFFFF, 0x8FFFFFFF, 0xF2FFFFFF, 0xE6FFFFFF, 0x240F172A,
            0xFF18202C, 0xFF526075, 0xFF2563EB, 0x302563EB, 0xFF7C3AED, 0x307C3AED, 0xFFD97706, 0xFF059669, true, false);
    public static final WmiTheme CREME = new WmiTheme("CREME", "wmi.theme.creme", 0xB0F7EFE0, 0xCCFDF8EE, 0x94FFFAF0, 0xF5FFFDF8, 0xE0FFF8E7, 0x284E381E,
            0xFF2C2217, 0xFF6E5A44, 0xFFB45309, 0x33B45309, 0xFF7C3AED, 0x307C3AED, 0xFFD97706, 0xFF15803D, true, false);
    public static final WmiTheme SAUGE = derived("SAUGE", "wmi.theme.sauge", 0x9DB8A0);
    public static final WmiTheme LAVANDE = derived("LAVANDE", "wmi.theme.lavande", 0xB9A9E0);
    public static final WmiTheme ROSE = derived("ROSE", "wmi.theme.rose", 0xE8B4C0);
    public static final WmiTheme SABLE = derived("SABLE", "wmi.theme.sable", 0xD9C49A);
    public static final WmiTheme ANTHRACITE = new WmiTheme("ANTHRACITE", "wmi.theme.anthracite", 0xB82B2F36, 0xCC3A3F48, 0x941D2026, 0xE64B525F, 0x38FFFFFF, 0x20FFFFFF,
            0xFFF1F5F9, 0xFF94A3B8, 0xFF38BDF8, 0x3638BDF8, 0xFFC084FC, 0x36C084FC, 0xFFFBBF24, 0xFF34D399, false, false);
    public static final WmiTheme BLEU_FONCE = new WmiTheme("BLEU_FONCE", "wmi.theme.bleu_fonce", 0xB8121D35, 0xCC1B2A4A, 0x940C1427, 0xE8273C68, 0x4293C5FD, 0x2493C5FD,
            0xFFEFF6FF, 0xFF93C5FD, 0xFF60A5FA, 0x3860A5FA, 0xFFC084FC, 0x38C084FC, 0xFFFBBF24, 0xFF34D399, false, false);
    public static final WmiTheme OCEAN = derived("OCEAN", "wmi.theme.ocean", 0x0E3B43);
    public static final WmiTheme FORET = derived("FORET", "wmi.theme.foret", 0x1F3B2D);
    public static final WmiTheme BORDEAUX = derived("BORDEAUX", "wmi.theme.bordeaux", 0x4A1624);
    public static final WmiTheme PRUNE = derived("PRUNE", "wmi.theme.prune", 0x33203F);
    public static final WmiTheme NOIR = new WmiTheme("NOIR", "wmi.theme.noir", 0xC40E0F12, 0xD6181A1F, 0xA608090B, 0xEE2A2E37, 0x2EFFFFFF, 0x1AFFFFFF,
            0xFFFAFAFA, 0xFFA1A1AA, 0xFFA78BFA, 0x38A78BFA, 0xFFC084FC, 0x38C084FC, 0xFFFBBF24, 0xFF4ADE80, false, false);
    public static final WmiTheme VANILLA = new WmiTheme("VANILLA", "wmi.theme.vanilla", 0xFFC6C6C6, 0xFFB8B8B8, 0xFF8B8B8B, 0xFFA2A2A2, 0xFF000000, 0xFF555555,
            0xFF404040, 0xFF6A6A6A, 0xFF2E5E9E, 0x552E5E9E, 0xFF7B3FA0, 0x557B3FA0, 0xFF9A6B00, 0xFF2E7D32, true, true);

    /** Préréglages dans l'ordre du sélecteur (clairs, puis sombres, puis Minecraft). */
    public static final List<WmiTheme> PRESETS = List.of(BLANC, CREME, SAUGE, LAVANDE, ROSE, SABLE,
            ANTHRACITE, BLEU_FONCE, OCEAN, FORET, BORDEAUX, PRUNE, NOIR, VANILLA);

    public final String key;
    /** Clé de langue du nom du thème (voir {@link #label()}). */
    public final String label;
    public final int panelBg;
    public final int cardBg;
    public final int slotBg;
    public final int slotHover;
    public final int border;
    public final int innerBorder;
    public final int textMain;
    public final int textMuted;
    public final int accent;
    public final int accentSoft;
    public final int purple;
    public final int purpleSoft;
    public final int gold;
    public final int green;
    public final boolean lightTextShadowOff;
    private final boolean vanilla;

    /** Nom du thème dans la langue du jeu. */
    public String label() {
        return WmiText.s(label);
    }

    private WmiTheme(String key, String label, int panelBg, int cardBg, int slotBg, int slotHover, int border, int innerBorder,
                    int textMain, int textMuted, int accent, int accentSoft, int purple, int purpleSoft,
                    int gold, int green, boolean lightTextShadowOff, boolean vanilla) {
        this.key = key;
        this.label = label;
        this.panelBg = panelBg;
        this.cardBg = cardBg;
        this.slotBg = slotBg;
        this.slotHover = slotHover;
        this.border = border;
        this.innerBorder = innerBorder;
        this.textMain = textMain;
        this.textMuted = textMuted;
        this.accent = accent;
        this.accentSoft = accentSoft;
        this.purple = purple;
        this.purpleSoft = purpleSoft;
        this.gold = gold;
        this.green = green;
        this.lightTextShadowOff = lightTextShadowOff;
        this.vanilla = vanilla;
    }

    private static WmiTheme derived(String key, String label, int baseRgb) {
        ThemePalette p = ThemePalette.fromBase(baseRgb);
        return new WmiTheme(key, label, p.panelBg(), p.cardBg(), p.slotBg(), p.slotHover(), p.border(), p.innerBorder(),
                p.textMain(), p.textMuted(), p.accent(), p.accentSoft(), p.purple(), p.purpleSoft(),
                p.gold(), p.green(), p.light(), false);
    }

    /** Thème « Personnalisé » dérivé de la couleur choisie (0xRRGGBB). */
    public static WmiTheme custom(int baseRgb) {
        return derived(CUSTOM_KEY, "wmi.theme.custom", baseRgb);
    }

    /** Préréglage portant cette clé, ou {@code null}. */
    public static @Nullable WmiTheme preset(String key) {
        for (WmiTheme t : PRESETS) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** Clé d'enregistrement (identique à l'ancien nom d'énumération pour les six thèmes d'origine). */
    public String name() {
        return key;
    }

    /** Thème « Minecraft Vanilla » : conteneurs gris biseautés et police d'origine, sans flou. */
    public boolean vanilla() {
        return vanilla;
    }

    public boolean isCustom() {
        return CUSTOM_KEY.equals(key);
    }
}
