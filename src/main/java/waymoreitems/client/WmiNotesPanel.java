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
 * Notes de la surcouche : bloc de l'inventaire (en-tête, saisie, sélecteur d'objet, notes et objectifs, déplacement
 * par l'en-tête) et bloc de l'écran de jeu sous le widget du coin.
 */
final class WmiNotesPanel {

    private final WmiOverlay o;
    private final WmiSettings settings;
    private final PinnedChecklist checklist;

    /** Rectangle des Notes au dernier dessin (inventaire ou écran de jeu). */
    GuiLayout.Rect bounds;
    boolean postItCollapsed = false;
    boolean postItInputFocused = false;
    /** Vrai pendant le dessin des Notes sous le widget du coin : le bloc réduit prend toute la largeur. */
    boolean notesHudFullWidth = false;
    boolean notesMoving = false;
    int notesGrabDx;
    int notesGrabDy;
    /** Vrai quand le champ des Notes sert à chercher un objet à ajouter (bouton « + » ou menu du clic droit). */
    boolean notesItemMode = false;
    String notesPickedItem;
    int notesPickQty = 1;
    String notesMatchQuery;
    List<String> notesMatches = List.of();
    String postItDraft = "";

    /** Édition complète (curseur, sélection, copier-coller) du champ des Notes. */
    final TextFieldHelper notesEdit = new TextFieldHelper(() -> postItDraft, v -> postItDraft = v,
            () -> TextFieldHelper.getClipboardContents(Minecraft.getInstance()),
            v -> TextFieldHelper.setClipboardContents(Minecraft.getInstance(), v), v -> v.length() <= 120);

    WmiNotesPanel(WmiOverlay o) {
        this.o = o;
        this.settings = o.settings;
        this.checklist = o.checklist;
    }

    void focusNotes() {
        postItCollapsed = false;
        postItInputFocused = true;
        notesItemMode = false;
        o.panel.searchFocused = false;
        notesEdit.setCursorToEnd();
    }

    /** Fin de saisie des Notes (fenêtre de la touche N validée ou fermée) : plus aucun champ en attente. */
    public void endNotesInput() {
        postItInputFocused = false;
        notesItemMode = false;
        postItDraft = "";
        notesEdit.setCursorToStart();
        settings.save();
    }

    public int renderHudChecklist(GuiGraphicsExtractor g, Font font) {
        // Widget de l'heure du coin : les Notes passent juste en dessous ; sans lui, elles prennent sa place, à la
        // même largeur
        int cornerH = o.widget.renderCornerWidget(g, font);
        if (!settings.hudPostItPinned || (!settings.notesShowEmpty && checklist.isEmpty() && !postItInputFocused)) {
            return cornerH;
        }
        int top = cornerH > 0 ? WmiTimeWidget.HUD_CORNER_Y + cornerH + 4 : WmiTimeWidget.HUD_CORNER_Y;
        notesHudFullWidth = true;
        panelAlpha = settings.hudOpacity / 100.0;
        int notesH;
        try {
            notesH = renderPostItBox(g, font, o.widget.hudCornerX(g, WmiTimeWidget.HUD_CORNER_W), top,
                    WmiTimeWidget.HUD_CORNER_W, -1, -1, false);
        } finally {
            notesHudFullWidth = false;
            panelAlpha = 1.0;
        }
        return top - WmiTimeWidget.HUD_CORNER_Y + notesH;
    }

    /** Opacité du fond des Notes : celle du HUD pendant leur dessin en jeu, pleine dans l'inventaire. */
    private double panelAlpha = 1.0;

