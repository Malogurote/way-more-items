package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import waymoreitems.WmiMod;
import waymoreitems.WmiPayloads;
import waymoreitems.core.CatalogItem;
import waymoreitems.core.GuiLayout;
import waymoreitems.core.InventorySorter;
import waymoreitems.core.PinnedChecklist;
import waymoreitems.core.RecipeIndex;
import waymoreitems.core.SettingsFile;
import waymoreitems.core.ThemePalette;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Réglages de l'interface (fenêtre « Paramètres », positions et tailles réglées à la souris), Notes et objectifs
 * compris, lus et enregistrés dans {@code config/wmi-client.properties} par {@link SettingsFile}.
 */
public final class WmiSettings {

    public static final int GRID_ICON_MIN = 12;
    public static final int GRID_ICON_MAX = 32;
    public static final int GRID_COUNT_MAX = 24;
    public static final int NOTES_DEPTH_MAX = 5;
    static final double HUD_SCALE_MIN = 0.75;
    static final double HUD_SCALE_MAX = 2.5;

    private final PinnedChecklist checklist;
    /** Objectifs tels qu'enregistrés la dernière fois (l'enregistrement différé compare à cette valeur). */
    String lastSavedGoals = "";

    WmiTheme currentTheme = WmiTheme.ANTHRACITE;
    /** Dernier thème prédéfini choisi : rétabli quand « Personnaliser » est désactivé. */
    WmiTheme presetTheme = WmiTheme.ANTHRACITE;
    /** Couleur du thème « Personnalisé » en teinte, saturation, valeur (0 à 1) ; par défaut un bleu moyen. */
    float customHue = 0.61F;
    float customSat = 0.62F;
    float customVal = 0.94F;
    InventorySorter.SortOrder sortOrder = InventorySorter.SortOrder.MOD_CATEGORY_NAME;
    /** Largeur du panneau de droite (proportion de la largeur d'écran, modifiable à la souris). */
    double rightPanelWidthRatio = 0.275;
    boolean directGiveMode = true;
    boolean vanillaBookEnabled = true;
    boolean hudPostItPinned = true;
    WmiToolbar.UtilityBarPosition utilityBarPosition = WmiToolbar.UtilityBarPosition.LEFT_VERTICAL;
    boolean showCenterLeftHud = false;
    /** Barre d'outils réduite : seules les sous-barres « Heure » et « Météo » restent affichées. */
    boolean toolbarCollapsed = false;
    /** Barre horizontale : libellés écrits à côté des icônes quand la place le permet (désactivé par défaut). */
    boolean toolbarLabels = false;
    /** Lignes verticales du widget : lever et coucher du soleil (gauche), de la lune (droite). */
    boolean showSunMoonLines = true;
    /** Horloge compacte en haut de l'écran de jeu (hors inventaire) et, en option, mod d'origine du biome. */
    boolean hudClockInGame = false;
    boolean hudClockShowMod = false;
    /** Widget de l'heure au plus petit cran dans le coin haut gauche de l'écran de jeu, Notes juste dessous. */
    boolean hudCornerWidget = true;
    /**
     * Taille propre de l'interface de WMI (voir {@link WmiUiScale}) : 1 à {@link #UI_SCALE_MAX}, {@link #UI_SCALE_GAME}
     * = comme le jeu, {@link #UI_SCALE_AUTO} = proportionnelle à la hauteur de la fenêtre (par défaut).
     */
    int uiScale = UI_SCALE_AUTO;
    static final int UI_SCALE_AUTO = -1;
    static final int UI_SCALE_GAME = 0;
    static final int UI_SCALE_MAX = 6;
    /** Widget de l'heure : échelle et position libre en proportion de l'écran (négative = placement automatique). */
    double hudScale = 1.0;
    double hudPosXRatio = -1.0;
    double hudPosYRatio = -1.0;
    /** Cohabitation avec JEI : en mode JEI, le panneau de droite de WMI laisse la place à la liste de JEI. */
    boolean jeiMode = false;
    int gridIconSize = 18;
    int gridColumns = 0;
    int gridRows = 0;
    /** Niveaux d'ingrédients dépliables dans les arbres des Notes (onglet « Notes » des paramètres ; 1 = premier niveau seul). */
    int notesTreeDepth = 1;
    /** Noms d'objets (blocs, objets, mods) en anglais dans WMI, l'interface restant dans la langue du jeu. */
    boolean englishNames = false;
    /** Notes affichées en jeu même vides (rappel « Notes [N] ») ; sinon masquées tant qu'elles sont vides. */
    boolean notesShowEmpty = true;
    /** Position libre des Notes dans l'inventaire (proportion de l'écran, coin haut gauche) ; négative = mise en page. */
    double notesPosXRatio = -1.0;
    double notesPosYRatio = -1.0;

