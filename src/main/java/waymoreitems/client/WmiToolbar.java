package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Function;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
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

import waymoreitems.client.WmiButtons.QuickButton;

import static waymoreitems.client.WmiDraw.*;

/** Barre d'outils (verticale à gauche, horizontale en haut ou masquée), boutons « Paramètres » et bascule du navigateur. */
final class WmiToolbar {

    private static final Identifier ICO_DAY = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/day.png");
    private static final Identifier ICO_NOON = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/noon.png");
    private static final Identifier ICO_NIGHT = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/night.png");
    private static final Identifier ICO_CLEAR = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/clear.png");
    private static final Identifier ICO_RAIN = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/rain.png");
    private static final Identifier ICO_THUNDER = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/thunder.png");
    private static final Identifier ICO_HEAL = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/heal.png");
    private static final Identifier ICO_GEAR = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/gear.png");
    private static final Identifier ICO_TRASH = Identifier.fromNamespaceAndPath(WmiMod.MODID, "textures/gui/trash.png");

    private final WmiOverlay o;
    private final WmiSettings settings;

    /** Rectangles au dernier dessin : barre d'outils, bouton « Paramètres », bascule WMI / JEI (null si absents). */
    GuiLayout.Rect barBounds;
    GuiLayout.Rect settingsBounds;
    GuiLayout.Rect viewerBounds;

    WmiToolbar(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
    }

    // ==================== BARRE D'OUTILS (VERTICALE À GAUCHE, HORIZONTALE EN HAUT OU MASQUÉE) ====================

    /** Position de la barre d'outils, réglable dans « Paramètres ». */
    public enum UtilityBarPosition {
        LEFT_VERTICAL("wmi.toolbar.left_vertical"),
        TOP_HORIZONTAL("wmi.toolbar.top_horizontal"),
        HIDDEN("wmi.toolbar.hidden");

        public final String label;

        UtilityBarPosition(String label) {
            this.label = label;
        }

        public String label() {
            return WmiText.s(label);
        }
    }

    /** Élément de la barre : bouton d'action, indicateur (sans action) ou séparateur ({@code separator}). */
    record BarEntry(Identifier texture, ItemStack item, String label, String tooltip, boolean active,
                            Runnable onClick, Runnable onRightClick, boolean separator) {
        static BarEntry sep() {
            return new BarEntry(null, null, "", "", false, null, null, true);
        }
    }

    /**
     * Sous-barre indépendante (son propre panneau) : « Heure », « Météo », « Gameplay » ou « Outils ».
     * {@code labelled} : libellé toujours affiché à côté du bouton (mode de jeu) ; {@code collapseToggle} : la
     * sous-barre porte le bouton qui réduit ou développe la barre.
     */
    record BarGroup(List<BarEntry> entries, boolean labelled, boolean collapseToggle) {
    }

    /** Tout ce dont dépend le contenu de la barre ; la liste n'est reconstruite que lorsqu'il change. */
    private record BarKey(boolean admin, @Nullable GameType mode, boolean collapsed, String language) {
    }

    /** Modes proposés par le bouton « Gameplay », dans l'ordre du cycle (spectateur exclu). */
    private static final List<GameType> MODE_CYCLE = List.of(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE);

    private static final int CELL = 20;
    private static final int PAD = 3;
    private static final int GROUP_GAP = 4;
    private static final int TOGGLE_SIZE = 8;

    private @Nullable BarKey cachedKey;
    private List<BarGroup> cachedGroups = List.of();
    private List<BarEntry> cachedEntries = List.of();

    /**
     * Sous-barres, reprises du cache tant que droits, mode de jeu, réduction et langue sont inchangés. Liste vide sans
     * droits d'administration : la barre n'est alors pas dessinée (date, heure et biome sont dans le widget ; Notes
     * se replient depuis leur propre en-tête).
     */
    List<BarGroup> buildBarGroups(Minecraft mc) {
        GameType mode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : null;
        BarKey key = new BarKey(o.hasAdminRights(mc), mode, settings.toolbarCollapsed,
                mc.getLanguageManager() != null ? mc.getLanguageManager().getSelected() : "");
        if (!key.equals(cachedKey)) {
            cachedGroups = List.copyOf(createBarGroups(key));
            List<BarEntry> flat = new ArrayList<>();
            for (BarGroup grp : cachedGroups) {
                if (!flat.isEmpty()) {
                    flat.add(BarEntry.sep());
                }
                flat.addAll(grp.entries());
            }
            cachedEntries = List.copyOf(flat);
            cachedKey = key;
        }
        return cachedGroups;
    }