    /**
     * Lignes d'objectifs à dessiner : toutes dans l'inventaire ; en jeu, sans les lignes atteintes si elles sont
     * masquées.
     */
    private List<PinnedChecklist.Line> visibleLines(boolean inScreen) {
        List<PinnedChecklist.Line> all = checklist.lines(settings.notesTreeDepth);
        if (inScreen) {
            return all;
        }
        List<PinnedChecklist.Line> out = all;
        if (settings.doneBehavior == WmiSettings.DoneBehavior.HIDE) {
            out = new ArrayList<>(all.size());
            for (PinnedChecklist.Line l : all) {
                if (!l.completed()) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /** Hauteur des Notes dans l'inventaire, identique au calcul de {@link #renderPostItBox} (en-tête seul si repliées ou vides). */
    int postItBoxHeight() {
        int total = checklist.goalCount() + checklist.notes().size();
        if (postItCollapsed || (total == 0 && !postItInputFocused)) {
            return 22;
        }
        return 22 + notesBodyHeight(checklist.lines(settings.notesTreeDepth), true);
    }

    /** Pas vertical d'une note textuelle, d'une ligne d'objectif, d'un liseré ; hauteur du sélecteur d'objet. */
    private static final int NOTE_PITCH = 11;
    private static final int LINE_PITCH = 10;
    private static final int SEP_H = 5;
    private static final int PICKER_H = 30;

    /** Hauteur sous l'en-tête : saisie (et sélecteur d'objet), notes, liserés, lignes des objectifs, marge basse. */
    int notesBodyHeight(List<PinnedChecklist.Line> lines, boolean inScreen) {
        int h = 0;
        if (postItInputFocused) {
            h += 15 + (inScreen && notesItemMode ? PICKER_H : 0);
        }
        int noteCount = checklist.notes().size();
        h += noteCount * NOTE_PITCH;
        for (int i = 0; i < lines.size(); i++) {
            if (separatorBefore(lines, i, noteCount)) {
                h += SEP_H;
            }
            h += LINE_PITCH;
        }
        return h + 3;
    }

    /**
     * Liseré avant la ligne {@code i} : entre les notes textuelles et le premier objectif, avant chaque nouvel arbre,
     * et une seule fois avant le groupe des objets libres.
     */
    static boolean separatorBefore(List<PinnedChecklist.Line> lines, int i, int noteCount) {
        if (i == 0) {
            return noteCount > 0;
        }
        PinnedChecklist.Line l = lines.get(i);
        return l.depth() == 0 && (l.kind() == PinnedChecklist.LineKind.GOAL
                || lines.get(i - 1).kind() != PinnedChecklist.LineKind.ITEM);
    }

    int renderPostItBox(
            GuiGraphicsExtractor g,
            Font font,
            int x,
            int y,
            int boxW,
            int mouseX,
            int mouseY,
            boolean inScreen
    ) {
        List<PinnedChecklist.Line> lines = visibleLines(inScreen);
        List<PinnedChecklist.NoteEntry> notes = checklist.notes();
        int totalCount = checklist.goalCount() + notes.size();

        if (!inScreen && totalCount == 0 && !postItInputFocused) {
            String hint = WmiText.s("wmi.notes.hint");
            int pw = notesHudFullWidth ? boxW : WmiVector.width(font, hint) + 23;
            drawFrostedPanel(g, x, y, pw, 18, panelAlpha);
            drawSmallItem(g, notesIcon(), x + 4, y + 3, 12);
            drawText(g, font, hint, x + 17, y + 5, theme().textMain);
            return 18;
        }

        int headerH = 22;
        boolean headerOnly = postItCollapsed || (inScreen && totalCount == 0 && !postItInputFocused);
        int boxH = headerOnly ? headerH : headerH + notesBodyHeight(lines, inScreen);
        drawFrostedPanel(g, x, y, boxW, boxH, panelAlpha);
        renderNotesHeader(g, font, x, y, totalCount, mouseX, mouseY, inScreen);
        if (inScreen) {
            // « + » : ajouter un objet (seul ou avec son arbre de fabrication)
            boolean picking = postItInputFocused && notesItemMode;
            o.buttons.addSmallButton(g, font, x + boxW - 17, y + 5, 12, 12, "+", WmiText.s("wmi.notes.add_tip"), mouseX, mouseY,
                    picking, () -> {
                        if (postItInputFocused && notesItemMode) {
                            postItInputFocused = false;
                            notesItemMode = false;
                        } else {
                            openNotesPicker(null);
                        }
                    });
        }
        if (headerOnly) {
            bounds = new GuiLayout.Rect(x, y, boxW, headerH);
            return headerH;
        }

        int ry = y + headerH;

        if (postItInputFocused) {
            int fieldW = boxW - 22;
            String placeholder = notesItemMode ? WmiText.s("wmi.notes.search_placeholder") : "";
            drawEditField(g, font, x + 4, ry, fieldW, 13, postItDraft, notesEdit, true, placeholder, theme().accent);
            if (inScreen) {
                o.buttons.addSmallButton(g, font, x + 5 + fieldW, ry, 13, 13, WmiText.s("wmi.common.ok"),
                        WmiText.s(notesItemMode ? "wmi.notes.add_item_tip" : "wmi.notes.commit_tip"), mouseX, mouseY, false, this::commitPostItDraft);
            }
            ry += 15;
            if (inScreen && notesItemMode) {
                renderNotesPicker(g, font, x, ry, boxW, mouseX, mouseY);
                ry += PICKER_H;
            }
        }

        for (int i = 0; i < notes.size(); i++) {
            int idx = i;
            PinnedChecklist.NoteEntry note = notes.get(i);
            bar(g, x + 3, ry, x + boxW - 3, ry + 10, theme().cardBg);
            drawCheckbox(g, x + 5, ry + 1, note.done());
            int textCol = note.done() ? theme().green : theme().textMain;
            // Même cran (75 %) que les lignes d'objectifs
            String line = hudTrim(font, note.text(), 0, 0, boxW - 32);
            hudText(g, font, line, 0, 0, 0.75, x + 16, ry + 8, textCol);
            if (note.done() && settings.doneBehavior == WmiSettings.DoneBehavior.STRIKE) {
                bar(g, x + 16, ry + 5, x + 16 + hudWidth(font, line, 0, 0), ry + 6, theme().green);
            }
            if (inScreen) {
                o.buttons.drawn.add(new QuickButton(x + 3, ry, boxW - 18, 10, "", WmiText.s("wmi.notes.toggle_tip"),
                        () -> {
                            checklist.toggleNote(idx);
                            settings.save();
                        }, null));
                o.buttons.addSmallButton(g, font, x + boxW - 13, ry, 9, 9, "x", WmiText.s("wmi.notes.delete_tip"), mouseX, mouseY, false,
                        () -> {
                            checklist.removeNote(idx);
                            settings.save();
                        });
            }
            ry += NOTE_PITCH;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (separatorBefore(lines, i, notes.size())) {
                bar(g, x + 4, ry + 2, x + boxW - 4, ry + 3, theme().innerBorder);
                ry += SEP_H;
            }
            renderNotesLine(g, font, lines.get(i), x, ry, boxW, mouseX, mouseY, inScreen);
            ry += LINE_PITCH;
        }

        bounds = new GuiLayout.Rect(x, y, boxW, boxH);
        return boxH;
    }

    /** Icône des Notes (livre vanilla). */
    static ItemStack notesIcon;

    /** Livre des Notes, créé à la première utilisation (jamais à l'initialisation de la classe : les objets ne sont pas encore enregistrés au chargement du mod). */
    static ItemStack notesIcon() {
        if (notesIcon == null) {
            notesIcon = new ItemStack(Items.BOOK);
        }
        return notesIcon;
    }

    /**
     * En-tête des Notes : icône du livre (clic : écrire une note, comme la touche N) et titre (clic : réduire ou
     * déplier) ; bouton « HUD » placé par la mise en page, au bord gauche de l'inventaire par défaut.
     */
    void renderNotesHeader(GuiGraphicsExtractor g, Font font, int x, int y, int totalCount,
                                   int mouseX, int mouseY, boolean inScreen) {
        String title = totalCount > 0 ? WmiText.s("wmi.notes.title_count", totalCount) : WmiText.s("wmi.notes.title");
        int titleCol = totalCount > 0 && checklist.isAllCompleted() ? theme().green : theme().textMain;
        GuiLayout.Rect iconR = inScreen ? o.frame.place("notes.icon", 12, 12) : new GuiLayout.Rect(x + 4, y + 6, 12, 12);
        GuiLayout.Rect titleR = inScreen ? o.frame.place("notes.title", WmiVector.width(font, title), 9)
                : new GuiLayout.Rect(x + 17, y + 7, WmiVector.width(font, title), 9);
        if (iconR != null) {
            boolean hover = inScreen && iconR.contains(mouseX, mouseY);
            if (hover || (inScreen && postItInputFocused)) {
                drawBox(g, iconR.x() - 1, iconR.y() - 1, iconR.w() + 2, iconR.h() + 2, theme().accentSoft, theme().accent);
            }
            drawSmallItem(g, notesIcon(), iconR.x(), iconR.y(), iconR.w());
            if (inScreen) {
                o.buttons.drawn.add(new QuickButton(iconR.x(), iconR.y(), iconR.w(), iconR.h(), "",
                        WmiText.s(postItInputFocused ? "wmi.notes.close_input_tip" : "wmi.notes.write_tip"), () -> {
                            if (postItInputFocused) {
                                postItInputFocused = false;
                            } else {
                                focusNotes();
                            }
                        }, null));
            }
        }
        if (titleR != null) {
            boolean hover = inScreen && titleR.contains(mouseX, mouseY);
            drawText(g, font, title, titleR.x(), titleR.y(), hover ? theme().accent : titleCol);
            if (inScreen) {
                o.buttons.drawn.add(new QuickButton(titleR.x(), titleR.y() - 2, titleR.w(), titleR.h() + 4, "",
                        WmiText.s(postItCollapsed ? "wmi.notes.expand_tip" : "wmi.notes.collapse_tip"), () -> {
                            postItCollapsed = !postItCollapsed;
                            if (postItCollapsed) {
                                postItInputFocused = false;
                            }
                        }, null));
            }
        }
        // Plus de bouton « HUD » dans l'inventaire : le réglage est dans l'onglet HUD de « Paramètres »
    }

    /**
     * Ligne d'objectif, discrète : icône de 8 unités et texte au cran 75 %, indentée selon la profondeur. Objet
     * principal suivi de « ×quantité », ingrédients et objets libres de « possédé/voulu », en vert une fois réunis.
     * Dans l'inventaire : clic sur le signe ou l'icône d'une branche dépliable pour la plier, « x » pour retirer
     * un objectif ; le nom de l'objet répond comme le catalogue (touches des recettes et usages, clics, menu).
     */
    void renderNotesLine(GuiGraphicsExtractor g, Font font, PinnedChecklist.Line l, int x, int ry, int boxW,
                                 int mouseX, int mouseY, boolean inScreen) {
        ItemStack st = WmiClientIndex.stackFor(l.itemId());
        int ix = x + 5 + l.depth() * 8;
        int baseline = ry + 8;
        boolean done = l.completed();
        if (l.expandable()) {
            hudText(g, font, l.expanded() ? "-" : "+", 0, 0, 0.75, ix - 6, baseline, theme().textMuted);
        }
        if (st.isEmpty()) {
            WmiFluids.draw(g, l.itemId(), ix, ry + 1, 8);
        }
        if (!st.isEmpty()) {
            drawSmallItem(g, st, ix, ry + 1, 8);
        }
        boolean removable = inScreen && l.depth() == 0;
        int right = x + boxW - (removable ? 16 : 5);
        String count;
        if (waymoreitems.core.IngredientRef.isFluid(l.itemId())) {
            String req = waymoreitems.core.IngredientRef.formatAmount(l.itemId(), l.required());
            count = l.kind() == PinnedChecklist.LineKind.GOAL ? "×" + req : WmiFluids.compactAmount(l.current()) + "/" + req;
        } else {
            count = l.kind() == PinnedChecklist.LineKind.GOAL ? "×" + l.required() : l.current() + "/" + l.required();
        }
        int countW = hudWidth(font, count, 0, 0);
        int nameX = ix + 10;
        String name = !st.isEmpty() ? WmiNames.of(st) : WmiFluids.displayName(l.itemId());
        name = hudTrim(font, name, 0, 0, Math.max(10, right - countW - 4 - nameX));
        hudText(g, font, name, 0, 0, 0.75, nameX, baseline, done ? theme().green : theme().textMain);
        hudText(g, font, count, 0, 0, 0.75, right - countW, baseline, done ? theme().green : theme().textMuted);
        if (done && settings.doneBehavior == WmiSettings.DoneBehavior.STRIKE) {
            bar(g, nameX, baseline - 3, nameX + hudWidth(font, name, 0, 0), baseline - 2, theme().green);
        }
        if (!inScreen) {
            return;
        }
        // Le nom se comporte comme une case du catalogue (recettes, usages, menu, infobulle) : le signe et
        // l'icône d'une branche dépliable restent réservés au pliage
        int itemZoneX = l.expandable() ? nameX : ix;
        if (!st.isEmpty() && mouseX >= itemZoneX && mouseX < right && mouseY >= ry && mouseY < ry + LINE_PITCH) {
            o.panel.hoveredOverlayItemId = l.itemId();
        }
        if (l.expandable()) {
            String key = l.key();
            o.buttons.drawn.add(new QuickButton(x + 3, ry, nameX - x - 3, LINE_PITCH, "",
                    WmiText.s(l.expanded() ? "wmi.notes.branch_collapse_tip" : "wmi.notes.branch_expand_tip"),
                    () -> checklist.toggleExpanded(key), null));
        }
        if (removable) {
            int goalIndex = l.goalIndex();
            o.buttons.addSmallButton(g, font, x + boxW - 13, ry, 9, 9, "x", WmiText.s("wmi.notes.remove_tip"), mouseX, mouseY, false,
                    () -> {
                        checklist.removeGoal(goalIndex);
                        settings.save();
                    });
        }
    }

    /** Ouvre le sélecteur d'objet des Notes, avec {@code itemId} déjà choisi (ou rien). */
    void openNotesPicker(String itemId) {
        focusNotes();
        notesItemMode = true;
        postItDraft = "";
        notesEdit.setCursorToStart();
        notesPickedItem = itemId;
        notesPickQty = 1;
        notesMatchQuery = null;
    }

    static boolean hasRecipe(String itemId) {
        return itemId != null && !WmiClientIndex.peek().recipesForOutput(itemId).isEmpty();
    }

    /**
     * Sélecteur d'objet, sous le champ de recherche : objets trouvés (clic : choisir), puis quantité (clic gauche
     * ±1, clic droit ±16) et ajout comme objet seul ou avec son arbre de fabrication.
     */
    void renderNotesPicker(GuiGraphicsExtractor g, Font font, int x, int ry, int boxW, int mouseX, int mouseY) {
        String q = postItDraft.trim();
        if (!q.equals(notesMatchQuery)) {
            notesMatchQuery = q;
            List<String> found = new ArrayList<>();
            if (!q.isEmpty()) {
                for (CatalogItem it : WmiClientIndex.peek().search(q)) {
                    found.add(it.id());
                    if (found.size() >= 24) {
                        break;
                    }
                }
            }
            notesMatches = found;
        }
        List<String> shown = new ArrayList<>();
        if (notesPickedItem != null) {
            shown.add(notesPickedItem);
        }
        for (String id : notesMatches) {
            if (!id.equals(notesPickedItem)) {
                shown.add(id);
            }
        }
        int slots = Math.max(1, (boxW - 8) / 13);
        for (int i = 0; i < shown.size() && i < slots; i++) {
            String id = shown.get(i);
            int sx = x + 4 + i * 13;
            boolean hover = mouseX >= sx && mouseX < sx + 12 && mouseY >= ry && mouseY < ry + 12;
            if (id.equals(notesPickedItem) || hover) {
                drawBox(g, sx - 1, ry - 1, 14, 14, theme().accentSoft, theme().accent);
            }
            ItemStack st = WmiClientIndex.stackFor(id);
            if (!st.isEmpty()) {
                drawSmallItem(g, st, sx, ry, 12);
            } else {
                WmiFluids.draw(g, id, sx, ry, 12);
            }
            o.buttons.drawn.add(new QuickButton(sx, ry, 12, 12, "", !st.isEmpty() ? WmiNames.of(st) : WmiFluids.displayName(id),
                    () -> notesPickedItem = id, null));
        }

        int ay = ry + 15;
        o.buttons.addSmallButton(g, font, x + 4, ay, 11, 12, "-", WmiText.s("wmi.notes.qty_tip"), mouseX, mouseY, false,
                () -> notesPickQty = Math.max(1, notesPickQty - 1), () -> notesPickQty = Math.max(1, notesPickQty - 16));
        String qty = "×" + notesPickQty;
        int qtyW = Math.max(22, WmiVector.width(font, qty) + 4);
        drawText(g, font, qty, x + 16 + (qtyW - WmiVector.width(font, qty)) / 2, ay + 2, theme().textMain);
        o.buttons.addSmallButton(g, font, x + 17 + qtyW, ay, 11, 12, "+", WmiText.s("wmi.notes.qty_tip"), mouseX, mouseY, false,
                () -> notesPickQty = Math.min(9999, notesPickQty + 1), () -> notesPickQty = Math.min(9999, notesPickQty + 16));
        String target = notesPickedItem != null ? notesPickedItem : (notesMatches.isEmpty() ? null : notesMatches.get(0));
        String treeLabel = WmiText.s("wmi.notes.add_tree");
        String itemLabel = WmiText.s("wmi.notes.add_item");
        int treeW = WmiVector.width(font, treeLabel) + 8;
        int itemW = WmiVector.width(font, itemLabel) + 8;
        int treeX = x + boxW - 4 - treeW;
        int itemX = treeX - 2 - itemW;
        if (target != null) {
            o.buttons.addSmallButton(g, font, itemX, ay, itemW, 12, itemLabel, WmiText.s("wmi.notes.add_item_tip"), mouseX, mouseY, false,
                    () -> pinFromPicker(false));
        }
        if (target != null && hasRecipe(target)) {
            o.buttons.addSmallButton(g, font, treeX, ay, treeW, 12, treeLabel, WmiText.s("wmi.notes.add_tree_tip"), mouseX, mouseY, false,
                    () -> pinFromPicker(true));
        }
    }

    /** Ajoute l'objet choisi (ou le premier trouvé) aux Notes, seul ou avec son arbre, puis ferme le sélecteur. */
    void pinFromPicker(boolean tree) {
        String id = notesPickedItem != null ? notesPickedItem : (notesMatches.isEmpty() ? null : notesMatches.get(0));
        if (id == null) {
            return;
        }
        if (tree && hasRecipe(id)) {
            checklist.useIndex(WmiClientIndex.peek());
            checklist.pinCraft(id, notesPickQty, Map.of(), null);
        } else {
            checklist.pinOrIncrementItem(id, notesPickQty);
        }
        notesPickedItem = null;
        notesPickQty = 1;
        postItDraft = "";
        notesEdit.setCursorToStart();
        postItInputFocused = false;
        notesItemMode = false;
        settings.save();
    }

    void commitPostItDraft() {
        if (notesItemMode) {
            pinFromPicker(false);
            return;
        }
        if (!postItDraft.isBlank()) {
            checklist.addNote(postItDraft);
            postItDraft = "";
            notesEdit.setCursorToStart();
            settings.save();
        }
        postItInputFocused = false;
    }

    /**
     * Notes dans l'inventaire, à droite de la barre verticale ou sous la barre horizontale ; hauteur calculée
     * d'avance (un ancrage par le bas en a besoin), puis confirmée par le dessin.
     */
    void renderInScreen(GuiGraphicsExtractor g, Font font, GuiLayout.Resolver lay, int mouseX, int mouseY, int screenW, int screenH) {
        GuiLayout.Rect nr = lay.place("notes", 0, postItBoxHeight());
        if (nr != null) {
            // Position libre (glisser l'en-tête, comme le widget de l'heure), en proportion de l'écran ; sinon la mise en page
            int nx = nr.x();
            int ny = nr.y();
            int autoX = nx;
            int autoY = ny;
            if (notesMoving) {
                nx = mouseX - notesGrabDx;
                ny = mouseY - notesGrabDy;
            } else if (settings.notesPosXRatio >= 0 && settings.notesPosYRatio >= 0) {
                nx = (int) Math.round(settings.notesPosXRatio * screenW);
                ny = (int) Math.round(settings.notesPosYRatio * screenH);
            }
            // Un simple clic sans déplacement ne fige pas la position automatique
            if ((notesMoving && (nx != autoX || ny != autoY)) || (!notesMoving && settings.notesPosXRatio >= 0)) {
                nx = Math.max(0, Math.min(nx, screenW - nr.w()));
                ny = Math.max(0, Math.min(ny, screenH - 22));
                settings.notesPosXRatio = (double) nx / Math.max(1, screenW);
                settings.notesPosYRatio = (double) ny / Math.max(1, screenH);
            }
            // Les sous-éléments (icône, titre) s'ancrent sur ce rectangle : le poser avant de dessiner
            lay.set("notes", new GuiLayout.Rect(nx, ny, nr.w(), nr.h()));
            int notesH = renderPostItBox(g, font, nx, ny, nr.w(), mouseX, mouseY, true);
            lay.set("notes", new GuiLayout.Rect(nx, ny, nr.w(), notesH));
        }
    }

    /** Clic sur l'en-tête (hors boutons) : début du déplacement ; double-clic : retour à la mise en page. */
    boolean mousePressed(double mx, double my, boolean doubleClick) {
        GuiLayout.Rect b = bounds;
        if (b == null || !new GuiLayout.Rect(b.x(), b.y(), b.w(), 22).contains(mx, my)) {
            return false;
        }
        if (doubleClick) {
            settings.notesPosXRatio = -1.0;
            settings.notesPosYRatio = -1.0;
            settings.save();
        } else {
            notesMoving = true;
            notesGrabDx = (int) mx - b.x();
            notesGrabDy = (int) my - b.y();
        }
        return true;
    }

    /** Fin du déplacement : position enregistrée. */
    boolean mouseReleased() {
        if (!notesMoving) {
            return false;
        }
        notesMoving = false;
        settings.save();
        return true;
    }
}