    /** Ordre des objets du catalogue. */
    public enum CatalogSort { MOD, NAME, ID }

    /** Format de l'heure du widget : selon la langue (clé {@code wmi.time.twelve_hour}), 24 h ou 12 h. */
    public enum TimeFormat { LANGUAGE, H24, H12 }

    /** Ligne d'objectif atteinte : laissée en vert, barrée, ou masquée des Notes en jeu. */
    public enum DoneBehavior { KEEP, STRIKE, HIDE }

    /** Côté de l'écran de jeu où se placent le widget du coin et les Notes. */
    public enum HudSide { LEFT, RIGHT }

    public static final int PANEL_OPACITY_MIN = 40;

    /** Nom du mod d'origine ajouté aux infobulles des objets. */
    boolean modInTooltip = false;
    /** Opacité des panneaux de WMI, en pourcentage de l'opacité du thème. */
    int panelOpacity = 100;
    boolean catalogTabs = true;
    CatalogSort catalogSort = CatalogSort.MOD;
    boolean hideNoRecipe = false;
    /** Fluides seulement dans l'onglet « Fluides » du catalogue. */
    boolean hideFluids = false;
    /** Recherche du catalogue effacée au retour au jeu (sinon gardée jusqu'à la fermeture du jeu). */
    boolean clearSearchOnClose = false;
    TimeFormat timeFormat = TimeFormat.LANGUAGE;
    /** Heure en couleur d'alerte à l'approche de la nuit (apparition des monstres). */
    boolean nightAlert = false;
    DoneBehavior doneBehavior = DoneBehavior.KEEP;
    boolean doneSound = true;
    HudSide hudSide = HudSide.LEFT;
    /** Opacité du fond du widget du coin et des Notes en jeu, en pourcentage. */
    int hudOpacity = 100;
    boolean hideWithF1 = true;
    boolean hideWithDebug = true;

    WmiSettings(PinnedChecklist checklist) {
        this.checklist = checklist;
    }

    public WmiTheme theme() {
        return currentTheme;
    }

    // ==================== PERSISTANCE DES PARAMÈTRES CLIENT ====================

    void load() {
        // Lecture clé par clé avec repli (SettingsFile) : une valeur illisible ne coûte que cette clé ; un fichier
        // illisible est copié à côté (.illisible) avant d'être réécrit. Notes et objectifs sont lus en premier.
        SettingsFile props = SettingsFile.load(WmiPlatform.configFile("wmi-client.properties"));
        String saved = props.string("savedNotes", "");
        if (!saved.isEmpty() && checklist.notes().isEmpty()) {
            for (String line : saved.split("\n")) {
                if (line.length() >= 2) {
                    checklist.addNote(line.substring(1));
                    if (line.charAt(0) == '1') {
                        checklist.toggleNote(checklist.notes().size() - 1);
                    }
                }
            }
        }
        if (checklist.goalCount() == 0) {
            try {
                checklist.loadGoals(props.string("savedGoals", ""));
            } catch (RuntimeException ignored) {
                // objectifs illisibles : liste vide, le reste des réglages est lu normalement
            }
            lastSavedGoals = checklist.serializeGoals();
        }
        applyPreferences(props);
    }

