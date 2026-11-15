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

/** Cahier de recettes de Minecraft : ouvert ou fermé selon le réglage, le choix du joueur étant suivi. */
final class WmiVanillaBook {

    private final WmiSettings settings;
    private Screen lastSyncedBookScreen = null;

    WmiVanillaBook(WmiSettings settings) {
        this.settings = settings;
    }

    /** Le prochain écran ouvert reprendra le réglage (changé dans « Paramètres »). */
    void reset() {
        lastSyncedBookScreen = null;
    }

    RecipeBookComponent<?> extractRecipeBookComponent(Screen screen) {
        if (!(screen instanceof AbstractRecipeBookScreen<?> rbs)) {
            return null;
        }
        for (Class<?> c = rbs.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (RecipeBookComponent.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (RecipeBookComponent<?>) f.get(rbs);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    ImageButton findVanillaRecipeBookButton(Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof ImageButton ib && ib.getWidth() == 20 && ib.getHeight() == 18) {
                return ib;
            }
        }
        return null;
    }

    boolean isVanillaBookVisible(Screen screen) {
        RecipeBookComponent<?> rbc = extractRecipeBookComponent(screen);
        return rbc != null && rbc.isVisible();
    }

    void syncVanillaRecipeBookOnOpen(AbstractContainerScreen<?> screen) {
        RecipeBookComponent<?> rbc = extractRecipeBookComponent(screen);
        if (rbc == null) {
            return;
        }
        if (lastSyncedBookScreen != screen) {
            lastSyncedBookScreen = screen;
            if (rbc.isVisible() != settings.vanillaBookEnabled) {
                ImageButton btn = findVanillaRecipeBookButton(screen);
                if (btn != null) {
                    btn.onPress(new MouseButtonEvent(btn.getX() + 2, btn.getY() + 2, new net.minecraft.client.input.MouseButtonInfo(0, 0)));
                }
            }
        } else {
            // Si le joueur a cliqué directement sur le bouton livre vert natif de l'inventaire, synchroniser son choix
            boolean currentVis = rbc.isVisible();
            if (currentVis != settings.vanillaBookEnabled) {
                settings.vanillaBookEnabled = currentVis;
                settings.save();
            }
        }
    }

    void toggleVanillaRecipeBook(AbstractContainerScreen<?> screen) {
        RecipeBookComponent<?> rbc = extractRecipeBookComponent(screen);
        if (rbc != null) {
            ImageButton btn = findVanillaRecipeBookButton(screen);
            if (btn != null) {
                btn.onPress(new MouseButtonEvent(btn.getX() + 2, btn.getY() + 2, new net.minecraft.client.input.MouseButtonInfo(0, 0)));
            } else {
                rbc.toggleVisibility();
            }
            settings.vanillaBookEnabled = rbc.isVisible();
        } else {
            settings.vanillaBookEnabled = !settings.vanillaBookEnabled;
        }
        settings.save();
    }
}
