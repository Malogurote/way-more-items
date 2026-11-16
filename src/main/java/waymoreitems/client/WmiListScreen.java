package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fenêtre de liste de WMI (biomes, enchantements), au style de « Paramètres » : panneau flouté, titre, recherche
 * toujours active, liste défilante à la molette ou par l'ascenseur, message de confirmation bref, croix de fermeture. Échap ou la croix ramène
 * à l'écran d'origine. Les sous-classes dessinent les lignes et déclarent leurs zones cliquables.
 */
abstract class WmiListScreen extends WmiScaledScreen {
    private static final int BOX_W = 300;
    private static final int SCROLL_STEP = 18;

    private final @Nullable Screen parent;
    private final List<Zone> zones = new ArrayList<>();
    private String query = "";
    private final TextFieldHelper edit = new TextFieldHelper(() -> query, v -> {
        query = v;
        scroll = 0;
    }, () -> TextFieldHelper.getClipboardContents(Minecraft.getInstance()),
            v -> TextFieldHelper.setClipboardContents(Minecraft.getInstance(), v), v -> v.length() <= 60);
    private int scroll;
    private int contentHeight;
    private int viewTop;
    private int viewBottom;

    /** Zone cliquable de l'image en cours ; {@code right} : action du clic droit, facultative. */
    record Zone(int x, int y, int w, int h, Runnable left, @Nullable Runnable right) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    WmiListScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Recherche saisie, en minuscules et sans espaces de bord. */
    String query() {
        return query.trim().toLowerCase(Locale.ROOT);
    }

    /** Texte affiché à droite du titre (nombre d'éléments...). */
    abstract String subtitle();

    /** Indication grisée du champ de recherche. */
    abstract String searchPlaceholder();

    /**
     * Dessine les lignes à partir de {@code top} (déjà décalé du défilement) dans la largeur {@code w}.
     * Les lignes hors de {@link #isVisible} n'ont pas besoin d'être dessinées. Renvoie la hauteur totale.
     */
    abstract int renderRows(GuiGraphicsExtractor g, int x, int top, int w, int mouseX, int mouseY);

    /** Vrai si la bande {@code [y, y + h)} est au moins en partie dans la zone visible de la liste. */
    boolean isVisible(int y, int h) {
        return y + h > viewTop && y < viewBottom;
    }

    /** Ajoute une zone cliquable, seulement pour sa partie visible. */
    void addZone(int x, int y, int w, int h, Runnable left, @Nullable Runnable right) {
        int y0 = Math.max(y, viewTop);
        int y1 = Math.min(y + h, viewBottom);
        if (y1 > y0) {
            zones.add(new Zone(x, y0, w, y1 - y0, left, right));
        }
    }

    /** Vrai si la souris survole la bande visible {@code (x, y, w, h)} de la liste. */
    boolean hovered(int x, int y, int w, int h, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, viewTop) && mouseY < Math.min(y + h, viewBottom);
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
        // Saisie de texte du système (SDL3) : sans startTextInput, aucun caractère n'arrive à charTyped
        Minecraft.getInstance().textInputManager().startTextInput(this);
        WmiTheme th = WmiDraw.theme();
        zones.clear();

        int boxW = Math.min(BOX_W, width - 20);
        int boxH = Math.min(340, height - 20);
        int x0 = (width - boxW) / 2;
        int y0 = (height - boxH) / 2;
        WmiDraw.drawFrostedPanel(g, x0, y0, boxW, boxH);
        renderOutside(g, x0, y0, boxW, boxH, mouseX, mouseY);
        WmiDraw.drawText(g, font, title.getString(), x0 + 10, y0 + 7, th.textMain);
        // Confirmation récente : elle prend la place du sous-titre, à droite du titre, pendant trois secondes
        boolean flashing = System.currentTimeMillis() < flashUntil;
        String sub = flashing
                ? WmiVector.trim(font, flashText, boxW - 30 - WmiVector.width(font, title.getString()))
                : subtitle();
        int closeX = x0 + boxW - 6 - 14;
        WmiDraw.drawText(g, font, sub, closeX - 6 - WmiVector.width(font, sub), y0 + 7,
                flashing ? th.accent : th.textMuted);

        int fieldX = x0 + 10;
        int fieldW = boxW - 20;
        WmiDraw.drawEditField(g, font, fieldX, y0 + 24, fieldW, 14, query, edit, true, searchPlaceholder(), th.accent);
        renderToolbar(g, fieldX, y0 + 42, fieldW, mouseX, mouseY);

