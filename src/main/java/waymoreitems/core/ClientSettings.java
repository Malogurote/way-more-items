package waymoreitems.core;

import java.util.Locale;
import java.util.Properties;

/**
 * Réglages typés de l'interface, sans dépendance au jeu : valeurs par défaut, bornes, lecture clé par clé
 * ({@link SettingsFile}) et écriture. Les réglages liés au rendu (thème, barre d'outils, dessin vectoriel) et
 * l'enregistrement sur disque restent dans l'adaptateur client, qui étend cette classe.
 */
public class ClientSettings {

    public static final int GRID_ICON_MIN = 12;
    public static final int GRID_ICON_MAX = 32;
    public static final int GRID_COUNT_MAX = 24;
    public static final int NOTES_DEPTH_MAX = 5;
    public static final double HUD_SCALE_MIN = 0.75;
    public static final double HUD_SCALE_MAX = 2.5;
    public static final int PANEL_OPACITY_MIN = 40;
    /** Taille de l'interface : proportionnelle à la hauteur de la fenêtre (par défaut). */
    public static final int UI_SCALE_AUTO = -1;
    /** Taille de l'interface : comme le jeu. */
    public static final int UI_SCALE_GAME = 0;
    public static final int UI_SCALE_MAX = 6;
    public static final float DEFAULT_CUSTOM_HUE = 0.61F;
    public static final float DEFAULT_CUSTOM_SAT = 0.62F;
    public static final float DEFAULT_CUSTOM_VAL = 0.94F;

    /** Ordre des objets du catalogue. */
    public enum CatalogSort { MOD, NAME, ID }

    /** Format de l'heure du widget : selon la langue, 24 h ou 12 h. */
    public enum TimeFormat { LANGUAGE, H24, H12 }

    /** Ligne d'objectif atteinte : laissée en vert, barrée, ou masquée des Notes en jeu. */
    public enum DoneBehavior { KEEP, STRIKE, HIDE }

    /** Côté de l'écran de jeu où se placent le widget du coin et les Notes. */
    public enum HudSide { LEFT, RIGHT }

