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

import waymoreitems.client.WmiButtons.QuickButton;

import static waymoreitems.client.WmiDraw.*;

/**
 * Heure du monde : widget de l'inventaire (crans d'échelle, déplacement, poignée), widget du coin de l'écran de jeu,
 * horloge compacte en haut de l'écran et lignes du soleil et de la lune.
 */
final class WmiTimeWidget {

    static final int HUD_CORNER_W = 112;
    /** Cran 50 % (dernier de {@link WmiVector#STEP_PERCENTS}). */
    static final int HUD_CORNER_STEP = 6;
    /** Cran 65 % pour le nom du mod du widget du coin. */
    static final int HUD_CORNER_MOD_STEP = 7;
    static final int HUD_CORNER_X = 8;
    static final int HUD_CORNER_Y = 8;
    static final int HUD_GRIP = 9;

    private final WmiOverlay o;
    private final WmiSettings settings;

    /** Rectangle du widget de l'inventaire au dernier dessin (null s'il n'est pas dessiné). */
    GuiLayout.Rect bounds;
    boolean hudMoving = false;
    boolean hudResizing = false;
    int hudGrabDx;
    int hudGrabDy;
    int hudResizeStartMouseX;
    double hudResizeStartScale;
    int hudResizeStartW = 1;

    WmiTimeWidget(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
    }

    /** Horloge compacte : taille fixe (largeur constante, hauteur fixée par l'option du mod), jamais liée au nom du biome. */
    private static final int HUD_CLOCK_W = 100;
    private static final int HUD_CLOCK_TOP = 6;

    int hudClockHeight() {
        return settings.hudClockShowMod ? 31 : 24;
    }

    /**
     * Décalage vers le bas des barres de boss de Minecraft : leur barre commence à y = 12 et leur titre est dessiné
     * 9 unités plus haut (y = 3) ; le titre doit passer sous l'horloge compacte (3 unités de marge). 0 si l'horloge
     * n'est pas affichée (écran ouvert, interface masquée, option).
     */
    public int hudClockBossShift() {
        Minecraft mc = Minecraft.getInstance();
        if (!settings.hudClockInGame || mc.level == null || mc.player == null || WmiClient.hudSuppressed(mc) || mc.gui.screen() != null) {
            return 0;
        }
        // L'horloge est dessinée à la taille propre de WMI : décalage converti en unités du jeu
        return (int) Math.ceil((HUD_CLOCK_TOP + hudClockHeight()) * WmiUiScale.factor());
    }

    /**
     * Horloge compacte et discrète en haut et au centre de l'écran de jeu (jamais avec un écran ouvert : la couche HUD
     * s'arrête alors) : heure, liseré, jour, puis biome dessous et, en option, mod du biome sur une troisième ligne
     * (réservée même pour un biome vanilla). Mêmes proportions et mêmes couleurs que le grand widget, à 75 % de taille (heure et jour à 100 % de la petite police) ;
     * taille fixe : les noms trop longs sont tronqués.
     */
    public void renderHudClock(GuiGraphicsExtractor g, Font font) {
        Minecraft mc = Minecraft.getInstance();
        if (!settings.hudClockInGame || mc.level == null || mc.player == null) {
            return;
        }
        String[] env = environmentLines(mc);
        int step = 0;
        double k = 0.75;
        int padX = 7;
        int textW = HUD_CLOCK_W - padX * 2;
        String time = env[0];
        String day = env[2];
        String biome = hudTrim(font, env[1], 0, step, textW);
        String mod = settings.hudClockShowMod ? hudTrim(font, env[3], 0, step, textW) : "";
        int gap = 4;
        // Heure et jour en taille 100 % de la petite police (8,5), biome et mod à 75 %
        int timeW = hudWidth(font, time, 0, 1);
        int dayW = hudWidth(font, day, 0, 1);
        int rowW = timeW + gap + 1 + gap + dayW;
        int w = HUD_CLOCK_W;
        int h = hudClockHeight();
        int x = (WmiUiScale.guiWidth(g) - w) / 2;
        int y = HUD_CLOCK_TOP;
        drawFrostedPanel(g, x, y, w, h);
        // Lignes de base (marges haute et basse égales de 4) : heure et jour, biome, mod
        int row1Base = y + 10;
        int biomeBase = y + 18;
        int modBase = y + 25;
        int cx = x + w / 2;
        int rx = cx - rowW / 2;
        hudText(g, font, time, 0, 1, 1.0, rx, row1Base, timeColor(mc));
        bar(g, rx + timeW + gap, y + 3, rx + timeW + gap + 1, row1Base + 2, theme().innerBorder);
        hudText(g, font, day, 0, 1, 1.0, rx + timeW + gap * 2 + 1, row1Base, theme().textMuted);
        hudText(g, font, biome, 0, step, k, cx - hudWidth(font, biome, 0, step) / 2, biomeBase, theme().textMuted);
        if (settings.hudClockShowMod && !mod.isEmpty()) {
            hudText(g, font, mod, 0, step, k, cx - hudWidth(font, mod, 0, step) / 2, modBase, theme().textMuted);
        }
    }

