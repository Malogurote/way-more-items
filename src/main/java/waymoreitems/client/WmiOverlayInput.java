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

/** Saisie de la surcouche : souris (clics, relâchements, molette), clavier et caractères, saisie de texte du système. */
final class WmiOverlayInput {

    private final WmiOverlay o;
    private final WmiSettings settings;

    /** Vrai tant que WMI a activé la saisie de texte du système pour ses propres champs. */
    private boolean ownsTextInput = false;
    private boolean consumeNextRelease = false;

    WmiOverlayInput(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
    }

    /**
     * Active ou coupe la saisie de texte du système (SDL3) selon le focus des champs de WMI.
     * <p>En 26.3, la fenêtre ne produit d'événements de caractère (donc de {@code ScreenEvent.CharacterTyped})
     * que si un propriétaire a appelé {@code TextInputManager.startTextInput} ; les {@code EditBox} de Minecraft
     * le font en prenant le focus ({@code Minecraft.onTextInputFocusChange}). Les champs de WMI, dessinés à la
     * main, doivent le faire eux-mêmes : sans cela, seules les touches (retour arrière, flèches) arrivaient.
     * Appelé à chaque image ; {@code startTextInput} ne fait que changer de propriétaire si la saisie est déjà
     * active. À la fermeture de l'écran, {@code Gui.setScreen} coupe la saisie de toute façon.
     */
    void syncTextInput(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        boolean wanted = o.visible && (o.panel.searchFocused || o.notes.postItInputFocused);
        if (wanted) {
            if (!ownsTextInput) {
                // Un champ de l'écran (recherche du mode créatif...) perd le focus au profit de WMI
                GuiEventListener other = WmiInputGuard.capturingWidget(screen);
                if (other != null && screen.getFocused() == other) {
                    screen.setFocused(null);
                }
                ownsTextInput = true;
            }
            mc.textInputManager().startTextInput(o);
        } else if (ownsTextInput) {
            ownsTextInput = false;
            mc.textInputManager().stopTextInput(o);
            // Un champ de l'écran qui n'a pas pu lâcher le focus (setCanLoseFocus(false)) récupère la saisie
            GuiEventListener other = WmiInputGuard.capturingWidget(screen);
            if (other != null) {
                mc.textInputManager().startTextInput(other);
            }
        }
    }

    /**
     * Vrai si une saisie de texte est en cours : recherche ou Notes de WMI, ou tout champ de texte de Minecraft
     * ou d'un autre mod qui a le focus sur l'écran (recherche du mode créatif, enclume, terminal...).
     * Tant que c'est le cas, les raccourcis de WMI (O, R, U...) sont ignorés.
     */
    public boolean isTextInputActive(Screen screen) {
        return o.panel.searchFocused || o.notes.postItInputFocused || WmiInputGuard.anyWidgetCapturesInput(screen);
    }

    // ==================== GESTION DES ÉVÉNEMENTS SOURIS & CLAVIER ====================

    public static boolean isLeftClick(int btn) {
        return btn == InputConstants.MOUSE_BUTTON_LEFT || btn == 0;
    }

