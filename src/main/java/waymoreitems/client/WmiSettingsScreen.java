package waymoreitems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.jspecify.annotations.Nullable;
import waymoreitems.core.CatalogItem;
import waymoreitems.core.RecipeIndex;
import waymoreitems.core.ThemePalette;

/**
 * Fenêtre « Paramètres » de Way More Items : six onglets en colonne (Général, Interface, Catalogue, Widgets, HUD,
 * Touches), recherche parmi tous les réglages, description sous chaque réglage, raison affichée quand il est grisé,
 * clic droit pour la valeur par défaut. Chaque réglage s'applique et s'enregistre aussitôt dans
 * {@code config/wmi-client.properties} (les touches, dans {@code options.txt}, comme l'écran « Commandes »).
 */
public final class WmiSettingsScreen extends WmiScaledScreen {
    private static final int BOX_W = 404;
    private static final int BOX_H_MAX = 290;
    private static final int SIDE_W = 96;
    private static final int HEAD_H = 26;
    private static final int CTRL_H = 13;
    private static final int SCROLL_STEP = 24;
    /** Largeur minimale de l'aperçu du catalogue ; en deçà (écran trop étroit), l'aperçu n'est pas dessiné. */
    private static final int PREVIEW_MIN_W = 110;
    /** Crans de la taille du widget de l'inventaire (bornes de {@link WmiSettings#HUD_SCALE_MIN} à {@code MAX}). */
    private static final int[] WIDGET_STEPS = {75, 100, 125, 150, 200, 250};

    private enum Tab {
        GENERAL("general"), APPEARANCE("appearance"), INVENTORY("inventory"), CATALOG("catalog"), SCREEN("screen"), WIDGET("widget"),
        KEYS("keys");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private final @Nullable Screen parent;
    private final List<Zone> zones = new ArrayList<>();
    private final List<Zone> rowZones = new ArrayList<>();
    private final List<Drag> drags = new ArrayList<>();
    private Tab tab = Tab.GENERAL;
    private int scroll = 0;
    private int maxScroll = 0;
    /** Glisser en cours (curseur ou sélecteur de couleur), identifié par la ligne, ou {@code null}. */
    private @Nullable String dragging;
    /** Touche en attente de saisie (Échap annule), ou {@code null}. */
    private @Nullable KeyMapping capturing;
    /** Bouton armé pour une confirmation par second clic, ou {@code null}. */
    private @Nullable String armed;
    /** Thème quitté pour le thème Minecraft : fenêtre d'avertissement ouverte tant qu'il n'est pas nul. */
    private @Nullable WmiTheme vanillaWarningFrom;

    private String query = "";
    private boolean searchFocused = false;
    private final TextFieldHelper searchEdit = new TextFieldHelper(() -> query, v -> {
        query = v;
        scroll = 0;
    }, () -> TextFieldHelper.getClipboardContents(Minecraft.getInstance()),
            v -> TextFieldHelper.setClipboardContents(Minecraft.getInstance(), v), v -> v.length() <= 40);

    private String hexDraft = "";
    private boolean hexFocused = false;
    private final TextFieldHelper hexEdit = new TextFieldHelper(() -> hexDraft, this::onHexEdited,
            () -> TextFieldHelper.getClipboardContents(Minecraft.getInstance()),
            v -> TextFieldHelper.setClipboardContents(Minecraft.getInstance(), v), v -> v.length() <= 7);

    private record Zone(int x, int y, int w, int h, Runnable left, @Nullable Runnable right) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** Zone glissable : {@code onChange} reçoit la position relative (0 à 1) en abscisse et en ordonnée. */
    private record Drag(String id, int x, int y, int w, int h, Pos onChange) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        void apply(double mx, double my) {
            float fx = (float) Math.max(0.0, Math.min(1.0, (mx - x) / Math.max(1.0, w - 1.0)));
            float fy = (float) Math.max(0.0, Math.min(1.0, (my - y) / Math.max(1.0, h - 1.0)));
            onChange.accept(fx, fy);
        }
    }

    @FunctionalInterface
    private interface Pos {
        void accept(float x, float y);
    }

    // ==================== MODÈLE DES RÉGLAGES ====================

    /** Commande d'une ligne. */
    private sealed interface Control permits Toggle, Choice, Slider, KeyCtl, Button, Stepper, ColorPick, None {
    }

    private record Toggle(boolean value, Consumer<Boolean> set) implements Control {
    }

    private record Choice(List<String> labels, int selected, IntConsumer set) implements Control {
    }

    /** Curseur entier de {@code min} à {@code max} par pas de {@code step} ; {@code set} ne doit pas enregistrer. */
    private record Slider(int min, int max, int step, int value, IntFunction<String> format, IntConsumer set) implements Control {
    }

    private record KeyCtl(KeyMapping key) implements Control {
    }

    /** Bouton ; {@code confirm} : second clic exigé (le libellé devient « Confirmer ? »). */
    private record Button(String label, boolean confirm, Runnable action) implements Control {
    }

    /** Valeur parcourue par flèches : clic sur la valeur = suivante, clic droit = précédente. */
    private record Stepper(String value, Runnable prev, Runnable next) implements Control {
    }

    private record ColorPick() implements Control {
    }

    private record None() implements Control {
    }

    /**
     * Ligne de réglage : libellé et description traduits, commande, retour à la valeur par défaut (clic droit),
     * raison d'un réglage grisé, avertissement (conflit de touche).
     */
    private record Row(Tab tab, String group, String id, String label, @Nullable String desc, Control control,
                       @Nullable Runnable reset, @Nullable String disabled, @Nullable String warning) {
    }

    /** Commande placée sous la description (sélecteur de couleur, choix trop large pour la droite). */
    private boolean wide(Row r) {
        return r.control() instanceof ColorPick
                || (r.control() instanceof Choice c && (c.labels().size() > 4 || choiceWidth(c) > 150));
    }