    /**
     * Widget de l'heure : heure, jour, séparateur, biome et, hors Minecraft, mod d'origine du biome. Tout est centré
     * dans le panneau (marges égales en haut et en bas, la ligne du mod ne s'ajoute que si elle existe). Échelle réglable
     * par la poignée du coin bas droit, position libre par glissement (sauvegardées).
     */
    void renderCenterLeftHud(GuiGraphicsExtractor g, Font font, GuiLayout.Resolver lay, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        String[] env = environmentLines(mc);

        // Redimensionnement en cours : l'échelle suit la souris, proportionnellement à la largeur au début du geste
        if (hudResizing) {
            settings.hudScale = Math.max(WmiSettings.HUD_SCALE_MIN, Math.min(WmiSettings.HUD_SCALE_MAX,
                    hudResizeStartScale * (hudResizeStartW + (mouseX - hudResizeStartMouseX)) / (double) Math.max(1, hudResizeStartW)));
        }
        // Crans d'échelle : chaque cran a ses polices, le texte reste net (jamais d'agrandissement par matrice en rendu lissé)
        WidgetMetrics m = measureWidget(font, env, hudStep(), 0);
        int w = m.w();
        int h = m.h();

        GuiLayout.Rect r = lay.place("widget", w, h);
        if (r == null) {
            return;
        }
        // La taille est celle du cran (proportions du bloc toujours conservées) : la mise en page ne donne que la position
        int x = r.x() + (r.w() - w) / 2;
        int y = r.y() + (r.h() - h) / 2;
        int sw = Math.max(1, o.lastScreenW);
        int sh = Math.max(1, o.lastScreenH);
        int autoX = x;
        int autoY = y;
        if (hudMoving) {
            x = mouseX - hudGrabDx;
            y = mouseY - hudGrabDy;
        } else if (settings.hudPosXRatio >= 0 && settings.hudPosYRatio >= 0) {
            x = (int) Math.round(settings.hudPosXRatio * sw);
            y = (int) Math.round(settings.hudPosYRatio * sh);
        }
        // Un simple clic sans déplacement ne fige pas la position automatique
        if ((hudMoving && (x != autoX || y != autoY)) || (!hudMoving && settings.hudPosXRatio >= 0)) {
            x = Math.max(0, Math.min(x, sw - w));
            y = Math.max(0, Math.min(y, sh - h));
            settings.hudPosXRatio = (double) x / sw;
            settings.hudPosYRatio = (double) y / sh;
        }
        lay.set("widget", new GuiLayout.Rect(x, y, w, h));
        drawFrostedPanel(g, x, y, w, h);
        bounds = new GuiLayout.Rect(x, y, w, h);

        drawWidgetContent(g, font, env, m, x, y);

        // Poignée du coin bas droit, visible au survol du widget ou pendant le redimensionnement
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        if (hover || hudResizing || hudMoving) {
            int gc = hudResizing || overHudGrip(mouseX, mouseY) ? theme().accent : theme().textMuted;
            bar(g, x + w - 5, y + h - 9, x + w - 3, y + h - 3, gc);
            bar(g, x + w - 9, y + h - 5, x + w - 3, y + h - 3, gc);
        }
    }