    /** Couleur du thème « Personnalisé » en teinte, saturation, valeur (0 à 1) ; par défaut un bleu moyen. */
    public float customHue = DEFAULT_CUSTOM_HUE;
    public float customSat = DEFAULT_CUSTOM_SAT;
    public float customVal = DEFAULT_CUSTOM_VAL;
    public InventorySorter.SortOrder sortOrder = InventorySorter.SortOrder.MOD_CATEGORY_NAME;
    /** Largeur du panneau de droite (proportion de la largeur d'écran, modifiable à la souris). */
    public double rightPanelWidthRatio = 0.275;
    public boolean directGiveMode = true;
    public boolean vanillaBookEnabled = true;
    public boolean hudPostItPinned = true;
    public boolean showCenterLeftHud = false;
    /** Barre d'outils réduite : seules les sous-barres « Heure » et « Météo » restent affichées. */
    public boolean toolbarCollapsed = false;
    /** Barre horizontale : libellés écrits à côté des icônes quand la place le permet. */
    public boolean toolbarLabels = false;
    /** Lignes verticales du widget : lever et coucher du soleil (gauche), de la lune (droite). */
    public boolean showSunMoonLines = true;
    /** Horloge compacte en haut de l'écran de jeu (hors inventaire) et, en option, mod d'origine du biome. */
    public boolean hudClockInGame = false;
    public boolean hudClockShowMod = false;
    /** Widget de l'heure au plus petit cran dans le coin haut gauche de l'écran de jeu, Notes juste dessous. */
    public boolean hudCornerWidget = true;
    /** Taille propre de l'interface : 1 à {@link #UI_SCALE_MAX}, {@link #UI_SCALE_GAME} ou {@link #UI_SCALE_AUTO}. */
    public int uiScale = UI_SCALE_AUTO;
    /** Widget de l'heure : échelle et position libre en proportion de l'écran (négative = placement automatique). */
    public double hudScale = 1.0;
    public double hudPosXRatio = -1.0;
    public double hudPosYRatio = -1.0;
    /** Cohabitation avec JEI : en mode JEI, le panneau de droite laisse la place à la liste de JEI. */
    public boolean jeiMode = false;
    public int gridIconSize = 18;
    public int gridColumns = 0;
    public int gridRows = 0;
    /** Niveaux d'ingrédients dépliables dans les arbres des Notes (1 = premier niveau seul). */
    public int notesTreeDepth = 1;
    /** Noms d'objets (blocs, objets, mods) en anglais, l'interface restant dans la langue du jeu. */
    public boolean englishNames = false;
    /** Notes affichées en jeu même vides ; sinon masquées tant qu'elles sont vides. */
    public boolean notesShowEmpty = true;
    /** Position libre des Notes dans l'inventaire (proportion de l'écran, coin haut gauche) ; négative = mise en page. */
    public double notesPosXRatio = -1.0;
    public double notesPosYRatio = -1.0;
    /** Nom du mod d'origine ajouté aux infobulles des objets. */
    public boolean modInTooltip = false;
    /** Opacité des panneaux, en pourcentage de l'opacité du thème. */
    public int panelOpacity = 100;
    public boolean catalogTabs = true;
    public CatalogSort catalogSort = CatalogSort.MOD;
    public boolean hideNoRecipe = false;
    /** Fluides seulement dans l'onglet « Fluides » du catalogue. */
    public boolean hideFluids = false;
    /** Recherche du catalogue effacée au retour au jeu (sinon gardée jusqu'à la fermeture du jeu). */
    public boolean clearSearchOnClose = false;
    public TimeFormat timeFormat = TimeFormat.LANGUAGE;
    /** Heure en couleur d'alerte à l'approche de la nuit (apparition des monstres). */
    public boolean nightAlert = false;
    public DoneBehavior doneBehavior = DoneBehavior.KEEP;
    public boolean doneSound = true;
    public HudSide hudSide = HudSide.LEFT;
    /** Opacité du fond du widget du coin et des Notes en jeu, en pourcentage. */
    public int hudOpacity = 100;
    public boolean hideWithF1 = true;
    public boolean hideWithDebug = true;

