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

/** Outils de l'inventaire : boutons de tri, cases verrouillées, surbrillance des cases trouvées par la recherche. */
final class WmiInventoryTools {

    private static final int HIGHLIGHT_YELLOW = 0x88FFB703;
    private static final int DIM_MASK = 0xAA000000;
    private static final int LOCK_BADGE = 0xCCF77F00;

    private final WmiOverlay o;
    private final WmiSettings settings;

    /** Cases verrouillées de l'inventaire principal (Alt + clic), exclues du tri. */
    final Set<Integer> lockedPlayerSlots = new LinkedHashSet<>();

    WmiInventoryTools(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
    }

    void renderInventorySortHeader(
            AbstractContainerScreen<?> screen,
            GuiGraphicsExtractor g,
            Font font,
            GuiLayout.Resolver lay,
            int guiLeft,
            int guiTop,
            int guiW,
            int mouseX,
            int mouseY
    ) {
        if (!WmiNetwork.serverHasWmi()) {
            return; // le tri se fait sur le serveur : boutons masqués sur un serveur sans WMI
        }
        boolean chest = hasExternalContainerSlots(screen);
        GuiLayout.Rect r = lay.place("sortBar", chest ? 126 : 82, 13);
        if (r == null) {
            return;
        }
        int barY = r.y();
        int rightEdge = r.right();

        String orderShort = switch (settings.sortOrder) {
            case MOD_CATEGORY_NAME -> WmiText.s("wmi.sort.short_category");
            case NAME_ONLY -> WmiText.s("wmi.sort.short_name");
            case QUANTITY_DESC -> WmiText.s("wmi.sort.short_quantity");
        };

        o.buttons.addFloatingButton(g, font, rightEdge - 46, barY, 46, 13, WmiText.s("wmi.sort.button"),
                WmiText.s("wmi.sort.button_tip"), mouseX, mouseY,
                false, () -> sendSort(false), null);

        o.buttons.addFloatingButton(g, font, rightEdge - 82, barY, 34, 13, orderShort,
                WmiText.s("wmi.sort.order_tip"), mouseX, mouseY,
                false, () -> {
                    InventorySorter.SortOrder[] vals = InventorySorter.SortOrder.values();
                    settings.sortOrder = vals[(settings.sortOrder.ordinal() + 1) % vals.length];
                    settings.save();
                    sendSort(false);
                }, null);

        if (chest) {
            o.buttons.addFloatingButton(g, font, rightEdge - 126, barY, 42, 13, WmiText.s("wmi.sort.chest"),
                    WmiText.s("wmi.sort.chest_tip"), mouseX, mouseY,
                    false, () -> sendSort(true), null);
        }
    }

    boolean hasExternalContainerSlots(AbstractContainerScreen<?> screen) {
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        Inventory inv = mc.player.getInventory();
        int count = 0;
        for (Slot s : screen.getMenu().slots) {
            if (s.container != inv && !(s instanceof ResultSlot) && s.mayPickup(mc.player)) {
                count++;
            }
        }
        return count > 4;
    }

    void renderSlotOverlays(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int guiLeft, int guiTop) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Inventory playerInv = mc.player.getInventory();
        RecipeIndex idx = WmiClientIndex.get();
        Set<String> matchedIds = null;
        if (o.panel.highlightChestMode && !o.panel.searchQuery.isBlank()) {
            matchedIds = new LinkedHashSet<>();
            for (CatalogItem it : idx.search(o.panel.searchQuery)) {
                matchedIds.add(it.id());
            }
        }

        int playerMainIndex = 0;
        for (Slot slot : screen.getMenu().slots) {
            int sx = guiLeft + slot.x;
            int sy = guiTop + slot.y;
            if (slot.container == playerInv) {
                int cSlot = slot.getContainerSlot();
                if (cSlot >= 9 && cSlot < 36) {
                    if (lockedPlayerSlots.contains(playerMainIndex)) {
                        g.fill(sx, sy, sx + 4, sy + 4, LOCK_BADGE);
                    }
                    playerMainIndex++;
                }
            }
            if (matchedIds != null) {
                ItemStack st = slot.getItem();
                if (!st.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                    g.fill(sx, sy, sx + 16, sy + 16, matchedIds.contains(id) ? HIGHLIGHT_YELLOW : DIM_MASK);
                } else {
                    g.fill(sx, sy, sx + 16, sy + 16, DIM_MASK);
                }
            }
        }
    }

    void sendSort(boolean container) {
        WmiNetwork.send(new WmiPayloads.SortPayload(
                container,
                new ArrayList<>(lockedPlayerSlots),
                settings.sortOrder.ordinal()
        ));
    }
}