    /** Mesures d'un widget de l'heure à un cran donné : unités de dessin, largeur, hauteur et lignes de base. */
    record WidgetMetrics(boolean vector, int step, double k, double u, boolean hasMod, boolean lines, int padX,
                                 int unitW, int unitH, int w, int h, int digBase, int dayBase, int sepY, int bioBase, int modBase) {
    }

    /**
     * Mesure le widget : largeur d'après le texte le plus large ({@code fixedW} = 0) ou largeur imposée en pixels
     * ({@code fixedW} &gt; 0, les textes trop longs sont tronqués au dessin). Mesures en unités de dessin : tailles réelles
     * en rendu lissé ; en rendu en pixels, unités naturelles puis matrice.
     */
    WidgetMetrics measureWidget(Font font, String[] env, int step, int fixedW) {
        boolean vector = WmiVector.enabled();
        double k = WmiVector.STEP_PERCENTS[step] / 100.0;
        double u = vector ? k : 1.0;
        boolean hasMod = !env[3].isEmpty();
        // Avec les lignes soleil et lune, les marges latérales laissent la place de la ligne et de l'astre (8 + 4 + 10 de dégagement)
        boolean lines = settings.showSunMoonLines;
        int padX = (int) Math.round((lines ? 22 : 18) * u);
        int widest = Math.max(hudWidth(font, env[0], 2, step), Math.max(hudWidth(font, env[1], 1, step), hudWidth(font, env[2], 1, step)));
        if (hasMod) {
            widest = Math.max(widest, hudWidth(font, env[3], 0, step));
        }
        // Lignes de base (haut du panneau = 0) : marges égales, haut sur les chiffres, bas sur le dernier texte
        int digBase = (int) Math.round(33 * u);
        int dayBase = (int) Math.round(48 * u);
        int sepY = (int) Math.round(57 * u);
        int bioBase = (int) Math.round(75 * u);
        int modBase = (int) Math.round(88 * u);
        int unitH = hasMod ? (int) Math.round((88 + 2 + 16) * u) : (int) Math.round((75 + 3 + 16) * u);
        int unitW = widest + padX * 2;
        if (fixedW > 0) {
            unitW = vector ? fixedW : (int) Math.round(fixedW / k);
        }
        int w = (int) Math.round(vector ? unitW : unitW * k);
        int h = (int) Math.round(vector ? unitH : unitH * k);
        return new WidgetMetrics(vector, step, k, u, hasMod, lines, padX, unitW, unitH, w, h, digBase, dayBase, sepY, bioBase, modBase);
    }

    /** Contenu du widget (panneau déjà dessiné) : heure, jour, séparateur, biome, mod et lignes soleil et lune. */
    void drawWidgetContent(GuiGraphicsExtractor g, Font font, String[] env, WidgetMetrics m, int x, int y) {
        boolean vector = m.vector();
        int step = m.step();
        double k = m.k();
        double u = m.u();
        int textW = m.unitW() - m.padX() * 2;
        String time = env[0];
        String day = hudTrim(font, env[2], 1, step, textW);
        String biome = hudTrim(font, env[1], 1, step, textW);
        // Au cran 50 % (widget du coin), le nom du mod est légèrement plus grand (cran 65 %) pour rester lisible
        int modStep = step == HUD_CORNER_STEP ? HUD_CORNER_MOD_STEP : step;
        double modK = WmiVector.STEP_PERCENTS[modStep] / 100.0;
        String mod = hudTrim(font, env[3], 0, modStep, textW);
        if (!vector) {
            WmiDraw.pushScale(g, x, y, (float) k);
        }
        int ox = vector ? x : 0;
        int oy = vector ? y : 0;
        int cx = ox + (vector ? m.w() : m.unitW()) / 2;
        hudText(g, font, time, 2, step, k, cx - hudWidth(font, time, 2, step) / 2, oy + m.digBase(), timeColor(Minecraft.getInstance()));
        hudText(g, font, day, 1, step, k, cx - hudWidth(font, day, 1, step) / 2, oy + m.dayBase(), theme().textMuted);
        int sepHalf = (int) Math.round((vector ? m.w() : m.unitW()) / 2.0 - m.padX());
        bar(g, cx - sepHalf, oy + m.sepY(), cx + sepHalf, oy + m.sepY() + (int) Math.max(1, Math.round(u)), theme().innerBorder);
        hudText(g, font, biome, 1, step, k, cx - hudWidth(font, biome, 1, step) / 2, oy + m.bioBase(), theme().textMain);
        if (m.hasMod()) {
            hudText(g, font, mod, 0, modStep, vector ? modK : k, cx - hudWidth(font, mod, 0, modStep) / 2, oy + m.modBase(), theme().textMuted);
        }
        if (m.lines()) {
            double hourF = ((Minecraft.getInstance().level.getDefaultClockTime() + 6000L) % 24000L) / 1000.0;
            renderSunMoonLines(g, ox, oy, vector ? m.w() : m.unitW(), vector ? m.h() : m.unitH(), u, hourF,
                    (int) Math.round(14 * u), (int) Math.round(8 * u));
        }
        if (!vector) {
            WmiDraw.popScale(g);
        }
    }

