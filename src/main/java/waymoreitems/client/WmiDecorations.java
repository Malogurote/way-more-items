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

/** Décorations du fichier de mise en page (texte, séparateur, cadre, objet) et contexte du relevé {@code /wmi releve}. */
final class WmiDecorations {

    private final WmiOverlay o;
    private final WmiSettings settings;

    WmiDecorations(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
    }

    /**
     * Dessine les décorations du fichier de mise en page, dans son ordre. Une décoration {@code in} un élément
     * n'apparaît que si cet élément est dessiné ; ses ancrages peuvent citer tous les éléments.
     */
    void renderDecorations(GuiGraphicsExtractor g, Font font, GuiLayout.Resolver lay, Map<String, Boolean> drawn) {
        for (Map.Entry<String, Map<String, Object>> e : WmiLayoutRuntime.layout().decorations().entrySet()) {
            Map<String, Object> d = e.getValue();
            Object in = d.get("in");
            if (in != null && !Boolean.TRUE.equals(drawn.get(String.valueOf(in)))) {
                continue;
            }
            String type = String.valueOf(d.get("type"));
            String text = d.get("text") instanceof String t ? t : "";
            String size = d.get("size") instanceof String sz ? sz : "normal";
            int cw;
            int ch;
            switch (type) {
                case "text" -> {
                    cw = "huge".equals(size) ? WmiVector.hugeWidth(font, text) : "big".equals(size) ? WmiVector.bigWidth(font, text) : WmiVector.width(font, text);
                    ch = "huge".equals(size) ? (WmiVector.enabled() ? 26 : 22) : "big".equals(size) ? 13 : 9;
                }
                case "item" -> {
                    cw = 16;
                    ch = 16;
                }
                case "separator" -> {
                    cw = 40;
                    ch = 1;
                }
                default -> {
                    cw = 60;
                    ch = 30;
                }
            }
            GuiLayout.Rect r = lay.place(e.getKey(), cw, ch);
            if (r == null) {
                continue;
            }
            switch (type) {
                case "text" -> {
                    int color = decorationColor(d.get("color"), theme().textMain);
                    String align = d.get("textAlign") instanceof String a ? a : "start";
                    int tx = "center".equals(align) ? r.x() + (r.w() - cw) / 2 : "end".equals(align) ? r.right() - cw : r.x();
                    if ("huge".equals(size)) {
                        drawHugeText(g, font, text, tx, r.y(), color);
                    } else if ("big".equals(size)) {
                        drawBigText(g, font, text, tx, r.y(), color);
                    } else {
                        drawText(g, font, WmiVector.trim(font, text, Math.max(8, r.right() - tx)), tx, r.y(), color);
                    }
                }
                case "separator" -> bar(g, r.x(), r.y(), r.right(), r.y() + Math.max(1, r.h()),
                        decorationColor(d.get("color"), theme().innerBorder));
                case "frame" -> {
                    String style = d.get("style") instanceof String st ? st : "panel";
                    if ("card".equals(style)) {
                        drawFrostedCard(g, r.x(), r.y(), r.w(), r.h());
                    } else if ("box".equals(style)) {
                        drawBox(g, r.x(), r.y(), r.w(), r.h(), decorationColor(d.get("color"), theme().slotBg), theme().innerBorder);
                    } else {
                        drawFrostedPanel(g, r.x(), r.y(), r.w(), r.h());
                    }
                }
                case "item" -> {
                    ItemStack st = d.get("item") instanceof String id ? WmiClientIndex.stackFor(id) : ItemStack.EMPTY;
                    if (!st.isEmpty()) {
                        float sc = Math.min(r.w(), r.h()) / 16.0F;
                        g.pose().pushMatrix();
                        g.pose().translate(r.x(), r.y());
                        g.pose().scale(sc, sc);
                        g.fakeItem(st, 0, 0);
                        g.pose().popMatrix();
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Couleur d'une décoration : clé de la palette du thème ou « #AARRGGBB » / « #RRGGBB ». */
    int decorationColor(Object value, int fallback) {
        if (!(value instanceof String s) || s.isEmpty()) {
            return fallback;
        }
        if (s.startsWith("#")) {
            try {
                long v = Long.parseLong(s.substring(1), 16);
                return s.length() == 7 ? (int) (0xFF000000L | v) : (int) v;
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return switch (s) {
            case "textMain" -> theme().textMain;
            case "textMuted" -> theme().textMuted;
            case "accent" -> theme().accent;
            case "accentSoft" -> theme().accentSoft;
            case "gold" -> theme().gold;
            case "green" -> theme().green;
            case "purple" -> theme().purple;
            case "border" -> theme().border;
            case "innerBorder" -> theme().innerBorder;
            case "panelBg" -> theme().panelBg;
            case "cardBg" -> theme().cardBg;
            case "slotBg" -> theme().slotBg;
            case "slotHover" -> theme().slotHover;
            default -> fallback;
        };
    }

    /** Contexte du relevé : thème et palette, états de l'interface, entrées de la barre d'outils. */
    Map<String, Object> captureContext(Minecraft mc) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("key", theme().key);
        theme.put("label", theme().label());
        theme.put("vanilla", theme().vanilla());
        theme.put("customHsv", List.of((double) settings.customHue, (double) settings.customSat, (double) settings.customVal));
        Map<String, Object> pal = new LinkedHashMap<>();
        pal.put("panelBg", WmiLayoutRuntime.hex(theme().panelBg));
        pal.put("cardBg", WmiLayoutRuntime.hex(theme().cardBg));
        pal.put("slotBg", WmiLayoutRuntime.hex(theme().slotBg));
        pal.put("slotHover", WmiLayoutRuntime.hex(theme().slotHover));
        pal.put("border", WmiLayoutRuntime.hex(theme().border));
        pal.put("innerBorder", WmiLayoutRuntime.hex(theme().innerBorder));
        pal.put("textMain", WmiLayoutRuntime.hex(theme().textMain));
        pal.put("textMuted", WmiLayoutRuntime.hex(theme().textMuted));
        pal.put("accent", WmiLayoutRuntime.hex(theme().accent));
        pal.put("accentSoft", WmiLayoutRuntime.hex(theme().accentSoft));
        pal.put("gold", WmiLayoutRuntime.hex(theme().gold));
        pal.put("green", WmiLayoutRuntime.hex(theme().green));
        theme.put("palette", pal);
        ctx.put("theme", theme);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("toolbar", settings.utilityBarPosition.name());
        st.put("jeiInstalled", WmiPlatform.jeiInstalled());
        st.put("jeiActive", settings.jeiModeActive());
        st.put("widget", settings.showCenterLeftHud);
        st.put("notesCollapsed", o.notes.postItCollapsed);
        st.put("notesCount", (double) (o.checklist.entries().size() + o.checklist.notes().size()));
        st.put("vectorUi", WmiVector.enabled());
        st.put("admin", o.hasAdminRights(mc));
        st.put("gridIconSize", (double) settings.gridIconSize);
        st.put("gridColumns", (double) settings.gridColumns);
        st.put("gridRows", (double) settings.gridRows);
        st.put("directGiveMode", settings.directGiveMode);
        ctx.put("states", st);
        List<Object> bar = new ArrayList<>();
        for (WmiToolbar.BarEntry e : o.toolbar.buildBarEntries(mc)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", e.separator() ? "" : e.label());
            m.put("separator", e.separator());
            m.put("active", e.active());
            bar.add(m);
        }
        ctx.put("toolbarEntries", bar);
        return ctx;
    }
}