    /** Entrées de toutes les sous-barres à la suite, séparées par un séparateur (décorations de la mise en page). */
    List<BarEntry> buildBarEntries(Minecraft mc) {
        buildBarGroups(mc);
        return cachedEntries;
    }

    private List<BarGroup> createBarGroups(BarKey key) {
        List<BarGroup> groups = new ArrayList<>();
        if (!key.admin()) {
            return groups;
        }
        groups.add(new BarGroup(List.of(
                new BarEntry(ICO_DAY, null, WmiText.s("wmi.bar.day"), WmiText.s("wmi.bar.day_tip"), false, action("time_day"), null, false),
                new BarEntry(ICO_NOON, null, WmiText.s("wmi.bar.noon"), WmiText.s("wmi.bar.noon_tip"), false, action("time_noon"), null, false),
                new BarEntry(ICO_NIGHT, null, WmiText.s("wmi.bar.night"), WmiText.s("wmi.bar.night_tip"), false, action("time_night"), null, false)),
                false, false));
        groups.add(new BarGroup(List.of(
                new BarEntry(ICO_CLEAR, null, WmiText.s("wmi.bar.clear"), WmiText.s("wmi.bar.clear_tip"), false, action("weather_clear"), null, false),
                new BarEntry(ICO_RAIN, null, WmiText.s("wmi.bar.rain"), WmiText.s("wmi.bar.rain_tip"), false, action("weather_rain"), null, false),
                new BarEntry(ICO_THUNDER, null, WmiText.s("wmi.bar.thunder"), WmiText.s("wmi.bar.thunder_tip"), false, action("weather_thunder"), null, false)),
                false, true));
        if (key.collapsed()) {
            return groups;
        }
        GameType mode = key.mode() != null && MODE_CYCLE.contains(key.mode()) ? key.mode() : GameType.SURVIVAL;
        String modeName = mode.getShortDisplayName().getString();
        groups.add(new BarGroup(List.of(new BarEntry(null, new ItemStack(modeIcon(mode)), modeName,
                WmiText.s("wmi.bar.gamemode_tip", modeName), false, cycleMode(mode, 1), cycleMode(mode, -1), false)),
                true, false));
        groups.add(new BarGroup(List.of(
                new BarEntry(ICO_HEAL, null, WmiText.s("wmi.bar.heal"), WmiText.s("wmi.bar.heal_tip"), false, action("heal_feed"), null, false),
                new BarEntry(ICO_TRASH, null, WmiText.s("wmi.bar.purge"), WmiText.s("wmi.bar.purge_tip"), false, action("clear_drops"), null, false),
                new BarEntry(null, new ItemStack(Items.LAVA_BUCKET), WmiText.s("wmi.bar.clear_inventory"),
                        WmiText.s("wmi.bar.clear_inventory_tip"), false, this::clearInventoryClicked, null, false),
                new BarEntry(null, new ItemStack(Items.COMPASS), WmiText.s("wmi.bar.biomes"), WmiText.s("wmi.bar.biomes_tip"), false,
                        () -> openOver(WmiBiomeScreen::new), null, false),
                new BarEntry(null, new ItemStack(Items.ENCHANTED_BOOK), WmiText.s("wmi.bar.enchantments"), WmiText.s("wmi.bar.enchantments_tip"), false,
                        () -> openOver(WmiEnchantScreen::new), null, false)),
                false, false));
        return groups;
    }

    private static Item modeIcon(GameType mode) {
        return switch (mode) {
            case CREATIVE -> Items.GRASS_BLOCK;
            case ADVENTURE -> Items.FILLED_MAP;
            default -> Items.IRON_SWORD;
        };
    }