    /**
     * Widget de l'heure dans le coin haut gauche de l'écran de jeu, au cran 50 %, de largeur fixe
     * {@link #HUD_CORNER_W} : les Notes se placent juste en dessous, à la même largeur. Rend la hauteur occupée (0 si
     * absent).
     */
    public int renderCornerWidget(GuiGraphicsExtractor g, Font font) {
        Minecraft mc = Minecraft.getInstance();
        if (!settings.hudCornerWidget || mc.level == null || mc.player == null) {
            return 0;
        }
        String[] env = environmentLines(mc);
        WidgetMetrics m = measureWidget(font, env, HUD_CORNER_STEP, HUD_CORNER_W);
        int x = hudCornerX(g, m.w());
        drawFrostedPanel(g, x, HUD_CORNER_Y, m.w(), m.h(), settings.hudOpacity / 100.0);
        drawWidgetContent(g, font, env, m, x, HUD_CORNER_Y);
        return m.h();
    }

    /** Abscisse d'un bloc du coin de l'écran de jeu (widget ou Notes), à gauche ou à droite selon le réglage. */
    int hudCornerX(GuiGraphicsExtractor g, int w) {
        return settings.hudSide == WmiSettings.HudSide.RIGHT ? WmiUiScale.guiWidth(g) - w - HUD_CORNER_X : HUD_CORNER_X;
    }

    /**
     * Couleur de l'heure : dorée pendant la minute qui précède l'apparition des monstres (ticks 12000 à 13188 du
     * jour, coucher du soleil par temps clair), si l'alerte est activée.
     */
    int timeColor(Minecraft mc) {
        if (settings.nightAlert && mc.level != null) {
            long t = Math.floorMod(mc.level.getDefaultClockTime(), 24000L);
            if (t >= 12000L && t < 13188L) {
                return theme().gold;
            }
        }
        return theme().textMain;
    }

    private static final int[] SUN_STOPS = {0xFFE27A, 0xF59E3B, 0xB4503C, 0x3B3A6B};
    private static final int[] MOON_STOPS = {0xE3EAFF, 0x7F93C8, 0x1D2450};