    public WmiSettingsScreen(@Nullable Screen parent) {
        super(WmiText.c("wmi.settings.title"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String t(String key, Object... args) {
        return WmiText.s(key, args);
    }

    private Row row(Tab tab, String group, String id, Control control, @Nullable Runnable reset) {
        return row(tab, group, id, control, reset, null);
    }

    private Row row(Tab tab, String group, String id, Control control, @Nullable Runnable reset, @Nullable String disabled) {
        // Chaque réglage a sa description (clé « .desc », présente dans les six fichiers de langue)
        return new Row(tab, group, id, t("wmi.settings." + id), t("wmi.settings." + id + ".desc"), control, reset, disabled, null);
    }

    private static Toggle toggle(boolean value, Consumer<Boolean> set) {
        return new Toggle(value, set);
    }

    private static Choice choice(int selected, IntConsumer set, String... labelKeys) {
        List<String> labels = new ArrayList<>(labelKeys.length);
        for (String k : labelKeys) {
            labels.add(t(k));
        }
        return new Choice(labels, selected, set);
    }

    /** Tous les réglages, dans l'ordre d'affichage ; reconstruits à chaque image (valeurs toujours à jour). */
    private List<Row> rows() {
        WmiOverlay o = WmiClient.overlay();
        WmiSettings s = o.settings();
        WmiTheme th = s.theme();
        List<Row> r = new ArrayList<>();

        // ---- Général
        if (WmiPlatform.jeiInstalled()) {
            r.add(row(Tab.GENERAL, "recipes", "recipe_viewer", choice(s.jeiMode ? 1 : 0, i -> {
                if ((i == 1) != s.jeiMode) {
                    s.toggleRecipeViewer();
                }
            }, "wmi.settings.recipe_viewer.wmi", "wmi.settings.recipe_viewer.jei"), () -> {
                if (s.jeiMode) {
                    s.toggleRecipeViewer();
                }
            }));
        }
        r.add(row(Tab.GENERAL, "recipes", "vanilla_book", toggle(s.vanillaBookEnabled(), o::setVanillaBookEnabled),
                () -> o.setVanillaBookEnabled(true)));
        r.add(row(Tab.GENERAL, "recipes", "mod_in_tooltip", toggle(s.modInTooltip, v -> set(() -> s.modInTooltip = v)),
                () -> set(() -> s.modInTooltip = false)));
        r.add(row(Tab.GENERAL, "settings", "reset_all", new Button(t("wmi.settings.reset_all.button"), true, () -> {
            s.resetToDefaults();
            resetAllKeys();
        }), null));

        // ---- Apparence
        boolean custom = th.isCustom();
        r.add(row(Tab.APPEARANCE, "theme", "theme", new Stepper(s.presetTheme.label(), () -> stepTheme(-1), () -> stepTheme(1)),
                () -> s.selectTheme(WmiTheme.BLANC), custom ? t("wmi.settings.theme.custom_reason") : null));
        r.add(row(Tab.APPEARANCE, "theme", "customize", toggle(custom, s::setCustomize), () -> s.setCustomize(false)));
        if (custom) {
            r.add(row(Tab.APPEARANCE, "theme", "custom_color", new ColorPick(), () -> {
                s.setCustomHsv(0.61F, 0.62F, 0.94F, true);
                hexDraft = "";
            }));
        }
        r.add(row(Tab.APPEARANCE, "display", "render", choice(WmiVector.preferred() ? 0 : 1, i -> {
            if ((i == 0) != WmiVector.preferred()) {
                s.toggleVectorFromSettings();
            }
        }, "wmi.settings.render.smooth", "wmi.settings.render.pixels"), () -> {
            if (!WmiVector.preferred()) {
                s.toggleVectorFromSettings();
            }
        }, th.vanilla() ? t("wmi.settings.render.vanilla_reason") : null));
        int ui = s.uiScale();
        List<String> scales = new ArrayList<>();
        scales.add(t("wmi.settings.ui_scale.auto", WmiUiScale.targetScale()));
        scales.add(t("wmi.settings.ui_scale.game"));
        for (int i = 1; i <= WmiSettings.UI_SCALE_MAX; i++) {
            scales.add(Integer.toString(i));
        }
        r.add(new Row(Tab.APPEARANCE, "display", "ui_scale", t("wmi.settings.ui_scale"), t("wmi.settings.ui_scale.desc"),
                new Choice(scales, ui == WmiSettings.UI_SCALE_AUTO ? 0 : ui == WmiSettings.UI_SCALE_GAME ? 1 : ui + 1,
                        i -> s.setUiScale(i == 0 ? WmiSettings.UI_SCALE_AUTO : i == 1 ? WmiSettings.UI_SCALE_GAME : i - 1)),
                () -> s.setUiScale(WmiSettings.UI_SCALE_AUTO), null, null));
        r.add(row(Tab.APPEARANCE, "display", "panel_opacity", new Slider(WmiSettings.PANEL_OPACITY_MIN, 100, 5, s.panelOpacity,
                v -> v + " %", v -> s.panelOpacity = v), () -> set(() -> s.panelOpacity = 100), th.vanilla() ? t("wmi.settings.render.vanilla_reason") : null));
        WmiToolbar.UtilityBarPosition bar = s.utilityBarPosition();
        r.add(row(Tab.INVENTORY, "toolbar", "toolbar", choice(bar.ordinal(), i -> {
            s.utilityBarPosition = WmiToolbar.UtilityBarPosition.values()[i];
            s.save();
        }, "wmi.toolbar.left_vertical", "wmi.toolbar.top_horizontal", "wmi.toolbar.hidden"), () -> {
            s.utilityBarPosition = WmiToolbar.UtilityBarPosition.LEFT_VERTICAL;
            s.save();
        }));
        r.add(row(Tab.INVENTORY, "toolbar", "toolbar_labels", toggle(s.toolbarLabels(), v -> set(() -> s.toolbarLabels = v)),
                () -> set(() -> s.toolbarLabels = false),
                bar != WmiToolbar.UtilityBarPosition.TOP_HORIZONTAL ? t("wmi.settings.toolbar_labels.reason") : null));

        // ---- Catalogue
        r.add(row(Tab.CATALOG, "display", "icon_size", new Slider(WmiSettings.GRID_ICON_MIN, WmiSettings.GRID_ICON_MAX, 2, s.gridIconSize,
                v -> v + " px", v -> s.gridIconSize = v), () -> set(() -> s.gridIconSize = 18)));
        r.add(row(Tab.CATALOG, "display", "columns", new Slider(0, WmiSettings.GRID_COUNT_MAX, 1, s.gridColumns,
                WmiSettingsScreen::autoOrCount, v -> s.gridColumns = v), () -> set(() -> s.gridColumns = 0)));
        r.add(row(Tab.CATALOG, "display", "rows", new Slider(0, WmiSettings.GRID_COUNT_MAX, 1, s.gridRows,
                WmiSettingsScreen::autoOrCount, v -> s.gridRows = v), () -> set(() -> s.gridRows = 0)));
        r.add(row(Tab.CATALOG, "display", "catalog_tabs", toggle(s.catalogTabs, v -> set(() -> s.catalogTabs = v)),
                () -> set(() -> s.catalogTabs = true)));
        r.add(row(Tab.CATALOG, "display", "catalog_sort", choice(s.catalogSort.ordinal(),
                i -> set(() -> s.catalogSort = WmiSettings.CatalogSort.values()[i]),
                "wmi.settings.catalog_sort.mod", "wmi.settings.catalog_sort.name", "wmi.settings.catalog_sort.id"),
                () -> set(() -> s.catalogSort = WmiSettings.CatalogSort.MOD)));
        r.add(row(Tab.CATALOG, "display", "hide_no_recipe", toggle(s.hideNoRecipe, v -> set(() -> s.hideNoRecipe = v)),
                () -> set(() -> s.hideNoRecipe = false)));
        r.add(row(Tab.CATALOG, "search", "item_names", choice(s.englishNames() ? 1 : 0, i -> s.setEnglishNames(i == 1),
                "wmi.settings.item_names.game", "wmi.settings.item_names.english"), () -> s.setEnglishNames(false)));
        r.add(new Row(Tab.CATALOG, "search", "search_syntax", t("wmi.settings.search_syntax"),
                t("wmi.settings.search_syntax.desc", RecipeIndex.CRAFTABLE_KEYWORD), new None(), null, null, null));
        r.add(row(Tab.CATALOG, "search", "clear_search", toggle(s.clearSearchOnClose, v -> set(() -> s.clearSearchOnClose = v)),
                () -> set(() -> s.clearSearchOnClose = false)));

        // ---- Inventaire (horloge de l'inventaire) puis Horloge (contenu commun à toutes les horloges)
        String invOff = !s.showCenterLeftHud() ? t("wmi.settings.center_left.reason") : null;
        r.add(row(Tab.INVENTORY, "inv_clock", "center_left", toggle(s.showCenterLeftHud(), s::setShowCenterLeftHud),
                () -> s.setShowCenterLeftHud(false)));
        int step = 0;
        for (int i = 0; i < WIDGET_STEPS.length; i++) {
            if (Math.abs(WIDGET_STEPS[i] / 100.0 - s.hudScale) < Math.abs(WIDGET_STEPS[step] / 100.0 - s.hudScale)) {
                step = i;
            }
        }
        r.add(row(Tab.INVENTORY, "inv_clock", "widget_size", new Slider(0, WIDGET_STEPS.length - 1, 1, step,
                i -> WIDGET_STEPS[i] + " %", i -> s.hudScale = WIDGET_STEPS[i] / 100.0), () -> set(() -> s.hudScale = 1.0), invOff));
        r.add(row(Tab.INVENTORY, "inv_clock", "widget_position", new Button(t("wmi.settings.reset"), false, s::resetHudLayout),
                null, invOff));
        r.add(row(Tab.SCREEN, "hud_clocks", "corner_widget", toggle(s.hudCornerWidget(), s::setHudCornerWidget),
                () -> s.setHudCornerWidget(true)));
        r.add(row(Tab.SCREEN, "hud_clocks", "clock_in_game", toggle(s.hudClockInGame(), s::setHudClockInGame),
                () -> s.setHudClockInGame(true)));
        r.add(row(Tab.SCREEN, "hud_clocks", "clock_mod", toggle(s.hudClockShowMod(), s::setHudClockShowMod),
                () -> s.setHudClockShowMod(false), !s.hudClockInGame() ? t("wmi.settings.clock_in_game.reason") : null));
        r.add(row(Tab.WIDGET, "clock_display", "time_format", choice(s.timeFormat.ordinal(),
                i -> set(() -> s.timeFormat = WmiSettings.TimeFormat.values()[i]),
                "wmi.settings.time_format.language", "wmi.settings.time_format.h24", "wmi.settings.time_format.h12"),
                () -> set(() -> s.timeFormat = WmiSettings.TimeFormat.LANGUAGE)));
        r.add(row(Tab.WIDGET, "clock_display", "sun_moon", toggle(s.showSunMoonLines(), s::setShowSunMoonLines),
                () -> s.setShowSunMoonLines(true)));
        r.add(row(Tab.WIDGET, "clock_display", "night_alert", toggle(s.nightAlert, v -> set(() -> s.nightAlert = v)),
                () -> set(() -> s.nightAlert = false)));

        // ---- Widget : objectifs des Notes (inventaire et jeu)
        String notesOff = !s.hudNotesPinned() ? t("wmi.settings.notes_hud.reason") : null;
        r.add(row(Tab.SCREEN, "hud_notes", "notes_hud", toggle(s.hudNotesPinned(), s::setHudNotesPinned),
                () -> s.setHudNotesPinned(true)));
        r.add(row(Tab.SCREEN, "hud_notes", "notes_empty", toggle(s.notesShowEmpty(), s::setNotesShowEmpty),
                () -> s.setNotesShowEmpty(true), notesOff));
        r.add(row(Tab.WIDGET, "notes_goals", "notes_depth", new Slider(1, WmiSettings.NOTES_DEPTH_MAX, 1, s.notesTreeDepth(),
                v -> t(v == 1 ? "wmi.settings.notes_depth.one" : "wmi.settings.notes_depth.many", v),
                v -> s.notesTreeDepth = v), () -> s.setNotesTreeDepth(1)));
        r.add(row(Tab.WIDGET, "notes_goals", "done_behavior", choice(s.doneBehavior.ordinal(),
                i -> set(() -> s.doneBehavior = WmiSettings.DoneBehavior.values()[i]),
                "wmi.settings.done_behavior.keep", "wmi.settings.done_behavior.strike", "wmi.settings.done_behavior.hide"),
                () -> set(() -> s.doneBehavior = WmiSettings.DoneBehavior.KEEP)));
        r.add(row(Tab.WIDGET, "notes_goals", "done_sound", toggle(s.doneSound, v -> set(() -> s.doneSound = v)),
                () -> set(() -> s.doneSound = true)));

        // ---- HUD : disposition commune de l'horloge et des Notes en jeu, masquage
        String cornerOff = !s.hudCornerWidget() && !s.hudNotesPinned() ? t("wmi.settings.corner.reason") : null;
        r.add(row(Tab.SCREEN, "layout", "hud_side", choice(s.hudSide.ordinal(), i -> set(() -> s.hudSide = WmiSettings.HudSide.values()[i]),
                "wmi.settings.hud_side.left", "wmi.settings.hud_side.right"), () -> set(() -> s.hudSide = WmiSettings.HudSide.LEFT), cornerOff));
        r.add(row(Tab.SCREEN, "layout", "hud_opacity", new Slider(0, 100, 10, s.hudOpacity, v -> v + " %", v -> s.hudOpacity = v),
                () -> set(() -> s.hudOpacity = 100), cornerOff));
        r.add(row(Tab.SCREEN, "behavior", "hide_f1", toggle(s.hideWithF1, v -> set(() -> s.hideWithF1 = v)),
                () -> set(() -> s.hideWithF1 = true)));
        r.add(row(Tab.SCREEN, "behavior", "hide_debug", toggle(s.hideWithDebug, v -> set(() -> s.hideWithDebug = v)),
                () -> set(() -> s.hideWithDebug = true)));

        // ---- Touches (mêmes réglages que l'écran « Commandes », enregistrés dans options.txt)
        addKey(r, "recipes", "key_recipes", WmiClient.KEY_RECIPES);
        addKey(r, "recipes", "key_uses", WmiClient.KEY_USES);
        addKey(r, "notes_inventory", "key_pin", WmiClient.KEY_PIN);
        addKey(r, "notes_inventory", "key_note", WmiClient.KEY_POSTIT);
        addKey(r, "notes_inventory", "key_sort", WmiClient.KEY_SORT);
        addKey(r, "interface", "key_quick", WmiClient.KEY_QUICK);
        addKey(r, "interface", "key_toggle", WmiClient.KEY_TOGGLE_OVERLAY);
        r.add(row(Tab.KEYS, "manage", "controls", new Button(t("wmi.settings.controls.button"), false,
                () -> Minecraft.getInstance().gui.setScreen(new KeyBindsScreen(this, Minecraft.getInstance().options))), null));
        r.add(row(Tab.KEYS, "manage", "reset_keys", new Button(t("wmi.settings.reset_keys.button"), true, this::resetAllKeys), null));
        return r;
    }

    /** Lignes secondaires, en retrait sous le réglage dont elles dépendent. */
    private static final java.util.Set<String> SUB_ROWS = java.util.Set.of("custom_color", "toolbar_labels", "widget_size",
            "widget_position", "clock_mod", "notes_empty");

    /**
     * Thème prédéfini suivant ou précédent ; en arrivant sur le thème Minecraft (encore en alpha), la fenêtre
     * d'avertissement s'ouvre, avec retour possible au thème quitté.
     */
    private void stepTheme(int delta) {
        WmiSettings s = WmiClient.overlay().settings();
        WmiTheme before = s.presetTheme;
        s.cyclePreset(delta);
        if (s.presetTheme.vanilla() && !before.vanilla()) {
            vanillaWarningFrom = before;
        }
    }

    private void addKey(List<Row> r, String group, String id, KeyMapping key) {
        String clash = keyConflict(key);
        r.add(new Row(Tab.KEYS, group, id, t("wmi.settings." + id), t("wmi.settings." + id + ".desc"), new KeyCtl(key),
                () -> setKey(key, key.getDefaultKey()), null,
                clash != null ? t("wmi.settings.keys.conflict", clash) : null));
    }

    private static String autoOrCount(int v) {
        return v == 0 ? t("wmi.settings.auto") : Integer.toString(v);
    }

    /** Change un réglage par affectation directe, puis enregistre. */
    private static void set(Runnable assign) {
        assign.run();
        WmiClient.overlay().settings().save();
    }

    // ==================== TOUCHES ====================

    /** Action de Minecraft ou d'un autre mod qui partage la touche de {@code key}, ou {@code null}. */
    private static @Nullable String keyConflict(KeyMapping key) {
        if (key.isUnbound()) {
            return null;
        }
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            // Touches de débogage : combinaisons avec F3 (F3+B, F3+N…), jamais en concurrence avec une touche seule
            if (other.getCategory() == KeyMapping.Category.DEBUG) {
                continue;
            }
            if (other != key && !other.isUnbound() && key.same(other)) {
                return Component.translatable(other.getName()).getString();
            }
        }
        return null;
    }

    /** La touche passe par la liste des contrôles de Minecraft : même réglage que dans « Commandes », enregistré dans options.txt. */
    private static void setKey(KeyMapping key, InputConstants.Key code) {
        key.setKeyModifierAndCode(KeyModifier.NONE, code);
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    private void resetAllKeys() {
        for (KeyMapping k : List.of(WmiClient.KEY_RECIPES, WmiClient.KEY_USES, WmiClient.KEY_PIN, WmiClient.KEY_POSTIT,
                WmiClient.KEY_SORT, WmiClient.KEY_QUICK, WmiClient.KEY_TOGGLE_OVERLAY)) {
            k.setKeyModifierAndCode(k.getDefaultKeyModifier(), k.getDefaultKey());
        }
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    // ==================== DESSIN ====================

    @Override
    protected void extractUi(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Saisie de texte du système (SDL3) : sans startTextInput, aucun caractère n'arrive à charTyped
        Minecraft.getInstance().textInputManager().startTextInput(this);
        WmiTheme th = WmiDraw.theme();
        zones.clear();
        rowZones.clear();
        drags.clear();

        int boxW = Math.min(BOX_W, width - 16);
        int boxH = Math.min(BOX_H_MAX, height - 16);
        // Onglet Catalogue : aperçu du panneau d'objets à droite de la fenêtre (largeur réelle du panneau si la place
        // le permet), la fenêtre se décale vers la gauche pour le laisser voir
        // Onglets Inventaire, HUD et Widget : aperçu des deux horloges (inventaire, jeu) aux réglages en cours
        int previewW = 0;
        int room = width - boxW - 8 - 16;
        boolean clockPreview = (tab == Tab.INVENTORY || tab == Tab.WIDGET || tab == Tab.SCREEN) && query.isEmpty()
                && Minecraft.getInstance().level != null && Minecraft.getInstance().player != null;
        if (tab == Tab.CATALOG && query.isEmpty()) {
            int real = (int) Math.round(width * WmiClient.overlay().settings().rightPanelWidthRatio);
            previewW = room >= PREVIEW_MIN_W ? Math.max(PREVIEW_MIN_W, Math.min(real, room)) : 0;
        } else if (clockPreview) {
            int need = clockPreviewWidth();
            previewW = need <= room ? need : 0;
        }
        int x0 = (width - boxW - (previewW > 0 ? previewW + 8 : 0)) / 2;
        int y0 = (height - boxH) / 2;
        if (previewW > 0 && tab == Tab.CATALOG) {
            renderCatalogPreview(g, th, x0 + boxW + 8, 8, previewW, height - 16);
        } else if (previewW > 0) {
            renderClockPreview(g, th, x0 + boxW + 8, y0, previewW);
        }
        WmiDraw.drawFrostedPanel(g, x0, y0, boxW, boxH);
        WmiDraw.drawText(g, font, t("wmi.settings.title"), x0 + 10, y0 + 9, th.textMain);
        WmiDraw.bar(g, x0 + 1, y0 + HEAD_H - 1, x0 + boxW - 1, y0 + HEAD_H, th.innerBorder);

        int closeX = x0 + boxW - 6 - 14;
        int closeY = y0 + 6;
        WmiDraw.drawCloseButton(g, closeX, closeY, 14, 14,
                mouseX >= closeX && mouseX < closeX + 14 && mouseY >= closeY && mouseY < closeY + 14);
        zones.add(new Zone(closeX, closeY, 14, 14, this::onClose, null));

        List<Row> all = rows();
        renderSidebar(g, th, all, x0, y0 + HEAD_H, boxH - HEAD_H, mouseX, mouseY);
        renderContent(g, th, all, x0 + SIDE_W + 8, y0 + HEAD_H + 6, boxW - SIDE_W - 16, boxH - HEAD_H - 10, mouseX, mouseY);
        if (vanillaWarningFrom != null) {
            renderVanillaWarning(g, th, mouseX, mouseY);
        }
    }

    /**
     * Fenêtre d'avertissement du thème Minecraft (encore en alpha) : par-dessus tout, seules ses deux réponses
     * restent cliquables. Échap revient au thème quitté, Entrée garde le thème Minecraft.
     */
    private void renderVanillaWarning(GuiGraphicsExtractor g, WmiTheme th, int mouseX, int mouseY) {
        zones.clear();
        rowZones.clear();
        drags.clear();
        g.fill(0, 0, width, height, 0x99000000);
        int w = Math.min(260, width - 24);
        List<String> lines = wrapFull(t("wmi.settings.vanilla_warning.text"), w - 24);
        int h = 30 + lines.size() * 11 + 32;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        WmiDraw.drawFrostedPanel(g, x, y, w, h);
        WmiDraw.drawText(g, font, t("wmi.settings.vanilla_warning.title"), x + 12, y + 10, th.gold);
        int ty = y + 26;
        for (String line : lines) {
            WmiDraw.drawText(g, font, line, x + 12, ty, th.textMain);
            ty += 11;
        }
        WmiTheme previous = vanillaWarningFrom;
        String keep = t("wmi.settings.vanilla_warning.keep");
        String back = t("wmi.settings.vanilla_warning.back", previous != null ? previous.label() : "");
        int kw = WmiVector.width(font, keep) + 16;
        int bw = WmiVector.width(font, back) + 16;
        int by = y + h - 22;
        drawButton(g, th, x + w - 12 - kw, by, kw, keep, true, mouseX, mouseY, 0, height, () -> vanillaWarningFrom = null, null);
        drawButton(g, th, x + w - 12 - kw - 6 - bw, by, bw, back, false, mouseX, mouseY, 0, height, this::leaveVanilla, null);
    }

    /** Retour au thème quitté avant le thème Minecraft. */
    private void leaveVanilla() {
        if (vanillaWarningFrom != null) {
            WmiClient.overlay().settings().selectTheme(vanillaWarningFrom);
        }
        vanillaWarningFrom = null;
    }

    /** Largeur de l'aperçu des horloges : la plus large des deux, plus les marges. */
    private int clockPreviewWidth() {
        WmiTimeWidget wd = WmiClient.overlay().widget;
        String[] env = wd.environmentLines(Minecraft.getInstance());
        int inv = wd.measureWidget(font, env, wd.hudStep(), 0).w();
        int game = wd.measureWidget(font, env, WmiTimeWidget.HUD_CORNER_STEP, WmiTimeWidget.HUD_CORNER_W).w();
        int label = Math.max(WmiVector.width(font, t("wmi.settings.center_left")), WmiVector.width(font, t("wmi.settings.corner_widget")));
        return Math.max(Math.max(inv, game), label) + 20;
    }

    /**
     * Aperçu en direct des horloges : celle de l'inventaire (à sa taille) et celle de l'écran de jeu (au cran du coin,
     * à l'opacité réglée), avec le format, les lignes du soleil et de la lune et l'alerte de nuit en cours. Une
     * horloge masquée est remplacée par la raison. Purement visuel.
     */
    private void renderClockPreview(GuiGraphicsExtractor g, WmiTheme th, int px, int py, int pw) {
        WmiOverlay o = WmiClient.overlay();
        WmiSettings s = o.settings();
        WmiTimeWidget wd = o.widget;
        String[] env = wd.environmentLines(Minecraft.getInstance());
        WmiTimeWidget.WidgetMetrics inv = wd.measureWidget(font, env, wd.hudStep(), 0);
        WmiTimeWidget.WidgetMetrics game = wd.measureWidget(font, env, WmiTimeWidget.HUD_CORNER_STEP, WmiTimeWidget.HUD_CORNER_W);
        int invH = s.showCenterLeftHud() ? inv.h() : 10;
        int gameH = s.hudCornerWidget() ? game.h() : 10;
        int ph = 8 + 16 + 12 + invH + 10 + 12 + gameH + 10;
        WmiDraw.drawFrostedPanel(g, px, py, pw, ph);
        int x = px + 10;
        int y = py + 8;
        WmiDraw.hudText(g, font, t("wmi.settings.preview"), 0, 0, 0.75, x, y + 6, th.accent);
        y += 16;
        WmiDraw.drawText(g, font, t("wmi.settings.center_left"), x, y, th.textMain);
        y += 12;
        if (s.showCenterLeftHud()) {
            int wx = px + (pw - inv.w()) / 2;
            WmiDraw.drawFrostedPanel(g, wx, y, inv.w(), inv.h());
            wd.drawWidgetContent(g, font, env, inv, wx, y);
        } else {
            WmiDraw.hudText(g, font, t("wmi.settings.center_left.reason"), 0, 0, 0.75, x, y + 6, th.textMuted);
        }
        y += invH + 10;
        WmiDraw.drawText(g, font, t("wmi.settings.corner_widget"), x, y, th.textMain);
        y += 12;
        if (s.hudCornerWidget()) {
            int wx = px + (pw - game.w()) / 2;
            WmiDraw.drawFrostedPanel(g, wx, y, game.w(), game.h(), s.hudOpacity / 100.0);
            wd.drawWidgetContent(g, font, env, game, wx, y);
        } else {
            WmiDraw.hudText(g, font, t("wmi.settings.corner_widget.reason"), 0, 0, 0.75, x, y + 6, th.textMuted);
        }
    }

    /** Découpe {@code text} en lignes de largeur {@code maxW} (police normale). */
    private List<String> wrapFull(String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && WmiVector.width(font, test) > maxW) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    /**
     * Aperçu en direct du panneau d'objets (onglet Catalogue) : mêmes onglets, titre, recherche et grille que le vrai
     * panneau, avec les réglages en cours (taille des icônes, colonnes, lignes, onglets, ordre, objets sans recette)
     * et les vrais objets de la première page. Purement visuel, sans zone cliquable.
     */
    private void renderCatalogPreview(GuiGraphicsExtractor g, WmiTheme th, int px, int py, int pw, int ph) {
        WmiOverlay o = WmiClient.overlay();
        WmiSettings s = o.settings();
        WmiDraw.drawFrostedPanel(g, px, py, pw, ph);
        int x = px + 5;
        int w = pw - 10;
        int y = py + 4;
        if (s.catalogTabs) {
            String all = t("wmi.tabs.all");
            int allW = 22 + WmiVector.width(font, all);
            WmiDraw.drawBox(g, x, y, allW, 19, th.accentSoft, th.accent);
            WmiDraw.drawSmallItem(g, previewCompass(), x + 2, y + 2, 14);
            WmiDraw.drawText(g, font, all, x + 17, y + 6, th.accent);
            int tx = x + allW + 2;
            for (WmiRightPanel.ModTab mt : o.panel.modTabs()) {
                if (tx + 20 > x + w) {
                    break;
                }
                WmiDraw.drawBox(g, tx, y, 20, 19, th.slotBg, th.border);
                WmiDraw.drawSmallItem(g, mt.icon(), tx + 2, y + 2, 16);
                tx += 22;
            }
            y += 19 + 6;
        }
        WmiDraw.bar(g, x, y - 2, x + w, y - 1, th.innerBorder);
        WmiDraw.drawText(g, font, WmiVector.trim(font, t("wmi.tabs.all_items"), w - 4), x + 1, y + 2, th.textMain);
        String tag = t("wmi.settings.preview");
        WmiDraw.hudText(g, font, tag, 0, 0, 0.75, x + w - WmiDraw.hudWidth(font, tag, 0, 0), y + 8, th.accent);
        y += 13 + 3;
        String q = o.panel.searchQuery;
        WmiDraw.drawBox(g, x, y, w, 13, th.slotBg, th.innerBorder);
        WmiDraw.drawText(g, font, WmiVector.trim(font, q.isEmpty() ? t("wmi.search.placeholder") : q, w - 8), x + 4, y + 3,
                q.isEmpty() ? th.textMuted : th.textMain);
        y += 13 + 7;
        WmiDraw.bar(g, x + 1, y, x + w - 1, y + 1, th.innerBorder);
        y += 1 + 5;

        int gridH = py + ph - 17 - y;
        int icon = s.gridIconSize;
        int step = icon + 2;
        int fitCols = Math.max(1, (w + 2) / step);
        int fitRows = Math.max(1, gridH / step);
        int cols = s.gridColumns > 0 ? Math.min(s.gridColumns, fitCols) : fitCols;
        int rows = s.gridRows > 0 ? Math.min(s.gridRows, fitRows) : fitRows;
        List<CatalogItem> items = WmiClientIndex.peek().allItems().isEmpty() ? List.of()
                : o.panel.catalogItems(o.snapshotPlayerInventory(), null);
        int left = x + 1 + (w - cols * step) / 2;
        int perPage = cols * rows;
        for (int i = 0; i < perPage; i++) {
            int cx = left + (i % cols) * step;
            int cy = y + (i / cols) * step;
            WmiDraw.drawBox(g, cx, cy, icon + 1, icon + 1, th.slotBg, th.innerBorder);
            if (i < items.size()) {
                WmiDraw.drawSmallItem(g, WmiClientIndex.stackFor(items.get(i).id()), cx + 1, cy + 1, icon - 1);
            }
        }
        int pages = Math.max(1, (items.size() + perPage - 1) / Math.max(1, perPage));
        String page = "1 / " + pages;
        WmiDraw.drawText(g, font, page, px + (pw - WmiVector.width(font, page)) / 2, py + ph - 13, th.textMuted);
    }

    private static @Nullable ItemStack compass;

    private static ItemStack previewCompass() {
        if (compass == null) {
            compass = new ItemStack(Items.COMPASS);
        }
        return compass;
    }

    private void renderSidebar(GuiGraphicsExtractor g, WmiTheme th, List<Row> all, int x0, int top, int h, int mouseX, int mouseY) {
        WmiDraw.bar(g, x0 + SIDE_W, top, x0 + SIDE_W + 1, top + h - 1, th.innerBorder);
        int x = x0 + 6;
        int w = SIDE_W - 12;
        int y = top + 7;
        WmiDraw.drawEditField(g, font, x, y, w, CTRL_H, query, searchEdit, searchFocused, t("wmi.settings.search"),
                searchFocused ? th.accent : th.innerBorder);
        zones.add(new Zone(x, y, w, CTRL_H, () -> {
            searchFocused = true;
            hexFocused = false;
            searchEdit.setCursorToEnd();
        }, () -> {
            query = "";
            scroll = 0;
        }));
        y += CTRL_H + 7;
        for (Tab tb : Tab.values()) {
            boolean active = query.isEmpty() && tb == tab;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 16;
            if (active || hover) {
                WmiDraw.drawBox(g, x, y, w, 16, active ? th.accentSoft : th.slotHover, th.accent);
            }
            WmiDraw.drawText(g, font, WmiVector.trim(font, t("wmi.settings.tab." + tb.key), w - 8), x + 5, y + 4,
                    active || hover ? th.accent : th.textMain);
            Tab target = tb;
            zones.add(new Zone(x, y, w, 16, () -> {
                tab = target;
                query = "";
                searchFocused = false;
                scroll = 0;
                armed = null;
            }, null));
            y += 18;
        }
        List<String> hint = wrap(t("wmi.settings.autosave"), w);
        int hintY = top + h - 8 - hint.size() * 8;
        for (String line : hint) {
            if (hintY > y + 4) {
                WmiDraw.hudText(g, font, line, 0, 0, 0.75, x, hintY + 6, th.textMuted);
            }
            hintY += 8;
        }
    }

    private void renderContent(GuiGraphicsExtractor g, WmiTheme th, List<Row> all, int cx, int top, int cw, int ch,
                               int mouseX, int mouseY) {
        List<Row> shown = new ArrayList<>();
        String q = CatalogItem.normalize(query);
        for (Row r : all) {
            if (q.isEmpty() ? r.tab() == tab
                    : CatalogItem.normalize(r.label() + " " + (r.desc() != null ? r.desc() : "")).contains(q)) {
                shown.add(r);
            }
        }
        // Hauteur totale d'abord (bornes du défilement), puis dessin découpé à la zone du contenu
        int total = layout(g, th, shown, cx, 0, cw, top, top + ch, mouseX, mouseY, false);
        maxScroll = Math.max(0, total - ch);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        g.enableScissor(cx - 2, top, cx + cw + 2, top + ch);
        try {
            layout(g, th, shown, cx, top - scroll, cw, top, top + ch, mouseX, mouseY, true);
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            int trackH = ch;
            int thumbH = Math.max(16, trackH * ch / Math.max(1, total));
            int thumbY = top + (trackH - thumbH) * scroll / maxScroll;
            WmiDraw.bar(g, cx + cw + 4, thumbY, cx + cw + 6, thumbY + thumbH, th.innerBorder);
        }
    }

    /**
     * Dispose (et dessine si {@code draw}) l'en-tête de l'onglet, les groupes et leurs lignes à partir de {@code y} ;
     * rend la hauteur occupée. Les zones cliquables ne sont relevées que dans la partie visible.
     */
    private int layout(GuiGraphicsExtractor g, WmiTheme th, List<Row> shown, int cx, int y, int cw, int clipTop, int clipBottom,
                       int mouseX, int mouseY, boolean draw) {
        int y0 = y;
        boolean searching = !query.isEmpty();
        String heading = searching ? t("wmi.settings.search.results", shown.size()) : t("wmi.settings.tab." + tab.key);
        if (draw) {
            WmiDraw.drawText(g, font, heading, cx, y + 1, th.textMain);
        }
        y += 12;
        String lead = searching ? (shown.isEmpty() ? t("wmi.settings.search.none") : null) : t("wmi.settings.tab." + tab.key + ".desc");
        if (lead != null) {
            for (String line : wrap(lead, cw)) {
                if (draw) {
                    WmiDraw.hudText(g, font, line, 0, 0, 0.75, cx, y + 6, th.textMuted);
                }
                y += 8;
            }
        }
        y += 6;
        String group = null;
        Tab groupTab = null;
        int cardTop = 0;
        List<int[]> cards = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            if (!r.group().equals(group) || r.tab() != groupTab) {
                if (group != null) {
                    cards.add(new int[]{cardTop, y});
                    y += 8;
                }
                group = r.group();
                groupTab = r.tab();
                String title = (searching ? t("wmi.settings.tab." + r.tab().key) + " › " : "") + t("wmi.settings.group." + group);
                if (draw) {
                    WmiDraw.hudText(g, font, title.toUpperCase(Locale.ROOT), 0, 0, 0.75, cx + 2, y + 6, th.textMuted);
                }
                y += 10;
                cardTop = y;
            }
            y += rowHeight(r, cw);
        }
        if (group != null) {
            cards.add(new int[]{cardTop, y});
        }
        if (draw) {
            // Cartes d'abord (fond des groupes), puis les lignes par-dessus
            for (int[] c : cards) {
                WmiDraw.drawBox(g, cx, c[0], cw, c[1] - c[0], th.cardBg, th.innerBorder);
            }
            int ry = cardsStart(shown, cx, y0, cw);
            String grp = null;
            Tab gTab = null;
            boolean first = true;
            for (Row r : shown) {
                if (!r.group().equals(grp) || r.tab() != gTab) {
                    if (grp != null) {
                        ry += 8;
                    }
                    grp = r.group();
                    gTab = r.tab();
                    ry += 10;
                    first = true;
                }
                int rh = rowHeight(r, cw);
                if (!first) {
                    WmiDraw.bar(g, cx + 6, ry, cx + cw - 6, ry + 1, th.innerBorder);
                }
                if (ry + rh > clipTop && ry < clipBottom) {
                    drawRow(g, th, r, cx, ry, cw, rh, mouseX, mouseY, clipTop, clipBottom);
                }
                ry += rh;
                first = false;
            }
        }
        if (!searching) {
            y += 10;
            if (draw) {
                int bw = WmiVector.width(font, armed != null && armed.equals("tab") ? t("wmi.settings.confirm") : t("wmi.settings.reset_tab")) + 14;
                String label = "tab".equals(armed) ? t("wmi.settings.confirm") : t("wmi.settings.reset_tab");
                drawButton(g, th, cx + cw - bw, y, bw, label, "tab".equals(armed), mouseX, mouseY, clipTop, clipBottom, () -> {
                    if (!"tab".equals(armed)) {
                        armed = "tab";
                        return;
                    }
                    armed = null;
                    for (Row r : rows()) {
                        if (r.tab() == tab && r.reset() != null) {
                            r.reset().run();
                        }
                    }
                }, null);
                WmiDraw.hudText(g, font, WmiVector.trim(font, t("wmi.settings.right_click_hint"), cw - bw - 8), 0, 0, 0.75,
                        cx + 2, y + 9, th.textMuted);
            }
            y += CTRL_H + 4;
        }
        return y - y0;
    }

    /** Ordonnée de la première ligne du contenu (après l'en-tête), recalculée comme dans {@link #layout}. */
    private int cardsStart(List<Row> shown, int cx, int y0, int cw) {
        boolean searching = !query.isEmpty();
        int y = y0 + 12;
        String lead = searching ? (shown.isEmpty() ? t("wmi.settings.search.none") : null) : t("wmi.settings.tab." + tab.key + ".desc");
        if (lead != null) {
            y += 8 * wrap(lead, cw).size();
        }
        return y + 6;
    }

    private int controlWidth(Row r) {
        return switch (r.control()) {
            case Toggle tg -> 24;
            case Choice c -> c.labels().size() > 4 ? 0 : choiceWidth(c);
            case Slider sl -> 118;
            case Stepper st -> 140;
            case KeyCtl k -> 76;
            case Button b -> WmiVector.width(font, b.label()) + 14;
            default -> 0;
        };
    }

    private int choiceWidth(Choice c) {
        int w = 0;
        for (String l : c.labels()) {
            w += WmiVector.width(font, l) + 10;
        }
        return w;
    }

    private int rowHeight(Row r, int cw) {
        int textW = textWidth(r, cw);
        int lines = r.desc() != null ? wrap(r.desc(), textW).size() : 0;
        int h = 6 + 9 + lines * 8 + (r.disabled() != null || r.warning() != null ? 8 : 0) + 5;
        if (r.control() instanceof ColorPick) {
            h += 72;
        } else if (wide(r)) {
            h += CTRL_H + 4;
        }
        return Math.max(h, CTRL_H + 10);
    }

    private int textWidth(Row r, int cw) {
        int ctrl = wide(r) ? 0 : controlWidth(r);
        return cw - 20 - (SUB_ROWS.contains(r.id()) ? 12 : 0) - (ctrl > 0 ? ctrl + 10 : 0);
    }

    private void drawRow(GuiGraphicsExtractor g, WmiTheme th, Row r, int x, int y, int w, int h, int mouseX, int mouseY,
                         int clipTop, int clipBottom) {
        boolean off = r.disabled() != null;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= clipTop && mouseY < clipBottom;
        if (hover && !off) {
            WmiDraw.bar(g, x + 1, y + 1, x + w - 1, y + h - 1, th.slotBg);
        }
        int textW = textWidth(r, w);
        boolean sub = SUB_ROWS.contains(r.id());
        int lx = x + (sub ? 22 : 10);
        if (sub) {
            // Réglage secondaire : filet vertical de rattachement au réglage du dessus
            WmiDraw.bar(g, x + 13, y + 2, x + 14, y + h - 2, th.innerBorder);
        }
        int ty = y + 6;
        WmiDraw.drawText(g, font, WmiVector.trim(font, r.label(), textW), lx, ty, off ? th.textMuted : th.textMain);
        ty += 10;
        if (r.desc() != null) {
            for (String line : wrap(r.desc(), textW)) {
                WmiDraw.hudText(g, font, line, 0, 0, 0.75, lx, ty + 5, th.textMuted);
                ty += 8;
            }
        }
        if (off) {
            WmiDraw.hudText(g, font, WmiVector.trim(font, r.disabled(), textW), 0, 0, 0.75, lx, ty + 5, th.textMuted);
            ty += 8;
        } else if (r.warning() != null) {
            WmiDraw.hudText(g, font, WmiVector.trim(font, r.warning(), textW), 0, 0, 0.75, lx, ty + 5, th.gold);
            ty += 8;
        }
        if (r.reset() != null && !off && y + h > clipTop && y < clipBottom) {
            int vy = Math.max(y, clipTop);
            rowZones.add(new Zone(x, vy, w, Math.min(y + h, clipBottom) - vy, () -> {
            }, r.reset()));
        }
        if (off) {
            drawControl(g, th, r, x, y, w, ty, -1, -1, clipTop, clipBottom, false);
        } else {
            drawControl(g, th, r, x, y, w, ty, mouseX, mouseY, clipTop, clipBottom, true);
        }
    }

    /**
     * Commande de la ligne : à droite du libellé, ou sous la description pour les commandes larges ({@code below} :
     * première ordonnée libre). {@code active} à faux : dessinée grisée, sans zone cliquable.
     */
    private void drawControl(GuiGraphicsExtractor g, WmiTheme th, Row r, int x, int y, int w, int below, int mouseX, int mouseY,
                             int clipTop, int clipBottom, boolean active) {
        int right = x + w - 10;
        int cy = y + 6;
        switch (r.control()) {
            case Toggle tg -> {
                int sx = right - 24;
                int sy = cy - 1;
                boolean on = tg.value();
                WmiDraw.drawBox(g, sx, sy, 24, 12, on ? th.accent : th.slotBg, on ? th.accent : th.border);
                int knob = on ? sx + 13 : sx + 2;
                WmiDraw.bar(g, knob, sy + 2, knob + 9, sy + 10, on ? 0xFFFFFFFF : th.textMuted);
                if (active) {
                    addZone(sx - 4, sy - 2, 32, 16, () -> tg.set().accept(!on), null, clipTop, clipBottom);
                }
            }
            case Choice c -> {
                int bx = wide(r) ? x + 10 : right - choiceWidth(c);
                int by = wide(r) ? below + 2 : cy - 2;
                for (int i = 0; i < c.labels().size(); i++) {
                    String l = c.labels().get(i);
                    int bw = WmiVector.width(font, l) + 10;
                    boolean sel = i == c.selected();
                    boolean hov = active && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + CTRL_H;
                    WmiDraw.drawBox(g, bx, by, bw, CTRL_H, sel ? th.accentSoft : (hov ? th.slotHover : th.slotBg),
                            sel || hov ? th.accent : th.innerBorder);
                    WmiDraw.drawText(g, font, l, bx + 5, by + 3, sel || hov ? th.accent : th.textMain);
                    int idx = i;
                    if (active) {
                        addZone(bx, by, bw, CTRL_H, () -> c.set().accept(idx), null, clipTop, clipBottom);
                    }
                    bx += bw;
                }
            }
            case Slider sl -> {
                int valW = 40;
                int tw = 118 - valW - 6;
                int tx = right - 118;
                int tyy = cy + 3;
                int steps = Math.max(1, (sl.max() - sl.min()) / sl.step());
                float f = (sl.value() - sl.min()) / (float) Math.max(1, sl.max() - sl.min());
                WmiDraw.bar(g, tx, tyy, tx + tw, tyy + 3, th.slotBg);
                WmiDraw.bar(g, tx, tyy, tx + Math.round(tw * f), tyy + 3, th.accent);
                int kx = tx + Math.round(tw * f);
                WmiDraw.drawBox(g, kx - 4, tyy - 3, 8, 9, th.slotHover, th.accent);
                String v = sl.format().apply(sl.value());
                WmiDraw.drawText(g, font, v, right - WmiVector.width(font, v), cy, th.textMain);
                if (active) {
                    drags.add(new Drag(r.id(), tx - 4, tyy - 5, tw + 8, 13, (fx, fy) -> {
                        int k = Math.round(fx * steps);
                        sl.set().accept(Math.min(sl.max(), sl.min() + k * sl.step()));
                    }));
                }
            }
            case KeyCtl k -> {
                boolean cap = capturing == k.key();
                String label = cap ? t("wmi.settings.keys.press") : k.key().getTranslatedKeyMessage().getString();
                drawButton(g, th, right - 76, cy - 2, 76, label, cap, mouseX, mouseY, clipTop, clipBottom,
                        active ? () -> capturing = cap ? null : k.key() : null,
                        active ? () -> {
                            capturing = null;
                            setKey(k.key(), k.key().getDefaultKey());
                        } : null);
                if (r.warning() != null && !cap) {
                    // Conflit : contour doré, comme l'avertissement sous la description
                    WmiDraw.drawBorder(g, right - 76, cy - 2, 76, CTRL_H, th.gold);
                }
            }
            case Button b -> {
                boolean isArmed = b.confirm() && r.id().equals(armed);
                String label = isArmed ? t("wmi.settings.confirm") : b.label();
                int bw = WmiVector.width(font, b.label()) + 14;
                drawButton(g, th, right - bw, cy - 2, bw, label, isArmed, mouseX, mouseY, clipTop, clipBottom, active ? () -> {
                    if (b.confirm() && !r.id().equals(armed)) {
                        armed = r.id();
                        return;
                    }
                    armed = null;
                    b.action().run();
                } : null, null);
            }
            case Stepper st -> {
                int sw = 140;
                int sx = right - sw;
                drawButton(g, th, sx, cy - 2, 15, "<", false, mouseX, mouseY, clipTop, clipBottom, active ? st.prev() : null, null);
                drawButton(g, th, sx + 17, cy - 2, sw - 34, st.value(), false, mouseX, mouseY, clipTop, clipBottom,
                        active ? st.next() : null, active ? st.prev() : null);
                drawButton(g, th, sx + sw - 15, cy - 2, 15, ">", false, mouseX, mouseY, clipTop, clipBottom, active ? st.next() : null, null);
            }
            case ColorPick cp -> drawColorPicker(g, th, x + 10, below + 2, w - 20, mouseX, mouseY, clipTop, clipBottom);
            case None n -> {
            }
        }
    }

    /** Carré saturation (abscisse) et luminosité (ordonnée), barre de teinte, code hexadécimal et couleurs proposées. */
    private void drawColorPicker(GuiGraphicsExtractor g, WmiTheme th, int x, int y, int w, int mouseX, int mouseY,
                                 int clipTop, int clipBottom) {
        WmiSettings s = WmiClient.overlay().settings();
        float h = s.customHue();
        float sat = s.customSat();
        float val = s.customVal();
        int sw = 120;
        int sh = 52;
        for (int i = 0; i < sw; i += 3) {
            for (int j = 0; j < sh; j += 3) {
                int c = ThemePalette.hsvToRgb(h, i / (float) (sw - 1), 1.0F - j / (float) (sh - 1));
                g.fill(x + i, y + j, x + Math.min(sw, i + 3), y + Math.min(sh, j + 3), 0xFF000000 | c);
            }
        }
        WmiDraw.drawBorder(g, x - 1, y - 1, sw + 2, sh + 2, th.innerBorder);
        int kx = x + Math.round(sat * (sw - 1));
        int ky = y + Math.round((1.0F - val) * (sh - 1));
        g.fill(kx - 3, ky - 3, kx + 3, ky + 3, 0xFF101010);
        g.fill(kx - 2, ky - 2, kx + 2, ky + 2, 0xFFFFFFFF);
        drags.add(new Drag("sv", x, y, sw, sh, (fx, fy) -> s.setCustomHsv(s.customHue(), fx, 1.0F - fy, false)));
        int hy = y + sh + 6;
        for (int i = 0; i < sw; i += 2) {
            g.fill(x + i, hy, x + Math.min(sw, i + 2), hy + 8, 0xFF000000 | ThemePalette.hsvToRgb(i / (float) (sw - 1), 1.0F, 1.0F));
        }
        WmiDraw.drawBorder(g, x - 1, hy - 1, sw + 2, 10, th.innerBorder);
        int hx = x + Math.round(h * (sw - 1));
        g.fill(hx - 2, hy - 2, hx + 2, hy + 10, 0xFF101010);
        g.fill(hx - 1, hy - 1, hx + 1, hy + 9, 0xFFFFFFFF);
        drags.add(new Drag("hue", x, hy - 2, sw, 12, (fx, fy) -> s.setCustomHsv(Math.min(0.999F, fx), s.customSat(), s.customVal(), false)));

        int px = x + sw + 12;
        String hex = String.format(Locale.ROOT, "#%06X", s.customColor() & 0xFFFFFF);
        if (!hexFocused) {
            hexDraft = hex;
        }
        WmiDraw.drawBox(g, px, y, 18, CTRL_H, 0xFF000000 | s.customColor(), th.border);
        WmiDraw.drawEditField(g, font, px + 22, y, 60, CTRL_H, hexDraft, hexEdit, hexFocused, hex, hexFocused ? th.accent : th.innerBorder);
        addZone(px + 22, y, 60, CTRL_H, () -> {
            hexFocused = true;
            searchFocused = false;
            hexEdit.setCursorToEnd();
        }, null, clipTop, clipBottom);
        WmiDraw.hudText(g, font, t("wmi.settings.custom_color.presets"), 0, 0, 0.75, px, y + 26, th.textMuted);
        int[] presets = {0x2563EB, 0x0EA5E9, 0x10B981, 0x84CC16, 0xF59E0B, 0xEF4444, 0xEC4899, 0x8B5CF6, 0x64748B, 0x1F2937};
        for (int i = 0; i < presets.length; i++) {
            int c = presets[i];
            int bx = px + (i % 5) * 15;
            int by = y + 30 + (i / 5) * 15;
            WmiDraw.drawBox(g, bx, by, 12, 12, 0xFF000000 | c, 0x60000000);
            addZone(bx, by, 12, 12, () -> {
                float[] hsv = ThemePalette.rgbToHsv(c);
                s.setCustomHsv(hsv[0], hsv[1], hsv[2], true);
            }, null, clipTop, clipBottom);
        }
    }

    /** Code hexadécimal saisi : appliqué dès qu'il compte six chiffres. */
    private void onHexEdited(String v) {
        hexDraft = v;
        String digits = v.startsWith("#") ? v.substring(1) : v;
        if (digits.length() == 6 && digits.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            float[] hsv = ThemePalette.rgbToHsv(Integer.parseInt(digits, 16));
            WmiClient.overlay().settings().setCustomHsv(hsv[0], hsv[1], hsv[2], true);
        }
    }

    private void drawButton(GuiGraphicsExtractor g, WmiTheme th, int x, int y, int w, String label, boolean highlight,
                            int mouseX, int mouseY, int clipTop, int clipBottom, @Nullable Runnable left, @Nullable Runnable right) {
        boolean hover = left != null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CTRL_H
                && mouseY >= clipTop && mouseY < clipBottom;
        int bg = highlight ? th.accentSoft : (hover ? th.slotHover : th.slotBg);
        int brd = highlight || hover ? th.accent : th.innerBorder;
        WmiDraw.drawBox(g, x, y, w, CTRL_H, bg, brd);
        String shown = WmiVector.trim(font, label, w - 6);
        WmiDraw.drawText(g, font, shown, x + (w - WmiVector.width(font, shown)) / 2, y + 3,
                highlight || hover ? th.accent : (left == null ? th.textMuted : th.textMain));
        if (left != null) {
            addZone(x, y, w, CTRL_H, left, right, clipTop, clipBottom);
        }
    }

    private void addZone(int x, int y, int w, int h, Runnable left, @Nullable Runnable right, int clipTop, int clipBottom) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            zones.add(new Zone(x, top, w, bottom - top, left, right));
        }
    }

    /** Découpe {@code text} en lignes de largeur {@code maxW} (petite police, 75 %). */
    private List<String> wrap(String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && WmiDraw.hudWidth(font, test, 0, 0) > maxW) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ==================== SAISIE ====================

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (vanillaWarningFrom != null) {
            if (event.isEscape()) {
                leaveVanilla();
            } else if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                vanillaWarningFrom = null;
            }
            return true;
        }
        if (capturing != null) {
            KeyMapping k = capturing;
            capturing = null;
            if (!event.isEscape()) {
                setKey(k, InputConstants.getKey(event));
            }
            return true;
        }
        if (searchFocused || hexFocused) {
            if (event.isEscape() || event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                searchFocused = false;
                hexFocused = false;
                return true;
            }
            (searchFocused ? searchEdit : hexEdit).keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if ((searchFocused || hexFocused) && event.isAllowedChatCharacter()) {
            (searchFocused ? searchEdit : hexEdit).charTyped(event);
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (vanillaWarningFrom != null) {
            return true;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll + (scrollY < 0 ? SCROLL_STEP : -SCROLL_STEP)));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent rawEvent, boolean doubleClick) {
        MouseButtonEvent event = ui(rawEvent);
        double mx = event.x();
        double my = event.y();
        boolean left = WmiOverlayInput.isLeftClick(event.button());
        boolean right = WmiOverlayInput.isRightClick(event.button());
        if (capturing != null) {
            capturing = null;
        }
        if (left) {
            for (Drag d : drags) {
                if (d.contains(mx, my)) {
                    dragging = d.id();
                    d.apply(mx, my);
                    return true;
                }
            }
        }
        boolean wasArmed = armed != null;
        for (Zone z : zones) {
            if (z.contains(mx, my)) {
                if (right && z.right() != null) {
                    armed = null;
                    z.right().run();
                    return true;
                }
                if (left) {
                    String before = armed;
                    searchFocused = false;
                    hexFocused = false;
                    z.left().run();
                    if (wasArmed && armed != null && armed.equals(before)) {
                        armed = null;
                    }
                    return true;
                }
            }
        }
        searchFocused = false;
        hexFocused = false;
        armed = null;
        if (right) {
            for (Zone z : rowZones) {
                if (z.contains(mx, my) && z.right() != null) {
                    z.right().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            MouseButtonEvent e = ui(event);
            for (Drag d : drags) {
                if (d.id().equals(dragging)) {
                    d.apply(e.x(), e.y());
                    return true;
                }
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** Fin du glisser : le réglage est enregistré une seule fois, au relâchement. */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            WmiClient.overlay().settings().saveSettingsFromScreen();
            return true;
        }
        return super.mouseReleased(event);
    }
}