    /**
     * Réglages lus clé par clé avec leur valeur par défaut et leurs bornes. La couleur personnalisée n'est changée
     * que si le fichier en donne une lisible.
     */
    public void readFrom(SettingsFile props) {
        String[] hsv = props.string("customHsv", "").split(",");
        if (hsv.length == 3) {
            try {
                customHue = clamp01(Float.parseFloat(hsv[0]));
                customSat = clamp01(Float.parseFloat(hsv[1]));
                customVal = clamp01(Float.parseFloat(hsv[2]));
            } catch (NumberFormatException ignored) {
                // couleur illisible : la couleur courante est gardée
            }
        }
        sortOrder = props.enumValue("sortOrder", InventorySorter.SortOrder.class, InventorySorter.SortOrder.MOD_CATEGORY_NAME);
        rightPanelWidthRatio = props.decimal("rightPanelWidthRatio", 0.275, 0.16, 0.65);
        vanillaBookEnabled = props.bool("vanillaBookEnabled", true);
        directGiveMode = props.bool("directGiveMode", true);
        hudPostItPinned = props.bool("hudPostItPinned", true);
        showCenterLeftHud = props.bool("showCenterLeftHud", false);
        toolbarCollapsed = props.bool("toolbarCollapsed", false);
        toolbarLabels = props.bool("toolbarLabels", false);
        showSunMoonLines = props.bool("hudSunMoonLines", true);
        hudClockInGame = props.bool("hudClockInGame", false);
        hudClockShowMod = props.bool("hudClockShowMod", false);
        hudCornerWidget = props.bool("hudCornerWidget", true);
        uiScale = props.integer("uiScale", UI_SCALE_AUTO, UI_SCALE_AUTO, UI_SCALE_MAX);
        hudScale = props.decimal("hudScale", 1.0, HUD_SCALE_MIN, HUD_SCALE_MAX);
        hudPosXRatio = props.decimal("hudPosXRatio", -1);
        hudPosYRatio = props.decimal("hudPosYRatio", -1);
        jeiMode = "JEI".equals(props.string("recipeViewer", "WMI"));
        gridIconSize = props.integer("gridIconSize", 18, GRID_ICON_MIN, GRID_ICON_MAX);
        gridColumns = props.integer("gridColumns", 0, 0, GRID_COUNT_MAX);
        gridRows = props.integer("gridRows", 0, 0, GRID_COUNT_MAX);
        notesTreeDepth = props.integer("notesTreeDepth", 1, 1, NOTES_DEPTH_MAX);
        englishNames = props.bool("englishNames", false);
        notesShowEmpty = props.bool("notesShowEmpty", true);
        notesPosXRatio = props.decimal("notesPosXRatio", -1);
        notesPosYRatio = props.decimal("notesPosYRatio", -1);
        modInTooltip = props.bool("modInTooltip", false);
        panelOpacity = props.integer("panelOpacity", 100, PANEL_OPACITY_MIN, 100);
        catalogTabs = props.bool("catalogTabs", true);
        catalogSort = props.enumValue("catalogSort", CatalogSort.class, CatalogSort.MOD);
        hideNoRecipe = props.bool("hideNoRecipe", false);
        hideFluids = props.bool("hideFluids", false);
        clearSearchOnClose = props.bool("clearSearchOnClose", false);
        timeFormat = props.enumValue("timeFormat", TimeFormat.class, TimeFormat.LANGUAGE);
        nightAlert = props.bool("nightAlert", false);
        doneBehavior = props.enumValue("doneBehavior", DoneBehavior.class, DoneBehavior.KEEP);
        doneSound = props.bool("doneSound", true);
        hudSide = props.enumValue("hudSide", HudSide.class, HudSide.LEFT);
        hudOpacity = props.integer("hudOpacity", 100, 0, 100);
        hideWithF1 = props.bool("hideWithF1", true);
        hideWithDebug = props.bool("hideWithDebug", true);
    }