    public static boolean isRightClick(int btn) {
        return btn == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    public static boolean isMiddleClick(int btn) {
        return btn == InputConstants.MOUSE_BUTTON_MIDDLE;
    }

    boolean isInsideRightPanel(double mx, double my) {
        return inside(o.panel.bounds, mx, my);
    }

    boolean isInsideAnyWmiPanel(double mx, double my) {
        return isInsideRightPanel(mx, my) || inside(o.toolbar.barBounds, mx, my) || inside(o.toolbar.settingsBounds, mx, my)
                || inside(o.toolbar.viewerBounds, mx, my) || inside(o.notes.bounds, mx, my);
    }

    /** Vrai si le point est dans ce rectangle du dernier dessin (faux s'il est absent ou vide). */
    private static boolean inside(GuiLayout.Rect r, double mx, double my) {
        return r != null && r.contains(mx, my);
    }

    void deleteCarriedItemInWmi(AbstractContainerScreen<?> screen, boolean singleItem, Minecraft mc) {
        AbstractContainerMenu menu = screen.getMenu();
        ItemStack carried = menu.getCarried();
        // Serveur sans WMI : rien n'est supprimé, même côté client (sinon désynchronisation de l'inventaire)
        if (carried.isEmpty() || !WmiNetwork.send(new WmiPayloads.DeleteCarriedPayload(singleItem))) {
            return;
        }
        if (singleItem && carried.getCount() > 1) {
            carried.shrink(1);
        } else {
            menu.setCarried(ItemStack.EMPTY);
        }
        playClickSound(mc);
    }

    public boolean onMouseClicked(AbstractContainerScreen<?> screen, MouseButtonEvent event, boolean doubleClick) {
        if (!o.visible) {
            return false;
        }
        // Souris convertie en unités de WMI (taille propre de la surcouche, WmiUiScale)
        double mx = event.x() / o.lastFactor;
        double my = event.y() / o.lastFactor;
        // Menu du clic droit ouvert : une entrée choisie, ou le clic ferme le menu sans autre effet
        if (o.panel.ctxMenuItemId != null) {
            o.panel.ctxMenuItemId = null;
            for (QuickButton qb : o.panel.ctxEntries) {
                if (qb.contains(mx, my) && isLeftClick(event.button()) && qb.onClick() != null) {
                    qb.onClick().run();
                    playClickSound(Minecraft.getInstance());
                    break;
                }
            }
            o.panel.ctxEntries.clear();
            consumeNextRelease = true;
            return true;
        }
        int btn = event.button();
        Minecraft mc = Minecraft.getInstance();

        // 0. Si le joueur tient un objet avec le curseur et clique dans le panneau WMI de droite :
        //    l'objet disparaît immédiatement au lieu d'être jeté au sol !
        if (!screen.getMenu().getCarried().isEmpty() && isInsideRightPanel(mx, my)) {
            if (isLeftClick(btn) || isRightClick(btn)) {
                deleteCarriedItemInWmi(screen, isRightClick(btn), mc);
                consumeNextRelease = true;
                return true;
            }
        }

        // 1. Poignée gauche de redimensionnement du panneau de droite
        GuiLayout.Rect rp = o.panel.bounds;
        if (isLeftClick(btn) && rp != null && rp.w() > 0
                && mx >= rp.x() - 3 && mx <= rp.x() + 4
                && my >= rp.y() && my < rp.bottom()) {
            o.panel.resizingRightPanel = true;
            consumeNextRelease = true;
            playClickSound(mc);
            return true;
        }

        // 1 bis. Widget de l'heure : poignée du coin (échelle), double-clic (retour au placement automatique), glissement (position)
        if (isLeftClick(btn) && o.widget.mousePressed(mx, my, doubleClick)) {
            consumeNextRelease = true;
            playClickSound(mc);
            return true;
        }

        // 2. Clic sur un bouton actif de l'interface WMI
        for (QuickButton qb : o.buttons.drawn) {
            if (qb.contains(mx, my)) {
                if (isRightClick(btn) && qb.onRightClick() != null) {
                    qb.onRightClick().run();
                    playClickSound(mc);
                    consumeNextRelease = true;
                    return true;
                }
                if (isLeftClick(btn) && qb.onClick() != null) {
                    qb.onClick().run();
                    playClickSound(mc);
                    consumeNextRelease = true;
                    return true;
                }
            }
        }

        // 2 bis. Notes : glisser l'en-tête (hors boutons) pour les déplacer, double-clic pour la place automatique
        if (isLeftClick(btn) && o.notes.mousePressed(mx, my, doubleClick)) {
            consumeNextRelease = true;
            return true;
        }

        o.panel.searchFocused = false;
        o.notes.postItInputFocused = false;

        // 3. Interactions sur les cases de l'inventaire ou du conteneur
        Slot hovered = screen.getHoveredSlot();
        if (hovered != null && mc.player != null) {
            boolean isPlayerSlot = (hovered.container == mc.player.getInventory());

            // Alt + Clic gauche : verrouiller / déverrouiller une case de l'inventaire principal
            if (isLeftClick(btn) && event.hasAltDown() && isPlayerSlot) {
                int cSlot = hovered.getContainerSlot();
                if (cSlot >= 9 && cSlot < 36) {
                    int idx = cSlot - 9;
                    if (!o.inventory.lockedPlayerSlots.remove(idx)) {
                        o.inventory.lockedPlayerSlots.add(idx);
                    }
                    playClickSound(mc);
                    consumeNextRelease = true;
                    return true;
                }
            }

            // Clic molette (bouton 2) sur une case d'inventaire ou de coffre (hors mode créatif) : trie en 1 clic
            // (serveur sans WMI : le clic molette garde son comportement vanilla)
            if (isMiddleClick(btn) && o.panel.hoveredOverlayItemId == null && !(screen instanceof CreativeModeInventoryScreen)
                    && WmiNetwork.serverHasWmi()) {
                boolean sortExternal = !isPlayerSlot && o.inventory.hasExternalContainerSlots(screen);
                o.inventory.sendSort(sortExternal);
                playClickSound(mc);
                consumeNextRelease = true;
                return true;
            }
        }

        // 4. Clic sur un objet du catalogue WMI à droite
        if (o.panel.hoveredOverlayItemId != null) {
            boolean canGive = o.canGiveItems(mc);
            consumeNextRelease = true;
            playClickSound(mc);

            // Clic droit simple : menu (ajouter aux Notes, usages, donner 1 en mode don direct)
            if (isRightClick(btn) && !event.hasControlDown() && !event.hasShiftDown()) {
                o.panel.ctxMenuItemId = o.panel.hoveredOverlayItemId;
                o.panel.ctxMenuScreen = screen;
                o.panel.ctxMenuGive = canGive && settings.directGiveMode;
                o.panel.ctxMenuX = (int) mx;
                o.panel.ctxMenuY = (int) my;
                return true;
            }

            if (canGive && (settings.directGiveMode || event.hasControlDown() || event.hasShiftDown() || isMiddleClick(btn))) {
                if (settings.directGiveMode) {
                    int count = isRightClick(btn) ? 1 : 64;
                    o.giveItemToPlayer(o.panel.hoveredOverlayItemId, count);
                    return true;
                }
                int count = (event.hasShiftDown() || isMiddleClick(btn)) ? 64 : 1;
                o.giveItemToPlayer(o.panel.hoveredOverlayItemId, count);
                return true;
            }

            if (isLeftClick(btn)) {
                o.openModal(screen, WmiRecipeScreen.ViewMode.RECIPES, o.panel.hoveredOverlayItemId);
                return true;
            } else if (isRightClick(btn)) {
                o.openModal(screen, WmiRecipeScreen.ViewMode.USES, o.panel.hoveredOverlayItemId);
                return true;
            }
        }

        // 5. Tout clic à l'intérieur d'un panneau WMI est absorbé pour ne jamais jeter un objet au sol
        if (isInsideAnyWmiPanel(mx, my)) {
            consumeNextRelease = true;
            return true;
        }

        return false;
    }

    public boolean onMouseReleased(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (o.panel.resizingRightPanel) {
            o.panel.resizingRightPanel = false;
            settings.save();
            consumeNextRelease = false;
            return true;
        }
        if (o.notes.mouseReleased() || o.widget.mouseReleased()) {
            consumeNextRelease = false;
            return true;
        }
        double mx = event.x() / o.lastFactor;
        double my = event.y() / o.lastFactor;
        int btn = event.button();
        // Si le joueur relâche un objet glissé depuis l'inventaire dans le panneau de droite WMI : suppression directe !
        if (o.visible && !screen.getMenu().getCarried().isEmpty() && isInsideRightPanel(mx, my)) {
            if (isLeftClick(btn) || isRightClick(btn)) {
                deleteCarriedItemInWmi(screen, isRightClick(btn), Minecraft.getInstance());
                consumeNextRelease = false;
                return true;
            }
        }
        if (consumeNextRelease) {
            consumeNextRelease = false;
            return true;
        }
        return o.visible && isInsideAnyWmiPanel(mx, my);
    }

    void playClickSound(Minecraft mc) {
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.25F, 1.0F);
        }
    }

    public boolean onMouseScrolled(AbstractContainerScreen<?> screen, double rawMouseX, double rawMouseY, double scrollDeltaY) {
        if (!o.visible) {
            return false;
        }
        // Souris convertie en unités de WMI (WmiUiScale)
        double mouseX = rawMouseX / o.lastFactor;
        double mouseY = rawMouseY / o.lastFactor;
        if (isInsideRightPanel(mouseX, mouseY)) {
            int delta = scrollDeltaY < 0 ? 1 : -1;
            GuiLayout.Rect tabs = o.panel.tabRow;
            if (tabs != null && tabs.h() > 0 && mouseY < tabs.bottom() && mouseY >= tabs.y()) {
                o.panel.modTabScroll = Math.max(0, o.panel.modTabScroll + delta);
            } else {
                o.panel.currentPage = Math.max(0, o.panel.currentPage + delta);
            }
            return true;
        }
        return false;
    }

    public boolean onCharTyped(CharacterEvent event) {
        if (!o.visible || !event.isAllowedChatCharacter()) {
            return false;
        }
        if (o.notes.postItInputFocused) {
            o.notes.notesEdit.charTyped(event);
            return true;
        }
        if (o.panel.searchFocused) {
            o.panel.searchEdit.charTyped(event);
            o.panel.currentPage = 0;
            return true;
        }
        return false;
    }

    public boolean onKeyPressed(AbstractContainerScreen<?> screen, KeyEvent event) {
        int key = event.key();
        boolean isEsc = event.isEscape() || key == InputConstants.KEY_ESCAPE || key == 256;
        boolean isEnter = event.isConfirmation() || key == InputConstants.KEY_RETURN || key == 257 || key == 335;

        if (o.visible && o.notes.postItInputFocused) {
            if (isEsc) {
                o.notes.postItInputFocused = false;
                return true;
            }
            if (isEnter) {
                o.notes.commitPostItDraft();
                return true;
            }
            // Retour arrière, Suppr, flèches, Début/Fin, sélection, Ctrl+A/C/V/X
            o.notes.notesEdit.keyPressed(event);
            return true;
        }

        if (o.visible && o.panel.searchFocused) {
            if (isEsc || isEnter) {
                o.panel.searchFocused = false;
                return true;
            }
            o.panel.searchEdit.keyPressed(event);
            return true;
        }

        // Un champ de texte de Minecraft ou d'un autre mod a le focus : aucun raccourci de WMI
        if (WmiInputGuard.anyWidgetCapturesInput(screen)) {
            return false;
        }

        if (WmiClient.KEY_TOGGLE_OVERLAY.matches(event)) {
            o.toggleVisible();
            return true;
        }
        if (WmiClient.KEY_POSTIT.matches(event)) {
            o.notes.focusNotes();
            return true;
        }
        if (WmiClient.KEY_PALETTE.matches(event)) {
            o.openModal(screen, WmiRecipeScreen.ViewMode.COMMAND_PALETTE, "");
            return true;
        }
        if (WmiClient.KEY_SORT.matches(event)) {
            Slot under = screen.getHoveredSlot();
            Minecraft mc = Minecraft.getInstance();
            boolean sortContainer = under != null && mc.player != null && under.container != mc.player.getInventory() && o.inventory.hasExternalContainerSlots(screen);
            o.inventory.sendSort(sortContainer);
            return true;
        }

        String targetId = resolveTargetItemId(screen);
        // En mode JEI, les touches d'objet (recettes, usages, favoris) reviennent à JEI
        if (targetId != null && !settings.jeiModeActive()) {
            if (WmiClient.KEY_RECIPES.matches(event)) {
                o.openModal(screen, WmiRecipeScreen.ViewMode.RECIPES, targetId);
                return true;
            }
            if (WmiClient.KEY_USES.matches(event)) {
                o.openModal(screen, WmiRecipeScreen.ViewMode.USES, targetId);
                return true;
            }
            if (WmiClient.KEY_PIN.matches(event)) {
                if (event.hasShiftDown()) {
                    o.checklist.unpinItem(targetId);
                } else {
                    o.checklist.pinOrIncrementItem(targetId, 1);
                }
                return true;
            }
        }
        return false;
    }

    String resolveTargetItemId(AbstractContainerScreen<?> screen) {
        if (o.panel.hoveredOverlayItemId != null) {
            return o.panel.hoveredOverlayItemId;
        }
        Slot slot = screen.getHoveredSlot();
        if (slot != null && slot.hasItem()) {
            return BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
        }
        return null;
    }
}
