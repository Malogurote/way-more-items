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
 * Primitives de dessin de l'interface de WMI (panneaux floutés, cartes, cases, barres, textes, champs de saisie),
 * aux couleurs du thème actif. Les composants de la surcouche et les fenêtres de WMI dessinent par elles plutôt que
 * d'appeler {@code GuiGraphicsExtractor} directement : une montée de version des API de dessin se concentre ici.
 */
public final class WmiDraw {

    private static final float PANEL_RADIUS = 6.0F;
    private static final float CARD_RADIUS = 3.5F;

    private WmiDraw() {
    }

    /** Thème actif de la surcouche. */
    public static WmiTheme theme() {
        return WmiClient.overlay().theme();
    }

    // ==================== PRIMITIVES DE RENDU « FLOUTÉ TRANSPARENT ET MAT » & CASES FLOTTANTES ====================

    public static void drawFrostedPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (theme().vanilla()) {
            drawVanillaPanel(g, x, y, w, h);
            return;
        }
        if (WmiVector.enabled()) {
            // Rendu vectoriel : ombre douce, flou découpé à l'arrondi, voile mat et contour d'un pixel réel.
            float r = PANEL_RADIUS;
            WmiVector.shadow(g, x, y + 1.5F, w, h, r, 4.0F, 0x38000000);
            WmiBlurHelper.blitBlurredRegion(g, x, y, w, h, r);
            WmiVector.fill(g, x, y, w, h, r, theme().panelBg);
            WmiVector.strokePx(g, x, y, w, h, r, 1.0F, theme().border);
            return;
        }
        // 1. Flou GPU localisé uniquement sous la surface de ce panneau
        WmiBlurHelper.blitBlurredRegion(g, x, y, w, h);
        // 2. Ombre portée douce
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x26080C14);
        // 3. Voile mat teinté (Blanc, Crème, Anthracite, Bleu foncé ou Noir)
        g.fill(x, y, x + w, y + h, theme().panelBg);
        // 4. Liseré supérieur mat
        g.fill(x + 1, y + 1, x + w - 1, y + 2, theme().lightTextShadowOff ? 0x55FFFFFF : 0x18FFFFFF);
        // 5. Bordure nette
        drawBorder(g, x, y, w, h, theme().border);
    }

    public static void drawFrostedCard(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (theme().vanilla()) {
            drawBox(g, x, y, w, h, theme().cardBg, theme().innerBorder);
            return;
        }
        if (WmiVector.enabled()) {
            WmiVector.shadow(g, x, y + 1.0F, w, h, CARD_RADIUS, 2.0F, 0x22000000);
            drawBox(g, x, y, w, h, theme().cardBg, theme().innerBorder);
            return;
        }
        g.fill(x + 1, y + 1, x + w + 1, y + h + 1, 0x14080C14);
        g.fill(x, y, x + w, y + h, theme().cardBg);
        drawBorder(g, x, y, w, h, theme().innerBorder);
    }

    /** Panneau de conteneur de Minecraft : contour noir aux coins coupés, biseau clair en haut à gauche, sombre en bas à droite. */
    public static void drawVanillaPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + 1, 0xFF000000);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, 0xFF000000);
        g.fill(x, y + 1, x + 1, y + h - 1, 0xFF000000);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, theme().panelBg);
        g.fill(x + 1, y + 1, x + w - 3, y + 3, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 3, y + h - 3, 0xFFFFFFFF);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
    }

    /** Barre, trait ou aplat décoratif : pilule arrondie en rendu lissé, rectangle sinon. */
    public static void bar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int x = Math.min(x0, x1);
        int y = Math.min(y0, y1);
        int w = Math.abs(x1 - x0);
        int h = Math.abs(y1 - y0);
        if (WmiVector.enabled()) {
            WmiVector.fill(g, x, y, w, h, Math.min(CARD_RADIUS, Math.min(w, h) / 2.0F), color);
            return;
        }
        g.fill(x, y, x + w, y + h, color);
    }

    /** Fond et contour d'une case, d'un bouton ou d'un champ : arrondis en rendu vectoriel, carrés sinon. */
    public static void drawBox(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int border) {
        if (theme().vanilla()) {
            // Case creusée de Minecraft : ombre en haut et à gauche, lumière en bas et à droite.
            g.fill(x, y, x + w, y + h, bg);
            g.fill(x, y, x + w - 1, y + 1, 0xFF373737);
            g.fill(x, y, x + 1, y + h - 1, 0xFF373737);
            g.fill(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
            g.fill(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
            if (border == theme().accent || border == theme().purple) {
                drawBorder(g, x, y, w, h, border);
            }
            return;
        }
        if (WmiVector.enabled()) {
            float r = Math.min(h <= 10 ? 2.5F : CARD_RADIUS, Math.min(w, h) / 2.0F);
            WmiVector.fill(g, x, y, w, h, r, bg);
            WmiVector.strokePx(g, x, y, w, h, r, 1.0F, border);
            return;
        }
        g.fill(x, y, x + w, y + h, bg);
        drawBorder(g, x, y, w, h, border);
    }

    public static void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static void drawText(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        if (WmiVector.enabled()) {
            // Police lissée (Roboto) : pas d'ombre portée, elle épaissit les lettres fines.
            g.text(font, WmiVector.text(text), x, y, color, false);
            return;
        }
        g.text(font, text, x, y, color, !theme().lightTextShadowOff);
    }

    /** Texte très grand (heure du widget) : police lissée de taille 24, ou police d'origine à l'échelle 2,5. */
    public static void drawHugeText(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        if (WmiVector.enabled()) {
            g.text(font, WmiVector.hugeText(text), x, y, color, false);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(2.5F, 2.5F);
        g.text(font, text, 0, 0, color, !theme().lightTextShadowOff);
        g.pose().popMatrix();
    }

    /** Texte agrandi : police lissée de taille 13, ou police d'origine à l'échelle 1,5 en rendu en pixels. */
    public static void drawBigText(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        if (WmiVector.enabled()) {
            g.text(font, WmiVector.bigText(text), x, y, color, false);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.5F, 1.5F);
        g.text(font, text, 0, 0, color, !theme().lightTextShadowOff);
        g.pose().popMatrix();
    }

    /** Objet réduit à {@code size} unités (16 d'origine), placé en haut à gauche de {@code (x, y)}. */
    public static void drawSmallItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / 16.0F, size / 16.0F);
        g.fakeItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    /** Case à cocher dessinée de 8 unités (remplace les crochets « [ ] » et « [x] »). */
    public static void drawCheckbox(GuiGraphicsExtractor g, int x, int y, boolean checked) {
        drawBox(g, x, y, 8, 8, checked ? theme().accentSoft : theme().slotBg,
                checked ? theme().green : theme().innerBorder);
        if (checked) {
            bar(g, x + 2, y + 2, x + 6, y + 6, theme().green);
        }
    }

    /** Champ de saisie avec curseur et sélection (édition complète par {@link TextFieldHelper}). */
    public static void drawEditField(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String value,
                               TextFieldHelper edit, boolean focused, String placeholder, int border) {
        drawBox(g, x, y, w, h, theme().slotBg, border);
        int tx = x + 4;
        int ty = y + (h - 8) / 2;
        if (value.isEmpty() && !focused) {
            drawText(g, font, WmiVector.trim(font, placeholder, w - 6), tx, ty, theme().textMuted);
            return;
        }
        int maxW = w - 8;
        int cursor = Math.max(0, Math.min(edit.getCursorPos(), value.length()));
        int sel = Math.max(0, Math.min(edit.getSelectionPos(), value.length()));
        // Fenêtre visible : on décale le début pour garder le curseur affiché
        int start = 0;
        while (start < cursor && WmiVector.width(font, value.substring(start, cursor)) > maxW) {
            start++;
        }
        String shown = WmiVector.trim(font, value.substring(start), maxW);
        if (focused && sel != cursor) {
            int a = Math.max(start, Math.min(sel, cursor));
            int b = Math.min(start + shown.length(), Math.max(sel, cursor));
            if (b > a) {
                int sx0 = tx + WmiVector.width(font, value.substring(start, a));
                int sx1 = tx + WmiVector.width(font, value.substring(start, b));
                bar(g, sx0, ty - 1, sx1, ty + 9, theme().accentSoft);
            }
        }
        drawText(g, font, shown, tx, ty, theme().textMain);
        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cx = tx + WmiVector.width(font, value.substring(start, Math.min(cursor, start + shown.length())));
            bar(g, cx, ty - 1, cx + 1, ty + 9, theme().textMain);
        }
    }

    /** Largeur d'un texte réduit aux trois quarts (bascule discrète). */
    public static int smallTextWidth(Font font, String text) {
        return (int) Math.ceil(WmiVector.width(font, text) * 0.75F);
    }

    /** Texte tronqué avec une ellipse pour tenir dans {@code maxW} (unités de dessin). */
    public static String hudTrim(Font font, String s, int kind, int step, int maxW) {
        if (hudWidth(font, s, kind, step) <= maxW) {
            return s;
        }
        String cut = s;
        while (cut.length() > 1 && hudWidth(font, cut + "...", kind, step) > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /** Largeur d'un texte du widget ({@code kind} 0 petit, 1 grand, 2 très grand) à un cran donné (unités de dessin). */
    public static int hudWidth(Font font, String s, int kind, int step) {
        if (WmiVector.enabled()) {
            return font.width(WmiVector.stepText(s, kind, step));
        }
        return Math.round(font.width(s) * (kind == 2 ? 2.5F : kind == 1 ? 1.5F : 1F));
    }

    /**
     * Dessine un texte du widget posé sur sa ligne de base {@code baseline}. En rendu lissé, la ligne de base d'une
     * police TrueType est à {@code y + 7 + décalage vertical} (GlyphProvider.BASELINE et shift de la définition) : le
     * décalage de chaque cran est proportionnel à sa taille.
     */
    public static void hudText(GuiGraphicsExtractor g, Font font, String s, int kind, int step, double k, int x, int baseline, int color) {
        if (WmiVector.enabled()) {
            double shift = (kind == 0 ? 0.5 : 1.0) * k;
            int y = (int) Math.round(baseline - (7.0 + shift));
            g.text(font, WmiVector.stepText(s, kind, step), x, y, color, false);
            return;
        }
        int y = baseline - (int) Math.round(7 * (kind == 2 ? 2.5 : kind == 1 ? 1.5 : 1.0));
        if (kind == 2) {
            drawHugeText(g, font, s, x, y, color);
        } else if (kind == 1) {
            drawBigText(g, font, s, x, y, color);
        } else {
            drawText(g, font, s, x, y, color);
        }
    }
}
