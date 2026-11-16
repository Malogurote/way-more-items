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
import waymoreitems.core.ClientSettings;
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
 * compris, lus et enregistrés dans {@code config/wmi-client.properties} par {@link SettingsFile}. Les réglages sans
 * lien avec le rendu, leurs bornes et leur lecture sont dans {@link ClientSettings} (noyau, testé).
 */
public final class WmiSettings extends ClientSettings {

    private final PinnedChecklist checklist;
    /** Objectifs tels qu'enregistrés la dernière fois (l'enregistrement différé compare à cette valeur). */
    String lastSavedGoals = "";

    WmiTheme currentTheme = WmiTheme.ANTHRACITE;
    /** Dernier thème prédéfini choisi : rétabli quand « Personnaliser » est désactivé. */
    WmiTheme presetTheme = WmiTheme.ANTHRACITE;
    WmiToolbar.UtilityBarPosition utilityBarPosition = WmiToolbar.UtilityBarPosition.LEFT_VERTICAL;

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
        if (checklist.notes().isEmpty()) {
            decodeNotes(props.string("savedNotes", ""), checklist);
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
        resetCustomColor();
        applyPreferences(SettingsFile.empty());
        WmiNames.syncGameLanguage();
        save();
    }

    /** Réglages hors Notes et objectifs, lus clé par clé avec leur valeur par défaut. */
    private void applyPreferences(SettingsFile props) {
        readFrom(props);
        String th = props.string("theme", WmiTheme.ANTHRACITE.name());
        WmiTheme preset = WmiTheme.preset(th);
        currentTheme = WmiTheme.CUSTOM_KEY.equals(th) ? WmiTheme.custom(customColor())
                : (preset != null ? preset : WmiTheme.ANTHRACITE);
        WmiTheme savedPreset = WmiTheme.preset(props.string("presetTheme", th));
        presetTheme = currentTheme.isCustom() ? (savedPreset != null ? savedPreset : WmiTheme.ANTHRACITE) : currentTheme;
        WmiVector.setVanillaTheme(currentTheme.vanilla());
        utilityBarPosition = props.enumValue("utilityBarPosition", WmiToolbar.UtilityBarPosition.class, WmiToolbar.UtilityBarPosition.LEFT_VERTICAL);
        WmiVector.setEnabled(props.bool("vectorUi", true));
    }

    public void save() {
        Properties props = new Properties();
        writeTo(props);
        props.setProperty("theme", currentTheme.name());
        props.setProperty("presetTheme", presetTheme.name());
        props.setProperty("utilityBarPosition", utilityBarPosition.name());
        props.setProperty("vectorUi", Boolean.toString(WmiVector.preferred()));
        props.setProperty("savedNotes", encodeNotes(checklist));
        lastSavedGoals = checklist.serializeGoals();
        props.setProperty("savedGoals", lastSavedGoals);
        try {
            SettingsFile.storeAtomic(WmiPlatform.configFile("wmi-client.properties"), props, "Way More Items (WMI) - Parametres Client");
        } catch (IOException | RuntimeException ignored) {
            // enregistrement retenté à la prochaine modification ; l'ancien fichier reste intact (écriture atomique)
        }
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

    /** Parcourt les préréglages puis « Personnalisé » (dernier de la liste). */
    void cycleTheme(int delta) {
        int count = WmiTheme.PRESETS.size() + 1;
        int index = currentTheme.isCustom() ? WmiTheme.PRESETS.size() : Math.max(0, WmiTheme.PRESETS.indexOf(currentTheme));
        int next = Math.floorMod(index + delta, count);
        currentTheme = next == WmiTheme.PRESETS.size() ? WmiTheme.custom(customColor()) : WmiTheme.PRESETS.get(next);
        WmiVector.setVanillaTheme(currentTheme.vanilla());
        save();
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
        customHue = clamp01(hue);
        customSat = clamp01(sat);
        customVal = clamp01(val);
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
