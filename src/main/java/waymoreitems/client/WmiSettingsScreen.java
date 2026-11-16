package waymoreitems.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.jspecify.annotations.Nullable;
import waymoreitems.core.ThemePalette;

/**
 * Fenêtre « Paramètres » de Way More Items : chaque réglage s'applique et s'enregistre immédiatement
 * dans {@code config/wmi-client.properties}. Échap ou la croix de fermeture ramène à l'écran d'origine.
 */
public final class WmiSettingsScreen extends WmiScaledScreen {
    private static final int BOX_W = 272;
    private static final int ROW_H = 18;

    private final @Nullable Screen parent;
    private final List<Zone> zones = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    /** Curseur en cours de glisser (identifié par sa ligne), ou -1. */
    private int draggingRow = -1;
    /** Onglet affiché : 0 = Général, 1 = Widget (heure, biome, jour), 2 = Notes. */
    private int tab = 0;
    /** Attente de la touche de la fenêtre « Accès rapide » (Échap annule). */
    private boolean capturingQuickKey = false;

    private record Zone(int x, int y, int w, int h, Runnable left, @Nullable Runnable right) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** Curseur de couleur : {@code onChange} reçoit une valeur de 0 à 1 et l'indicateur d'enregistrement. */
    private record Slider(int row, int x, int y, int w, int h, Consumer<Float> onChange) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        float valueAt(double mx) {
            return (float) Math.max(0.0, Math.min(1.0, (mx - x) / (double) (w - 1)));
        }
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

    @Override
    protected void extractUi(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        WmiOverlay o = WmiClient.overlay();
        WmiTheme th = o.theme();
        zones.clear();
        sliders.clear();

        boolean custom = th.isCustom();
        int rows = tab == 3 ? 1 : tab == 2 ? 4 : tab == 1 ? 7 : (custom ? 14 : 11);
        int boxH = 30 + 22 + rows * ROW_H + 8;
        int x0 = (width - BOX_W) / 2;
        int y0 = (height - boxH) / 2;
        WmiDraw.drawFrostedPanel(g, x0, y0, BOX_W, boxH);
        WmiDraw.drawText(g, font, WmiText.s("wmi.settings.title"), x0 + 10, y0 + 7, th.textMain);

        int y = y0 + 26 + 22;

        // Onglets
        button(g, o, x0 + 10, y0 + 24, 60, 15, WmiText.s("wmi.settings.tab.general"), tab == 0, mouseX, mouseY, () -> tab = 0, null);
        button(g, o, x0 + 74, y0 + 24, 60, 15, WmiText.s("wmi.settings.tab.widget"), tab == 1, mouseX, mouseY, () -> tab = 1, null);
        button(g, o, x0 + 138, y0 + 24, 60, 15, WmiText.s("wmi.settings.tab.notes"), tab == 2, mouseX, mouseY, () -> tab = 2, null);
        button(g, o, x0 + 202, y0 + 24, 60, 15, WmiText.s("wmi.settings.tab.hud"), tab == 3, mouseX, mouseY, () -> tab = 3, null);

        if (tab == 3) {
            renderHudTab(g, o, x0, y, mouseX, mouseY);
        } else if (tab == 2) {
            renderNotesTab(g, o, th, x0, y, mouseX, mouseY);
        } else if (tab == 1) {
            renderWidgetTab(g, o, th, x0, y, mouseX, mouseY);
        } else {
            renderGeneralTab(g, o, th, x0, y, mouseX, mouseY);
        }

        // Fermer : croix rouge en haut à droite, comme les autres fenêtres de WMI
        int closeX = x0 + BOX_W - 6 - 14;
        int closeY = y0 + 6;
        WmiDraw.drawCloseButton(g, closeX, closeY, 14, 14,
                mouseX >= closeX && mouseX < closeX + 14 && mouseY >= closeY && mouseY < closeY + 14);
        zones.add(new Zone(closeX, closeY, 14, 14, this::onClose, null));
    }