    /**
     * Tous les réglages de WMI rétablis (fenêtre « Paramètres », « Tout réinitialiser ») : Notes et objectifs
     * conservés, touches de WMI remises par défaut par la fenêtre elle-même.
     */
    public void resetToDefaults() {
        customHue = 0.61F;
        customSat = 0.62F;
        customVal = 0.94F;
        applyPreferences(SettingsFile.empty());
        WmiNames.syncGameLanguage();
        save();
    }

    /** Réglages hors Notes et objectifs, lus clé par clé avec leur valeur par défaut. */
    private void applyPreferences(SettingsFile props) {
        String[] hsv = props.string("customHsv", "").split(",");
        if (hsv.length == 3) {
            try {
                customHue = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(hsv[0])));
                customSat = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(hsv[1])));
                customVal = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(hsv[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        String th = props.string("theme", WmiTheme.ANTHRACITE.name());
        WmiTheme preset = WmiTheme.preset(th);
        currentTheme = WmiTheme.CUSTOM_KEY.equals(th) ? WmiTheme.custom(customColor())
                : (preset != null ? preset : WmiTheme.ANTHRACITE);
        WmiTheme savedPreset = WmiTheme.preset(props.string("presetTheme", th));
        presetTheme = currentTheme.isCustom() ? (savedPreset != null ? savedPreset : WmiTheme.ANTHRACITE) : currentTheme;
        WmiVector.setVanillaTheme(currentTheme.vanilla());
        sortOrder = props.enumValue("sortOrder", InventorySorter.SortOrder.class, InventorySorter.SortOrder.MOD_CATEGORY_NAME);
        rightPanelWidthRatio = props.decimal("rightPanelWidthRatio", 0.275, 0.16, 0.65);
        vanillaBookEnabled = props.bool("vanillaBookEnabled", true);
        directGiveMode = props.bool("directGiveMode", true);
        hudPostItPinned = props.bool("hudPostItPinned", true);
        utilityBarPosition = props.enumValue("utilityBarPosition", WmiToolbar.UtilityBarPosition.class, WmiToolbar.UtilityBarPosition.LEFT_VERTICAL);
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
        WmiVector.setEnabled(props.bool("vectorUi", true));
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

    public void save() {
        Properties props = new Properties();
        props.setProperty("theme", currentTheme.name());
        props.setProperty("presetTheme", presetTheme.name());
        props.setProperty("customHsv", String.format(Locale.ROOT, "%.4f,%.4f,%.4f", customHue, customSat, customVal));
        props.setProperty("sortOrder", sortOrder.name());
        props.setProperty("rightPanelWidthRatio", String.format(Locale.ROOT, "%.4f", rightPanelWidthRatio));
        props.setProperty("vanillaBookEnabled", Boolean.toString(vanillaBookEnabled));
        props.setProperty("directGiveMode", Boolean.toString(directGiveMode));
        props.setProperty("hudPostItPinned", Boolean.toString(hudPostItPinned));
        props.setProperty("utilityBarPosition", utilityBarPosition.name());
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
        StringBuilder notesText = new StringBuilder();
        for (PinnedChecklist.NoteEntry n : checklist.notes()) {
            if (notesText.length() > 0) {
                notesText.append('\n');
            }
            notesText.append(n.done() ? '1' : '0').append(n.text().replace('\n', ' '));
        }
        props.setProperty("savedNotes", notesText.toString());
        lastSavedGoals = checklist.serializeGoals();
        props.setProperty("savedGoals", lastSavedGoals);
        props.setProperty("notesTreeDepth", Integer.toString(notesTreeDepth));
        props.setProperty("englishNames", Boolean.toString(englishNames));
        props.setProperty("notesShowEmpty", Boolean.toString(notesShowEmpty));
        props.setProperty("notesPosXRatio", String.format(Locale.ROOT, "%.5f", notesPosXRatio));
        props.setProperty("notesPosYRatio", String.format(Locale.ROOT, "%.5f", notesPosYRatio));
        props.setProperty("vectorUi", Boolean.toString(WmiVector.preferred()));
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
        try {
            SettingsFile.storeAtomic(WmiPlatform.configFile("wmi-client.properties"), props, "Way More Items (WMI) - Parametres Client");
        } catch (IOException | RuntimeException ignored) {
            // enregistrement retenté à la prochaine modification ; l'ancien fichier reste intact (écriture atomique)
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ==================== ACCÈS POUR LA FENÊTRE « PARAMÈTRES » (enregistrement immédiat) ====================

    public boolean vanillaBookEnabled() {
        return vanillaBookEnabled;
    }


    public void setVanillaBookEnabled(boolean value) {
        vanillaBookEnabled = value;
        save();
    }


    public void cycleThemeFromSettings(int delta) {
        cycleTheme(delta);
    }

    public void toggleVectorFromSettings() {
        toggleVectorUi();
    }

    public int gridIconSize() {
        return gridIconSize;
    }

    public void setGridIconSize(int value) {
        gridIconSize = clamp(value, GRID_ICON_MIN, GRID_ICON_MAX);
        save();
    }

    public int gridColumns() {
        return gridColumns;
    }

    public void setGridColumns(int value) {
        gridColumns = clamp(value, 0, GRID_COUNT_MAX);
        save();
    }

    public int gridRows() {
        return gridRows;
    }

    public void setGridRows(int value) {
        gridRows = clamp(value, 0, GRID_COUNT_MAX);
        save();
    }

    public WmiToolbar.UtilityBarPosition utilityBarPosition() {
        return utilityBarPosition;
    }

    public void cycleUtilityBarPosition(int delta) {
        WmiToolbar.UtilityBarPosition[] vals = WmiToolbar.UtilityBarPosition.values();
        utilityBarPosition = vals[Math.floorMod(utilityBarPosition.ordinal() + delta, vals.length)];
        save();
    }

    public boolean toolbarLabels() {
        return toolbarLabels;
    }

    public void toggleToolbarLabels() {
        toolbarLabels = !toolbarLabels;
        save();
    }

    public boolean showCenterLeftHud() {
        return showCenterLeftHud;
    }

    public boolean showSunMoonLines() {
        return showSunMoonLines;
    }

    public void setShowSunMoonLines(boolean value) {
        showSunMoonLines = value;
        save();
    }

    public int uiScale() {
        return uiScale;
    }

    /** Taille de l'interface de WMI : automatique (-1), comme le jeu (0), puis 1 à {@link #UI_SCALE_MAX}. */
    public void setUiScale(int value) {
        uiScale = Math.max(UI_SCALE_AUTO, Math.min(UI_SCALE_MAX, value));
        save();
    }

    public boolean hudCornerWidget() {
        return hudCornerWidget;
    }

    public void setHudCornerWidget(boolean value) {
        hudCornerWidget = value;
        save();
    }

    public boolean hudClockInGame() {
        return hudClockInGame;
    }

    public void setHudClockInGame(boolean value) {
        hudClockInGame = value;
        save();
    }

    public boolean hudClockShowMod() {
        return hudClockShowMod;
    }

    public void setHudClockShowMod(boolean value) {
        hudClockShowMod = value;
        save();
    }

    /** Taille et position du widget de l'heure : retour au placement automatique à l'échelle 100 %. */
    public void resetHudLayout() {
        hudScale = 1.0;
        hudPosXRatio = -1.0;
        hudPosYRatio = -1.0;
        save();
    }

    public void setShowCenterLeftHud(boolean value) {
        showCenterLeftHud = value;
        save();
    }

    public boolean hudNotesPinned() {
        return hudPostItPinned;
    }

    public void setHudNotesPinned(boolean value) {
        hudPostItPinned = value;
        save();
    }

    public boolean notesShowEmpty() {
        return notesShowEmpty;
    }

    public void setNotesShowEmpty(boolean value) {
        notesShowEmpty = value;
        save();
    }

    public boolean englishNames() {
        return englishNames;
    }

    public void setEnglishNames(boolean value) {
        englishNames = value;
        save();
        // Table de traduction du jeu posée ou retirée tout de suite ; l'index de WMI suit seul (changement de table)
        WmiNames.syncGameLanguage();
    }

    public int notesTreeDepth() {
        return notesTreeDepth;
    }

    public void setNotesTreeDepth(int value) {
        notesTreeDepth = clamp(value, 1, NOTES_DEPTH_MAX);
        save();
    }

    /** Vrai si JEI est installé et choisi comme navigateur de recettes (le panneau de droite de WMI est alors masqué). */
    public boolean jeiModeActive() {
        return jeiMode && WmiPlatform.jeiInstalled();
    }

    public void toggleRecipeViewer() {
        jeiMode = !jeiMode;
        save();
    }

    void toggleVectorUi() {
        WmiVector.setEnabled(!WmiVector.preferred());
        save();
    }

    /** Thème choisi dans le nuancier : un préréglage, ou « Personnalisé » ({@code null}). */
    public void selectTheme(@Nullable WmiTheme preset) {
        currentTheme = preset != null ? preset : WmiTheme.custom(customColor());
        if (preset != null) {
            presetTheme = preset;
        }
        WmiVector.setVanillaTheme(currentTheme.vanilla());
        save();
    }

    /** Thème prédéfini suivant ou précédent ({@code delta} = ±1), « Personnalisé » exclu. */
    public void cyclePreset(int delta) {
        int index = Math.max(0, WmiTheme.PRESETS.indexOf(presetTheme));
        selectTheme(WmiTheme.PRESETS.get(Math.floorMod(index + delta, WmiTheme.PRESETS.size())));
    }

    /** Interrupteur « Personnaliser » : thème tiré de la couleur choisie, ou retour au dernier thème prédéfini. */
    public void setCustomize(boolean on) {
        selectTheme(on ? null : presetTheme);
    }

    /** Fond de panneau du thème actif, à l'opacité réglée dans « Paramètres ». */
    public int panelBg() {
        return scaleAlpha(currentTheme.panelBg, panelOpacity / 100.0);
    }

    /** Couleur ARGB dont l'alpha est multiplié par {@code factor} (0 à 1). */
    static int scaleAlpha(int argb, double factor) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0, Math.min(1.0, factor)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Parcourt les préréglages puis « Personnalisé » (dernier de la liste). */
    void cycleTheme(int delta) {
        int count = WmiTheme.PRESETS.size() + 1;
        int index = currentTheme.isCustom() ? WmiTheme.PRESETS.size() : Math.max(0, WmiTheme.PRESETS.indexOf(currentTheme));
        int next = Math.floorMod(index + delta, count);
        currentTheme = next == WmiTheme.PRESETS.size() ? WmiTheme.custom(customColor()) : WmiTheme.PRESETS.get(next);
        WmiVector.setVanillaTheme(currentTheme.vanilla());
        save();
    }

    /** Couleur de base du thème « Personnalisé » (0xRRGGBB), tirée de la teinte, de la saturation et de la valeur. */
    public int customColor() {
        return ThemePalette.hsvToRgb(customHue, customSat, customVal);
    }

    public float customHue() {
        return customHue;
    }

    public float customSat() {
        return customSat;
    }

    public float customVal() {
        return customVal;
    }

    /**
     * Change la couleur personnalisée (valeurs de 0 à 1) et l'applique aussitôt.
     * {@code save} à faux pendant un glisser (enregistrement au relâchement).
     */
    public void setCustomHsv(float hue, float sat, float val, boolean save) {
        customHue = Math.max(0.0F, Math.min(1.0F, hue));
        customSat = Math.max(0.0F, Math.min(1.0F, sat));
        customVal = Math.max(0.0F, Math.min(1.0F, val));
        currentTheme = WmiTheme.custom(customColor());
        WmiVector.setVanillaTheme(false);
        if (save) {
            save();
        }
    }

    public void saveSettingsFromScreen() {
        save();
    }
}