    /** Écrit les réglages sous les clés relues par {@link #readFrom}. */
    public void writeTo(Properties props) {
        props.setProperty("customHsv", String.format(Locale.ROOT, "%.4f,%.4f,%.4f", customHue, customSat, customVal));
        props.setProperty("sortOrder", sortOrder.name());
        props.setProperty("rightPanelWidthRatio", String.format(Locale.ROOT, "%.4f", rightPanelWidthRatio));
        props.setProperty("vanillaBookEnabled", Boolean.toString(vanillaBookEnabled));
        props.setProperty("directGiveMode", Boolean.toString(directGiveMode));
        props.setProperty("hudPostItPinned", Boolean.toString(hudPostItPinned));
        props.setProperty("showCenterLeftHud", Boolean.toString(showCenterLeftHud));
        props.setProperty("toolbarCollapsed", Boolean.toString(toolbarCollapsed));
        props.setProperty("toolbarLabels", Boolean.toString(toolbarLabels));
        props.setProperty("hudSunMoonLines", Boolean.toString(showSunMoonLines));
        props.setProperty("hudClockInGame", Boolean.toString(hudClockInGame));
        props.setProperty("hudClockShowMod", Boolean.toString(hudClockShowMod));
        props.setProperty("hudCornerWidget", Boolean.toString(hudCornerWidget));
        props.setProperty("uiScale", Integer.toString(uiScale));
        props.setProperty("hudScale", String.format(Locale.ROOT, "%.3f", hudScale));
        props.setProperty("hudPosXRatio", String.format(Locale.ROOT, "%.5f", hudPosXRatio));
        props.setProperty("hudPosYRatio", String.format(Locale.ROOT, "%.5f", hudPosYRatio));
        props.setProperty("recipeViewer", jeiMode ? "JEI" : "WMI");
        props.setProperty("gridIconSize", Integer.toString(gridIconSize));
        props.setProperty("gridColumns", Integer.toString(gridColumns));
        props.setProperty("gridRows", Integer.toString(gridRows));
        props.setProperty("notesTreeDepth", Integer.toString(notesTreeDepth));
        props.setProperty("englishNames", Boolean.toString(englishNames));
        props.setProperty("notesShowEmpty", Boolean.toString(notesShowEmpty));
        props.setProperty("notesPosXRatio", String.format(Locale.ROOT, "%.5f", notesPosXRatio));
        props.setProperty("notesPosYRatio", String.format(Locale.ROOT, "%.5f", notesPosYRatio));
        props.setProperty("modInTooltip", Boolean.toString(modInTooltip));
        props.setProperty("panelOpacity", Integer.toString(panelOpacity));
        props.setProperty("catalogTabs", Boolean.toString(catalogTabs));
        props.setProperty("catalogSort", catalogSort.name());
        props.setProperty("hideNoRecipe", Boolean.toString(hideNoRecipe));
        props.setProperty("hideFluids", Boolean.toString(hideFluids));
        props.setProperty("clearSearchOnClose", Boolean.toString(clearSearchOnClose));
        props.setProperty("timeFormat", timeFormat.name());
        props.setProperty("nightAlert", Boolean.toString(nightAlert));
        props.setProperty("doneBehavior", doneBehavior.name());
        props.setProperty("doneSound", Boolean.toString(doneSound));
        props.setProperty("hudSide", hudSide.name());
        props.setProperty("hudOpacity", Integer.toString(hudOpacity));
        props.setProperty("hideWithF1", Boolean.toString(hideWithF1));
        props.setProperty("hideWithDebug", Boolean.toString(hideWithDebug));
    }

    /** Couleur personnalisée remise à sa valeur par défaut. */
    public void resetCustomColor() {
        customHue = DEFAULT_CUSTOM_HUE;
        customSat = DEFAULT_CUSTOM_SAT;
        customVal = DEFAULT_CUSTOM_VAL;
    }

    /** Couleur de base du thème « Personnalisé » (0xRRGGBB), tirée de la teinte, de la saturation et de la valeur. */
    public int customColor() {
        return ThemePalette.hsvToRgb(customHue, customSat, customVal);
    }

    /**
     * Notes enregistrées sur une ligne chacune, préfixée de {@code 1} (cochée) ou {@code 0} ; un retour à la ligne
     * dans le texte d'une note devient une espace.
     */
    public static String encodeNotes(PinnedChecklist checklist) {
        StringBuilder text = new StringBuilder();
        for (PinnedChecklist.NoteEntry n : checklist.notes()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(n.done() ? '1' : '0').append(n.text().replace('\n', ' '));
        }
        return text.toString();
    }

    /** Ajoute à {@code checklist} les notes écrites par {@link #encodeNotes} (lignes de moins de deux caractères ignorées). */
    public static void decodeNotes(String saved, PinnedChecklist checklist) {
        if (saved.isEmpty()) {
            return;
        }
        for (String line : saved.split("\n")) {
            if (line.length() >= 2) {
                checklist.addNote(line.substring(1));
                if (line.charAt(0) == '1') {
                    checklist.toggleNote(checklist.notes().size() - 1);
                }
            }
        }
    }

    /** Couleur ARGB dont l'alpha est multiplié par {@code factor} (0 à 1). */
    public static int scaleAlpha(int argb, double factor) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0, Math.min(1.0, factor)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    protected static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    protected static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }
}
