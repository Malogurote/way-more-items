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

import static waymoreitems.client.WmiDraw.*;

/** Boutons de la surcouche : dessinés dans l'image en cours, puis testés au clic suivant. */
final class WmiButtons {

    /** Boutons de l'image en cours (vidés au début de chaque image). */
    final List<QuickButton> drawn = new ArrayList<>();

    record QuickButton(int x, int y, int w, int h, String label, String tooltip, Runnable onClick, Runnable onRightClick) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    void addFloatingButton(
            GuiGraphicsExtractor g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            String label,
            String tooltip,
            int mouseX,
            int mouseY,
            boolean active,
            Runnable onClick,
            Runnable onRightClick
    ) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = active ? theme().accentSoft : (hover ? theme().slotHover : theme().slotBg);
        int brd = (active || hover) ? theme().accent : theme().border;
        int txtCol = (active || hover) ? theme().accent : theme().textMain;
        drawBox(g, x, y, w, h, bg, brd);
        String trimmed = WmiVector.trim(font, label, w - 4);
        drawText(g, font, trimmed, x + (w - WmiVector.width(font, trimmed)) / 2, y + (h - 8) / 2, txtCol);
        drawn.add(new QuickButton(x, y, w, h, label, tooltip, onClick, onRightClick));
    }


    /** Bouton discret : fond léger, texte réduit aux trois quarts, centré. */
    void addDiscreetButton(GuiGraphicsExtractor g, Font font, GuiLayout.Rect r, String label, String tooltip,
                                   int mouseX, int mouseY, boolean active, Runnable onClick) {
        boolean hover = r.contains(mouseX, mouseY);
        int bg = hover ? theme().slotHover : theme().slotBg;
        int brd = (active || hover) ? theme().accent : theme().innerBorder;
        int txtCol = hover ? theme().accent : (active ? theme().textMain : theme().textMuted);
        drawBox(g, r.x(), r.y(), r.w(), r.h(), bg, brd);
        int tw = smallTextWidth(font, label);
        g.pose().pushMatrix();
        g.pose().translate(r.x() + (r.w() - tw) / 2.0F, r.y() + (r.h() - 6) / 2.0F);
        g.pose().scale(0.75F, 0.75F);
        drawText(g, font, label, 0, 0, txtCol);
        g.pose().popMatrix();
        drawn.add(new QuickButton(r.x(), r.y(), r.w(), r.h(), label, tooltip, onClick, null));
    }

    void addSmallButton(
            GuiGraphicsExtractor g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            String label,
            String tooltip,
            int mouseX,
            int mouseY,
            boolean active,
            Runnable onClick
    ) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        if ("x".equals(label)) {
            // Suppression ou fermeture : croix rouge discrète dessinée en aplats (WmiDraw.drawCloseButton)
            WmiDraw.drawCloseButton(g, x, y, w, h, hover);
            drawn.add(new QuickButton(x, y, w, h, label, tooltip, onClick, null));
            return;
        }
        int bg = (active || hover) ? theme().accentSoft : theme().slotBg;
        int brd = (active || hover) ? theme().accent : theme().innerBorder;
        drawBox(g, x, y, w, h, bg, brd);
        int fg = (active || hover) ? theme().accent : theme().textMain;
        if ("+".equals(label)) {
            WmiDraw.drawPlusGlyph(g, x, y, w, h, fg); // croix nette et centrée, comme le « + » des recettes
        } else {
            drawText(g, font, label, x + (w - WmiVector.width(font, label)) / 2, y + (h - 8) / 2, fg);
        }
        drawn.add(new QuickButton(x, y, w, h, label, tooltip, onClick, null));
    }

    /** Petit bouton avec une action au clic droit. */
    void addSmallButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label, String tooltip,
                                int mouseX, int mouseY, boolean active, Runnable onClick, Runnable onRightClick) {
        addSmallButton(g, font, x, y, w, h, label, tooltip, mouseX, mouseY, active, onClick);
        drawn.set(drawn.size() - 1, new QuickButton(x, y, w, h, label, tooltip, onClick, onRightClick));
    }
}