        viewTop = y0 + 42 + toolbarHeight();
        viewBottom = y0 + boxH - 8;
        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        g.enableScissor(x0 + 4, viewTop, x0 + boxW - 4, viewBottom);
        contentHeight = renderRows(g, fieldX, viewTop - scroll, fieldW - 6, mouseX, mouseY);
        g.disableScissor();
        scrollbarX = -1;
        int trackH = viewBottom - viewTop;
        if (contentHeight > trackH) {
            // Ascenseur : position de la vue dans la liste ; la poignée se tire à la souris
            thumbH = Math.max(14, trackH * trackH / contentHeight);
            int thumbY = viewTop + (trackH - thumbH) * scroll / Math.max(1, contentHeight - trackH);
            scrollbarX = x0 + boxW - 9;
            boolean hot = draggingThumb || (mouseX >= scrollbarX - 2 && mouseX < scrollbarX + SCROLLBAR_W + 2
                    && mouseY >= thumbY && mouseY < thumbY + thumbH);
            WmiDraw.bar(g, scrollbarX + 1, viewTop, scrollbarX + SCROLLBAR_W - 1, viewBottom, th.innerBorder);
            WmiDraw.bar(g, scrollbarX, thumbY, scrollbarX + SCROLLBAR_W, thumbY + thumbH, hot ? th.accent : th.textMuted);
        }

        // Fermer : croix rouge en haut à droite, comme les autres fenêtres de WMI
        int closeY = y0 + 6;
        WmiDraw.drawCloseButton(g, closeX, closeY, 14, 14,
                mouseX >= closeX && mouseX < closeX + 14 && mouseY >= closeY && mouseY < closeY + 14);
        zones.add(new Zone(closeX, closeY, 14, 14, this::onClose, null));
    }

    private static final int SCROLLBAR_W = 4;
    /** Abscisse de l'ascenseur à la dernière image (-1 : liste assez courte, pas d'ascenseur). */
    private int scrollbarX = -1;
    private int thumbH;
    private boolean draggingThumb;
    /** Écart entre le haut de la poignée et la souris au moment de la saisie. */
    private int dragOffset;
    private String flashText = "";
    private long flashUntil;

    /**
     * Confirmation d'une action : message bref à droite du titre (trois secondes) et son des objectifs atteints des
     * Notes.
     */
    void flash(String text) {
        flashText = text;
        flashUntil = System.currentTimeMillis() + 3000L;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.35F);
        }
    }

    void resetScroll() {
        scroll = 0;
    }

    private void dragThumbTo(double mouseY) {
        int trackH = viewBottom - viewTop;
        int range = Math.max(1, trackH - thumbH);
        double top = mouseY - dragOffset - viewTop;
        scroll = (int) Math.round(Math.max(0, Math.min(range, top)) * (contentHeight - trackH) / range);
    }

    /** Commandes facultatives sous la recherche (boutons de tri) ; hauteur réservée par {@link #toolbarHeight}. */
    void renderToolbar(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
    }

    int toolbarHeight() {
        return 0;
    }

    /** Éléments facultatifs posés contre la fenêtre, à l'extérieur (onglets carrés latéraux). */
    void renderOutside(GuiGraphicsExtractor g, int x0, int y0, int boxW, int boxH, int mouseX, int mouseY) {
    }

    /** Zone cliquable hors de la liste (onglets latéraux), sans découpe par la zone visible. */
    void addClickZone(int x, int y, int w, int h, Runnable action) {
        zones.add(new Zone(x, y, w, h, action, null));
    }

    /** Bouton au style de « Paramètres ». */
    void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean active, int mouseX, int mouseY,
                Runnable action) {
        WmiTheme th = WmiDraw.theme();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = active ? th.accentSoft : (hover ? th.slotHover : th.slotBg);
        int brd = (active || hover) ? th.accent : th.innerBorder;
        WmiDraw.drawBox(g, x, y, w, h, bg, brd);
        String shown = WmiVector.trim(font, label, w - 6);
        WmiDraw.drawText(g, font, shown, x + (w - WmiVector.width(font, shown)) / 2, y + (h - 8) / 2,
                (active || hover) ? th.accent : th.textMain);
        zones.add(new Zone(x, y, w, h, action, null));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent rawEvent, boolean doubleClick) {
        MouseButtonEvent event = ui(rawEvent);
        if (scrollbarX >= 0 && WmiOverlayInput.isLeftClick(event.button())
                && event.x() >= scrollbarX - 3 && event.x() < scrollbarX + SCROLLBAR_W + 3
                && event.y() >= viewTop && event.y() < viewBottom) {
            int trackH = viewBottom - viewTop;
            int thumbY = viewTop + (trackH - thumbH) * scroll / Math.max(1, contentHeight - trackH);
            // Saisie de la poignée ; clic sur le rail : la poignée vient sous la souris, puis suit le glisser
            dragOffset = (event.y() >= thumbY && event.y() < thumbY + thumbH) ? (int) event.y() - thumbY : thumbH / 2;
            draggingThumb = true;
            dragThumbTo(event.y());
            return true;
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
        if (draggingThumb) {
            dragThumbTo(ui(event).y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingThumb) {
            draggingThumb = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll += scrollY < 0 ? SCROLL_STEP : -SCROLL_STEP;
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.isAllowedChatCharacter()) {
            edit.charTyped(event);
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.isEscape() || key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        // Retour arrière, Suppr, flèches, Début/Fin, sélection, Ctrl+A/C/V/X
        edit.keyPressed(event);
        return true;
    }
}