    /** Onglet HUD : éléments de WMI affichés sur l'écran de jeu, hors inventaire. */
    private void renderHudTab(GuiGraphicsExtractor g, WmiOverlay o, int x0, int y, int mouseX, int mouseY) {
        WmiSettings cfg = o.settings();
        int labelX = x0 + 10;
        int ctrlX = x0 + 138;
        int ctrlW = BOX_W - 148;
        row(g, o, WmiText.s("wmi.settings.notes_hud"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.notes_hud." + (cfg.hudNotesPinned() ? "on" : "off")), cfg.hudNotesPinned(), mouseX, mouseY,
                () -> cfg.setHudNotesPinned(!cfg.hudNotesPinned()));
    }

    /** Onglet Notes : notes vides et profondeur des arbres d'ingrédients. */
    private void renderNotesTab(GuiGraphicsExtractor g, WmiOverlay o, WmiTheme th, int x0, int y, int mouseX, int mouseY) {
        WmiSettings cfg = o.settings();
        int labelX = x0 + 10;
        int ctrlX = x0 + 138;
        int ctrlW = BOX_W - 148;
        row(g, o, WmiText.s("wmi.settings.notes_empty"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.notes_empty." + (cfg.notesShowEmpty() ? "on" : "off")), cfg.notesShowEmpty(), mouseX, mouseY,
                () -> cfg.setNotesShowEmpty(!cfg.notesShowEmpty()));
        y += ROW_H;
        row(g, o, WmiText.s("wmi.settings.notes_depth"), labelX, y);
        int depth = cfg.notesTreeDepth();
        stepper(g, o, ctrlX, y, ctrlW, depth == 1 ? WmiText.s("wmi.settings.notes_depth.one") : WmiText.s("wmi.settings.notes_depth.many", depth),
                mouseX, mouseY, () -> cfg.setNotesTreeDepth(depth - 1), () -> cfg.setNotesTreeDepth(depth + 1));
        y += ROW_H;
        WmiDraw.drawText(g, font, WmiVector.trim(font, WmiText.s("wmi.settings.notes_depth_hint"), BOX_W - 20),
                labelX, y + 4, th.textMuted);
        y += ROW_H;
        WmiDraw.drawText(g, font, WmiVector.trim(font, WmiText.s("wmi.settings.notes_add_hint"), BOX_W - 20),
                labelX, y + 4, th.textMuted);
    }

    /** Onglet Widget : heure, jour, biome et lignes soleil / lune. */
    private void renderWidgetTab(GuiGraphicsExtractor g, WmiOverlay o, WmiTheme th, int x0, int y, int mouseX, int mouseY) {
        WmiSettings cfg = o.settings();
        int labelX = x0 + 10;
        int ctrlX = x0 + 138;
        int ctrlW = BOX_W - 148;
        row(g, o, WmiText.s("wmi.settings.center_left"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.center_left." + (cfg.showCenterLeftHud() ? "on" : "off")), cfg.showCenterLeftHud(), mouseX, mouseY,
                () -> cfg.setShowCenterLeftHud(!cfg.showCenterLeftHud()));
        y += ROW_H;
        row(g, o, WmiText.s("wmi.settings.sun_moon"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.sun_moon." + (cfg.showSunMoonLines() ? "on" : "off")), cfg.showSunMoonLines(), mouseX, mouseY,
                () -> cfg.setShowSunMoonLines(!cfg.showSunMoonLines()));
        y += ROW_H;
        row(g, o, WmiText.s("wmi.settings.corner_widget"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.corner_widget." + (cfg.hudCornerWidget() ? "on" : "off")), cfg.hudCornerWidget(), mouseX, mouseY,
                () -> cfg.setHudCornerWidget(!cfg.hudCornerWidget()));
        y += ROW_H;
        row(g, o, WmiText.s("wmi.settings.clock_in_game"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.clock_in_game." + (cfg.hudClockInGame() ? "on" : "off")), cfg.hudClockInGame(), mouseX, mouseY,
                () -> cfg.setHudClockInGame(!cfg.hudClockInGame()));
        y += ROW_H;
        row(g, o, WmiText.s("wmi.settings.clock_mod"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.clock_mod." + (cfg.hudClockShowMod() ? "on" : "off")), cfg.hudClockShowMod(), mouseX, mouseY,
                () -> cfg.setHudClockShowMod(!cfg.hudClockShowMod()));
        y += ROW_H;
        row(g, o, WmiText.s("wmi.settings.size_position"), labelX, y);
        button(g, o, ctrlX, y, ctrlW, 15, WmiText.s("wmi.settings.reset"), false, mouseX, mouseY, cfg::resetHudLayout, null);
        y += ROW_H;
        WmiDraw.drawText(g, font, WmiVector.trim(font, WmiText.s("wmi.settings.widget_hint"), BOX_W - 20),
                labelX, y + 4, th.textMuted);
    }

    /** Onglet Général : cahier de recettes, thème, rendu, grille, barre d'outils, langue des noms. */
    private void renderGeneralTab(GuiGraphicsExtractor g, WmiOverlay o, WmiTheme th, int x0, int y, int mouseX, int mouseY) {
        WmiSettings cfg = o.settings();
        int labelX = x0 + 10;
        int ctrlX = x0 + 138;
        int ctrlW = BOX_W - 148;
        boolean custom = th.isCustom();
        // 1. Cahier de recettes de Minecraft
        row(g, o, WmiText.s("wmi.settings.vanilla_book"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.vanilla_book." + (cfg.vanillaBookEnabled() ? "on" : "off")), cfg.vanillaBookEnabled(), mouseX, mouseY,
                () -> o.setVanillaBookEnabled(!cfg.vanillaBookEnabled()));
        y += ROW_H;

        // 2. Thème
        row(g, o, WmiText.s("wmi.settings.theme"), labelX, y);
        stepper(g, o, ctrlX, y, ctrlW, th.label(), mouseX, mouseY, () -> cfg.cycleThemeFromSettings(-1), () -> cfg.cycleThemeFromSettings(1));
        y += ROW_H;

        // 2 bis. Couleur du thème « Personnalisé » : teinte, saturation, luminosité (clic ou glisser)
        if (custom) {
            float h = cfg.customHue();
            float s = cfg.customSat();
            float v = cfg.customVal();
            row(g, o, WmiText.s("wmi.settings.hue"), labelX, y);
            slider(g, o, 0, ctrlX, y, ctrlW, h, t -> ThemePalette.hsvToRgb(t, 1.0F, 1.0F),
                    val -> cfg.setCustomHsv(val, cfg.customSat(), cfg.customVal(), false));
            y += ROW_H;
            row(g, o, WmiText.s("wmi.settings.saturation"), labelX, y);
            slider(g, o, 1, ctrlX, y, ctrlW, s, t -> ThemePalette.hsvToRgb(h, t, Math.max(v, 0.35F)),
                    val -> cfg.setCustomHsv(cfg.customHue(), val, cfg.customVal(), false));
            y += ROW_H;
            row(g, o, WmiText.s("wmi.settings.brightness"), labelX, y);
            slider(g, o, 2, ctrlX, y, ctrlW, v, t -> ThemePalette.hsvToRgb(h, s, t),
                    val -> cfg.setCustomHsv(cfg.customHue(), cfg.customSat(), val, false));
            // Pastille de la couleur choisie, à gauche du libellé de la ligne « Thème »
            int themeRowY = y - 3 * ROW_H;
            g.fill(labelX + 99, themeRowY + 1, labelX + 123, themeRowY + 14, th.innerBorder);
            g.fill(labelX + 100, themeRowY + 2, labelX + 122, themeRowY + 13, 0xFF000000 | cfg.customColor());
            y += ROW_H;
        }

        // 3. Rendu lissé ou en pixels
        row(g, o, WmiText.s("wmi.settings.render"), labelX, y);
        String render = WmiText.s(th.vanilla() ? "wmi.settings.render.pixels_vanilla" : (WmiVector.preferred() ? "wmi.settings.render.smooth" : "wmi.settings.render.pixels"));
        toggle(g, o, ctrlX, y, ctrlW, render, WmiVector.preferred() && !th.vanilla(), mouseX, mouseY, cfg::toggleVectorFromSettings);
        y += ROW_H;

        // 3 bis. Taille propre de l'interface de WMI : automatique (taille en cours entre parenthèses), comme le jeu, fixe
        row(g, o, WmiText.s("wmi.settings.ui_scale"), labelX, y);
        int uiScale = cfg.uiScale();
        String uiScaleLabel = uiScale == WmiSettings.UI_SCALE_AUTO ? WmiText.s("wmi.settings.ui_scale.auto", WmiUiScale.targetScale())
                : uiScale == WmiSettings.UI_SCALE_GAME ? WmiText.s("wmi.settings.ui_scale.game")
                : WmiText.s("wmi.settings.ui_scale.size", uiScale);
        stepper(g, o, ctrlX, y, ctrlW, uiScaleLabel,
                mouseX, mouseY, () -> cfg.setUiScale(uiScale - 1), () -> cfg.setUiScale(uiScale + 1));
        y += ROW_H;

        // 4 à 6. Grille d'objets du panneau de droite
        row(g, o, WmiText.s("wmi.settings.icon_size"), labelX, y);
        stepper(g, o, ctrlX, y, ctrlW, cfg.gridIconSize() + " px", mouseX, mouseY,
                () -> cfg.setGridIconSize(cfg.gridIconSize() - 2), () -> cfg.setGridIconSize(cfg.gridIconSize() + 2));
        y += ROW_H;

        row(g, o, WmiText.s("wmi.settings.columns"), labelX, y);
        stepper(g, o, ctrlX, y, ctrlW, cfg.gridColumns() == 0 ? WmiText.s("wmi.settings.auto") : Integer.toString(cfg.gridColumns()), mouseX, mouseY,
                () -> cfg.setGridColumns(cfg.gridColumns() - 1), () -> cfg.setGridColumns(cfg.gridColumns() + 1));
        y += ROW_H;

        row(g, o, WmiText.s("wmi.settings.rows"), labelX, y);
        stepper(g, o, ctrlX, y, ctrlW, cfg.gridRows() == 0 ? WmiText.s("wmi.settings.auto") : Integer.toString(cfg.gridRows()), mouseX, mouseY,
                () -> cfg.setGridRows(cfg.gridRows() - 1), () -> cfg.setGridRows(cfg.gridRows() + 1));
        y += ROW_H;

        // 7. Barre d'outils
        row(g, o, WmiText.s("wmi.settings.toolbar"), labelX, y);
        stepper(g, o, ctrlX, y, ctrlW, cfg.utilityBarPosition().label(), mouseX, mouseY,
                () -> cfg.cycleUtilityBarPosition(-1), () -> cfg.cycleUtilityBarPosition(1));
        y += ROW_H;

        // 7 bis. Libellés de la barre horizontale (si la place le permet)
        row(g, o, WmiText.s("wmi.settings.toolbar_labels"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.toolbar_labels." + (cfg.toolbarLabels() ? "on" : "off")),
                cfg.toolbarLabels(), mouseX, mouseY, cfg::toggleToolbarLabels);
        y += ROW_H;

        // Langue des noms d'objets : jeu ou anglais (l'interface garde la langue du jeu)
        row(g, o, WmiText.s("wmi.settings.item_names"), labelX, y);
        toggle(g, o, ctrlX, y, ctrlW, WmiText.s("wmi.settings.item_names." + (cfg.englishNames() ? "english" : "game")), cfg.englishNames(), mouseX, mouseY,
                () -> cfg.setEnglishNames(!cfg.englishNames()));
        y += ROW_H;

        // Touche de la fenêtre « Accès rapide » : clic puis touche voulue ; clic droit : touche par défaut (²)
        row(g, o, WmiText.s("wmi.settings.quick_key"), labelX, y);
        String keyLabel = capturingQuickKey ? WmiText.s("wmi.settings.quick_key.press")
                : WmiClient.KEY_QUICK.getTranslatedKeyMessage().getString();
        button(g, o, ctrlX, y, ctrlW, 15, keyLabel, capturingQuickKey, mouseX, mouseY,
                () -> capturingQuickKey = !capturingQuickKey,
                () -> {
                    capturingQuickKey = false;
                    setQuickKey(WmiClient.KEY_QUICK.getDefaultKey());
                });
        y += ROW_H;
    }

    /** La touche passe par la liste des contrôles de Minecraft : même réglage que dans « Commandes », enregistré dans options.txt. */
    private static void setQuickKey(InputConstants.Key key) {
        WmiClient.KEY_QUICK.setKeyModifierAndCode(KeyModifier.NONE, key);
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingQuickKey) {
            capturingQuickKey = false;
            if (!event.isEscape()) {
                setQuickKey(InputConstants.getKey(event));
            }
            return true;
        }
        return super.keyPressed(event);
    }

    /** Barre de couleurs dégradée ({@code colorAt} : position de 0 à 1 vers 0xRRGGBB) avec repère de la valeur. */
    private void slider(GuiGraphicsExtractor g, WmiOverlay o, int rowId, int x, int y, int w, float value,
                        ColorAt colorAt, Consumer<Float> onChange) {
        WmiTheme th = o.theme();
        int h = 13;
        int by = y + 1;
        // Dégradé par tranches de 2 unités, dans un cadre fin
        g.fill(x - 1, by - 1, x + w + 1, by + h + 1, th.innerBorder);
        for (int i = 0; i < w; i += 2) {
            int sw = Math.min(2, w - i);
            g.fill(x + i, by, x + i + sw, by + h, 0xFF000000 | colorAt.rgb(i / (float) (w - 1)));
        }
        // Repère : trait clair cerné de sombre, lisible sur toutes les couleurs
        int mx = x + Math.round(value * (w - 1));
        g.fill(mx - 2, by - 2, mx + 2, by + h + 2, 0xFF101010);
        g.fill(mx - 1, by - 1, mx + 1, by + h + 1, 0xFFFFFFFF);
        sliders.add(new Slider(rowId, x, by - 2, w, h + 4, onChange));
    }

    @FunctionalInterface
    private interface ColorAt {
        int rgb(float t);
    }

    private void row(GuiGraphicsExtractor g, WmiOverlay o, String label, int x, int y) {
        WmiDraw.drawText(g, font, label, x, y + 4, o.theme().textMain);
    }

    private void toggle(GuiGraphicsExtractor g, WmiOverlay o, int x, int y, int w, String label, boolean active,
                        int mouseX, int mouseY, Runnable action) {
        button(g, o, x, y, w, 15, label, active, mouseX, mouseY, action, action);
    }

    /** Valeur encadrée de flèches : clic gauche sur la valeur = suivant, clic droit = précédent. */
    private void stepper(GuiGraphicsExtractor g, WmiOverlay o, int x, int y, int w, String value,
                         int mouseX, int mouseY, Runnable prev, Runnable next) {
        button(g, o, x, y, 15, 15, "<", false, mouseX, mouseY, prev, null);
        button(g, o, x + 17, y, w - 34, 15, value, false, mouseX, mouseY, next, prev);
        button(g, o, x + w - 15, y, 15, 15, ">", false, mouseX, mouseY, next, null);
    }

    private void button(GuiGraphicsExtractor g, WmiOverlay o, int x, int y, int w, int h, String label, boolean active,
                        int mouseX, int mouseY, Runnable left, @Nullable Runnable right) {
        WmiTheme th = o.theme();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = active ? th.accentSoft : (hover ? th.slotHover : th.slotBg);
        int brd = (active || hover) ? th.accent : th.innerBorder;
        WmiDraw.drawBox(g, x, y, w, h, bg, brd);
        String shown = WmiVector.trim(font, label, w - 6);
        WmiDraw.drawText(g, font, shown, x + (w - WmiVector.width(font, shown)) / 2, y + (h - 8) / 2, (active || hover) ? th.accent : th.textMain);
        zones.add(new Zone(x, y, w, h, left, right));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent rawEvent, boolean doubleClick) {
        MouseButtonEvent event = ui(rawEvent);
        if (WmiOverlayInput.isLeftClick(event.button())) {
            for (Slider sl : sliders) {
                if (sl.contains(event.x(), event.y())) {
                    draggingRow = sl.row();
                    sl.onChange().accept(sl.valueAt(event.x()));
                    return true;
                }
            }
        }
        for (Zone z : zones) {
            if (z.contains(event.x(), event.y())) {
                if (WmiOverlayInput.isRightClick(event.button()) && z.right() != null) {
                    z.right().run();
                    return true;
                }
                if (WmiOverlayInput.isLeftClick(event.button())) {
                    z.left().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingRow >= 0) {
            for (Slider sl : sliders) {
                if (sl.row() == draggingRow) {
                    sl.onChange().accept(sl.valueAt(ui(event).x()));
                    return true;
                }
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** Fin du glisser : la couleur est enregistrée une seule fois, au relâchement. */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingRow >= 0) {
            draggingRow = -1;
            WmiClient.overlay().settings().saveSettingsFromScreen();
            return true;
        }
        return super.mouseReleased(event);
    }
}