    /** Passe au mode suivant ({@code step} = 1) ou précédent (-1) du cycle survie, créatif, aventure. */
    private static Runnable cycleMode(GameType current, int step) {
        GameType next = MODE_CYCLE.get(Math.floorMod(MODE_CYCLE.indexOf(current) + step, MODE_CYCLE.size()));
        return action("mode_" + next.getName());
    }

    /** Ouvre une fenêtre de WMI par-dessus l'écran courant, qui y revient à la fermeture. */
    private static void openOver(Function<@Nullable Screen, Screen> factory) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(factory.apply(mc.gui.screen()));
    }

    /** Action irréversible : une fenêtre demande confirmation (à la place du double clic). */
    private void clearInventoryClicked() {
        openOver(parent -> new WmiConfirmScreen(parent, WmiText.c("wmi.bar.clear_inventory"),
                WmiText.s("wmi.confirm.clear_inventory"), WmiText.s("wmi.confirm.clear_inventory_ok"),
                () -> WmiNetwork.send(new WmiPayloads.ActionPayload("clear_inventory"))));
    }

    private void toggleCollapsed() {
        settings.toolbarCollapsed = !settings.toolbarCollapsed;
        settings.save();
    }

    static Runnable action(String id) {
        return () -> WmiNetwork.send(new WmiPayloads.ActionPayload(id));
    }

    private static int labelledWidth(Font font, BarGroup grp) {
        return CELL + 4 + WmiVector.width(font, grp.entries().getFirst().label()) + 5;
    }

    /**
     * Barre verticale : sous-barres empilées, chacune dans son panneau ; icônes seules (libellé en infobulle), sauf
     * « Gameplay » dont le mode est écrit à côté du bouton. La hauteur est bornée par la mise en page.
     */
    void renderUtilityBarVertical(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Font font,
                                          GuiLayout.Resolver lay, int mouseX, int mouseY) {
        List<BarGroup> groups = buildBarGroups(Minecraft.getInstance());
        if (groups.isEmpty()) {
            return;
        }
        int[] widths = new int[groups.size()];
        int[] heights = new int[groups.size()];
        int totalW = 0;
        int totalH = 0;
        for (int i = 0; i < groups.size(); i++) {
            BarGroup grp = groups.get(i);
            int n = grp.entries().size();
            widths[i] = grp.labelled() ? PAD + labelledWidth(font, grp) : CELL + 2 * PAD;
            heights[i] = 2 * PAD + n * CELL + (n - 1) * 2 + (grp.collapseToggle() ? 2 + TOGGLE_SIZE : 0);
            totalW = Math.max(totalW, widths[i]);
            totalH += heights[i] + (i > 0 ? GROUP_GAP : 0);
        }
        GuiLayout.Rect r = lay.place("toolbarVertical", totalW, totalH);
        if (r == null) {
            return;
        }
        int x = r.x();
        int bottom = r.y() + r.h();
        int py = r.y();
        int drawnBottom = py;
        for (int i = 0; i < groups.size(); i++) {
            BarGroup grp = groups.get(i);
            if (py + heights[i] > bottom) {
                break;
            }
            drawFrostedPanel(g, x, py, widths[i], heights[i]);
            int cy = py + PAD;
            for (BarEntry e : grp.entries()) {
                if (grp.labelled()) {
                    drawBarButton(g, font, x + PAD, cy, CELL, CELL, e, false, mouseX, mouseY);
                    drawText(g, font, e.label(), x + PAD + CELL + 4, cy + (CELL - 8) / 2, theme().textMain);
                } else {
                    drawBarButton(g, font, x + PAD, cy, CELL, CELL, e, false, mouseX, mouseY);
                }
                cy += CELL + 2;
            }
            if (grp.collapseToggle()) {
                drawCollapseToggle(g, x + PAD, cy, widths[i] - 2 * PAD, TOGGLE_SIZE, true, mouseX, mouseY);
            }
            drawnBottom = py + heights[i];
            py += heights[i] + GROUP_GAP;
        }
        barBounds = new GuiLayout.Rect(x, r.y(), totalW, drawnBottom - r.y());
    }

    /**
     * Barre horizontale en haut à gauche : sous-barres côte à côte, bornées par la mise en page pour ne jamais
     * chevaucher le panneau de droite ni « Paramètres » ; libellés retirés si la place manque (sauf le mode de jeu).
     */
    void renderUtilityBarHorizontal(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Font font,
                                            GuiLayout.Resolver lay, int mouseX, int mouseY) {
        List<BarGroup> groups = buildBarGroups(Minecraft.getInstance());
        if (groups.isEmpty()) {
            return;
        }
        int bh = 16;
        int withLabels = 0;
        int iconsOnly = 0;
        for (int i = 0; i < groups.size(); i++) {
            withLabels += horizontalGroupWidth(font, groups.get(i), true) + (i > 0 ? GROUP_GAP : 0);
            iconsOnly += horizontalGroupWidth(font, groups.get(i), false) + (i > 0 ? GROUP_GAP : 0);
        }
        // Libellés seulement si le réglage les demande (désactivé par défaut) et si la place le permet
        boolean wantLabels = settings.toolbarLabels;
        GuiLayout.Rect r = lay.place("toolbarHorizontal", wantLabels ? withLabels : iconsOnly, 22);
        if (r == null) {
            return;
        }
        boolean labels = wantLabels && r.w() >= withLabels;
        int barW = labels ? r.w() : Math.min(r.w(), iconsOnly);
        int x = r.x();
        int y = r.y();
        int barH = r.h();
        lay.set("toolbarHorizontal", new GuiLayout.Rect(x, y, barW, barH));
        int px = x;
        int drawnRight = x;
        for (BarGroup grp : groups) {
            int gw = horizontalGroupWidth(font, grp, labels);
            if (px + gw > x + barW) {
                break;
            }
            drawFrostedPanel(g, px, y, gw, barH);
            int cx = px + 4;
            for (BarEntry e : grp.entries()) {
                boolean showLabel = labels || grp.labelled();
                int w = 18 + (showLabel ? WmiVector.width(font, e.label()) + 4 : 0);
                drawBarButton(g, font, cx, y + 3, w, bh, e, showLabel, mouseX, mouseY);
                cx += w + 2;
            }
            if (grp.collapseToggle()) {
                drawCollapseToggle(g, cx, y + 3, TOGGLE_SIZE + 1, bh, false, mouseX, mouseY);
            }
            drawnRight = px + gw;
            px += gw + GROUP_GAP;
        }
        barBounds = new GuiLayout.Rect(x, y, drawnRight - x, barH);
    }

    private static int horizontalGroupWidth(Font font, BarGroup grp, boolean labels) {
        int w = 4;
        for (BarEntry e : grp.entries()) {
            w += 18 + ((labels || grp.labelled()) ? WmiVector.width(font, e.label()) + 4 : 0) + 2;
        }
        if (grp.collapseToggle()) {
            w += TOGGLE_SIZE + 1 + 2;
        }
        return w + 2;
    }

    /**
     * Bouton qui réduit la barre aux sous-barres « Heure » et « Météo » ou la développe : chevron dessiné
     * (vers le haut ou la gauche pour réduire, vers le bas ou la droite pour développer).
     */
    private void drawCollapseToggle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean vertical,
                                    int mouseX, int mouseY) {
        boolean collapsed = settings.toolbarCollapsed;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawBox(g, x, y, w, h, hover ? theme().slotHover : theme().slotBg, hover ? theme().accent : theme().innerBorder);
        int col = hover ? theme().accent : theme().textMuted;
        int cx = x + w / 2;
        int cy = y + h / 2;
        for (int k = 0; k < 3; k++) {
            // Chevron de trois rangs : 5, 3 puis 1 pixel, pointe vers la direction de l'action
            int half = 2 - k;
            if (vertical) {
                int row = collapsed ? cy - 1 + k : cy + 1 - k;
                bar(g, cx - half, row, cx + half + 1, row + 1, col);
            } else {
                int column = collapsed ? cx - 1 + k : cx + 1 - k;
                bar(g, column, cy - half, column + 1, cy + half + 1, col);
            }
        }
        String tip = WmiText.s(collapsed ? "wmi.bar.expand_tip" : "wmi.bar.collapse_tip");
        o.buttons.drawn.add(new QuickButton(x, y, w, h, tip, tip, this::toggleCollapsed, null));
    }

    void drawBarButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, BarEntry e, boolean label,
                               int mouseX, int mouseY) {
        boolean clickable = e.onClick() != null;
        boolean hover = clickable && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = e.active() ? theme().accentSoft : (hover ? theme().slotHover : theme().slotBg);
        int brd = (e.active() || hover) ? theme().accent : (clickable ? theme().border : theme().innerBorder);
        drawBox(g, x, y, w, h, bg, brd);
        int iconX = label ? x + 3 : x + (w - 12) / 2;
        if (e.texture() != null) {
            WmiDraw.drawIcon(g, e.texture(), iconX, y + (h - 12) / 2, 12, 12);
        } else if (e.item() != null && !e.item().isEmpty()) {
            float sc = Math.min(1.0F, (h - 2) / 16.0F);
            int size = Math.round(16 * sc);
            int ix = label ? x + 1 : x + (w - size) / 2;
            WmiDraw.drawScaledItem(g, e.item(), ix, y + (h - size) / 2.0F, sc);
        }
        if (label) {
            drawText(g, font, e.label(), x + 17, y + (h - 8) / 2, (e.active() || hover) ? theme().accent : theme().textMain);
        }
        o.buttons.drawn.add(new QuickButton(x, y, w, h, e.label(), e.tooltip(), e.onClick(), e.onRightClick()));
    }

    /**
     * Bouton carré « Paramètres » : engrenage de fer, collé au bas du panneau de droite, à sa gauche ; libellé
     * en infobulle.
     */
    void renderSettingsButton(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Font font,
                                      GuiLayout.Resolver lay, int mouseX, int mouseY) {
        GuiLayout.Rect r = lay.place("settings", 20, 20);
        if (r == null) {
            settingsBounds = null;
            return;
        }
        boolean hover = r.contains(mouseX, mouseY);
        drawBox(g, r.x(), r.y(), r.w(), r.h(), hover ? theme().slotHover : theme().panelBg, hover ? theme().accent : theme().border);
        WmiDraw.drawIcon(g, ICO_GEAR, r.x() + (r.w() - 16) / 2, r.y() + (r.h() - 16) / 2, 16, 16);
        o.buttons.drawn.add(new QuickButton(r.x(), r.y(), r.w(), r.h(), WmiText.s("wmi.button.settings"),
                WmiText.s("wmi.button.settings_tip"),
                () -> Minecraft.getInstance().gui.setScreen(new WmiSettingsScreen(screen)), null));
        settingsBounds = r;
    }

    /**
     * Bouton carré de bascule du navigateur de recettes, au-dessus de « Paramètres » (seulement si JEI est installé) :
     * sigle du navigateur actif ({@code WMI} ou {@code JEI}) en petit ; libellé complet en infobulle.
     */
    void renderViewerToggle(GuiGraphicsExtractor g, Font font, GuiLayout.Resolver lay, int mouseX, int mouseY) {
        boolean jei = settings.jeiModeActive();
        GuiLayout.Rect r = lay.place("viewerToggle", 20, 20);
        if (r == null) {
            viewerBounds = null;
            return;
        }
        boolean hover = r.contains(mouseX, mouseY);
        drawBox(g, r.x(), r.y(), r.w(), r.h(), hover ? theme().slotHover : theme().panelBg, hover ? theme().accent : theme().border);
        String tag = jei ? "JEI" : "WMI";
        int tw = smallTextWidth(font, tag);
        WmiDraw.pushScale(g, r.x() + (r.w() - tw) / 2.0F, r.y() + (r.h() - 6) / 2.0F, 0.75F);
        drawText(g, font, tag, 0, 0, hover ? theme().accent : theme().textMain);
        WmiDraw.popScale(g);
        String label = WmiText.s(jei ? "wmi.button.viewer_jei" : "wmi.button.viewer_wmi");
        o.buttons.drawn.add(new QuickButton(r.x(), r.y(), r.w(), r.h(), label,
                label + ". " + WmiText.s(jei ? "wmi.button.viewer_jei_tip" : "wmi.button.viewer_wmi_tip"),
                settings::toggleRecipeViewer, null));
        viewerBounds = r;
    }
}