    /** Couleur d'un dégradé à plusieurs paliers ({@code t} de 0 à 1, du haut vers le bas), au format ARGB opaque. */
    static int gradientAt(int[] stops, double t) {
        double p = Math.max(0.0, Math.min(1.0, t)) * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) p);
        double f = p - i;
        int a = stops[i];
        int b = stops[i + 1];
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int gr = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    /**
     * Lignes verticales du widget, dans le panneau (coordonnées en unités de dessin, origine {@code ox, oy}) : le
     * soleil à gauche (lever à 5 h, zénith à midi, coucher à 19 h), la lune à droite (de 19 h à 5 h), chacun sur
     * un dégradé du haut (plein jour ou pleine lune) vers le bas (horizon, nuit).
     */
    void renderSunMoonLines(GuiGraphicsExtractor g, int ox, int oy, int uw, int uh, double u, double hourF,
                                    int margin, int inset) {
        int lineW = Math.max(3, (int) Math.round(4 * u));
        int top = oy + margin;
        int len = uh - 2 * margin;
        int sunX = ox + inset;
        int moonX = ox + uw - inset - lineW;
        for (int i = 0; i < len; i++) {
            double t = len <= 1 ? 0.0 : i / (double) (len - 1);
            int cut = (i == 0 || i == len - 1) ? 1 : 0;
            WmiDraw.fillRect(g, sunX + cut, top + i, sunX + lineW - cut, top + i + 1, gradientAt(SUN_STOPS, t));
            WmiDraw.fillRect(g, moonX + cut, top + i, moonX + lineW - cut, top + i + 1, gradientAt(MOON_STOPS, t));
        }
        // Lever 5 h (tick 23000), coucher 19 h (tick 13000) : bornes du jour de Minecraft ; la lune prend la nuit (19 h à 5 h)
        double sunT = (hourF - 5.0) / 14.0;
        double moonT = ((hourF - 19.0 + 24.0) % 24.0) / 10.0;
        int bottom = top + len;
        int half = Math.max(3, (int) Math.round(3.5 * u));
        if (sunT >= 0.0 && sunT <= 1.0) {
            int cy = (int) Math.round(bottom - Math.sin(Math.PI * sunT) * (len - 2 * half) - half);
            drawSunIcon(g, sunX + lineW / 2, cy, half);
        }
        if (moonT >= 0.0 && moonT <= 1.0) {
            int cy = (int) Math.round(bottom - Math.sin(Math.PI * moonT) * (len - 2 * half) - half);
            drawMoonIcon(g, moonX + lineW / 2, cy, half);
        }
    }

    /** Soleil carré en pixels (style Minecraft) : cœur jaune cerclé d'ocre et quatre rayons ; {@code half} = demi-côté. */
    void drawSunIcon(GuiGraphicsExtractor g, int cx, int cy, int half) {
        int ray = Math.max(1, half / 2);
        int rw = Math.max(1, half * 2 / 3);
        WmiDraw.fillRect(g, cx - half, cy - half, cx + half, cy + half, 0xFFC98A12);
        WmiDraw.fillRect(g, cx - half + 1, cy - half + 1, cx + half - 1, cy + half - 1, 0xFFFFD23F);
        WmiDraw.fillRect(g, cx - rw / 2, cy - half - ray, cx - rw / 2 + rw, cy - half, 0xFFFFD23F);
        WmiDraw.fillRect(g, cx - rw / 2, cy + half, cx - rw / 2 + rw, cy + half + ray, 0xFFFFD23F);
        WmiDraw.fillRect(g, cx - half - ray, cy - rw / 2, cx - half, cy - rw / 2 + rw, 0xFFFFD23F);
        WmiDraw.fillRect(g, cx + half, cy - rw / 2, cx + half + ray, cy - rw / 2 + rw, 0xFFFFD23F);
    }

    /** Lune carrée en pixels : disque pâle cerclé de gris bleuté, deux cratères ; {@code half} = demi-côté. */
    void drawMoonIcon(GuiGraphicsExtractor g, int cx, int cy, int half) {
        int c = Math.max(1, half / 2);
        WmiDraw.fillRect(g, cx - half, cy - half, cx + half, cy + half, 0xFF8D97B3);
        WmiDraw.fillRect(g, cx - half + 1, cy - half + 1, cx + half - 1, cy + half - 1, 0xFFE8ECF5);
        WmiDraw.fillRect(g, cx - c, cy - c, cx, cy, 0xFFB7BFD6);
        WmiDraw.fillRect(g, cx + 1, cy + 1, cx + 1 + c, cy + 1 + c, 0xFFB7BFD6);
    }

    /** Cran d'échelle le plus proche de l'échelle réglée (indice dans {@link WmiVector#STEP_PERCENTS}). */
    int hudStep() {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < WmiVector.STEP_PERCENTS.length; i++) {
            double d = Math.abs(WmiVector.STEP_PERCENTS[i] / 100.0 - settings.hudScale);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Nom du mod d'où vient le biome ; « Minecraft Vanilla » pour les biomes du jeu de base. */
    String currentBiomeModName(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return "";
        }
        return mc.level.getBiome(mc.player.blockPosition()).unwrapKey().map(key -> {
            String ns = key.identifier().getNamespace();
            if ("minecraft".equals(ns)) {
                return WmiText.s("wmi.hud.vanilla_mod");
            }
            return WmiPlatform.modDisplayName(ns);
        }).orElse("");
    }

    /** Heure du monde (HH:MM), biome traduit, numéro du jour et mod du biome. */
    String[] environmentLines(Minecraft mc) {
        long dayTime = mc.level != null ? mc.level.getDefaultClockTime() : 0L;
        long dayCount = dayTime / 24000L + 1L;
        long ticksOfDay = (dayTime + 6000L) % 24000L;
        int hours = (int) (ticksOfDay / 1000L);
        int minutes = (int) ((ticksOfDay % 1000L) * 60L / 1000L);
        return new String[]{
                formatWorldTime(hours, minutes),
                currentBiomeName(mc),
                WmiText.s("wmi.hud.day", dayCount),
                currentBiomeModName(mc)
        };
    }

    /**
     * Heure affichée au format réglé dans « Paramètres » : selon la langue (clé {@code wmi.time.twelve_hour}), 24 h
     * ou 12 h avec AM/PM.
     */
    static String formatWorldTime(int hours, int minutes) {
        WmiSettings.TimeFormat fmt = WmiClient.overlay().settings().timeFormat;
        boolean twelve = fmt == WmiSettings.TimeFormat.LANGUAGE ? "true".equals(WmiText.s("wmi.time.twelve_hour"))
                : fmt == WmiSettings.TimeFormat.H12;
        if (!twelve) {
            // Les traductions de Minecraft ignorent les drapeaux de largeur (%02d) : zéros complétés ici
            return WmiText.s("wmi.time.format24", String.format("%02d", hours), String.format("%02d", minutes));
        }
        int h12 = hours % 12 == 0 ? 12 : hours % 12;
        return WmiText.s("wmi.time.format12", h12, String.format("%02d", minutes), WmiText.s(hours < 12 ? "wmi.time.am" : "wmi.time.pm"));
    }

    String currentBiomeName(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return "";
        }
        return mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
                .map(key -> Component.translatable(key.identifier().toLanguageKey("biome")).getString())
                .orElse("?");
    }

    boolean overHudGrip(double mx, double my) {
        GuiLayout.Rect b = bounds;
        return b != null && b.w() > 0 && mx >= b.right() - HUD_GRIP && mx < b.right()
                && my >= b.bottom() - HUD_GRIP && my < b.bottom();
    }

    boolean overHud(double mx, double my) {
        return settings.showCenterLeftHud && bounds != null && bounds.contains(mx, my);
    }

    /** Poignée du coin (échelle), double-clic (retour au placement automatique), glissement (position). */
    boolean mousePressed(double mx, double my, boolean doubleClick) {
        if (!overHud(mx, my)) {
            return false;
        }
        if (doubleClick) {
            settings.hudScale = 1.0;
            settings.hudPosXRatio = -1.0;
            settings.hudPosYRatio = -1.0;
            settings.save();
        } else if (overHudGrip(mx, my)) {
            hudResizing = true;
            hudResizeStartMouseX = (int) mx;
            hudResizeStartScale = settings.hudScale;
            hudResizeStartW = bounds.w();
        } else {
            hudMoving = true;
            hudGrabDx = (int) mx - bounds.x();
            hudGrabDy = (int) my - bounds.y();
        }
        return true;
    }

    /** Fin du glissement ou du redimensionnement : échelle ramenée au cran le plus proche, enregistrée. */
    boolean mouseReleased() {
        if (!hudMoving && !hudResizing) {
            return false;
        }
        settings.hudScale = WmiVector.STEP_PERCENTS[hudStep()] / 100.0;
        hudMoving = false;
        hudResizing = false;
        settings.save();
        return true;
    }
}
