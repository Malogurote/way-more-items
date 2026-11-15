package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Petite fenêtre de confirmation d'une action irréversible (« Vider l'inventaire »), au style des fenêtres de WMI :
 * message, bouton d'action et « Annuler ». Échap ou « Annuler » ramène à l'écran d'origine sans rien faire.
 */
final class WmiConfirmScreen extends WmiScaledScreen {
    private static final int BOX_W = 220;

    private final @Nullable Screen parent;
    private final String message;
    private final String confirmLabel;
    private final Runnable onConfirm;
    private final List<WmiListScreen.Zone> zones = new ArrayList<>();

    WmiConfirmScreen(@Nullable Screen parent, Component title, String message, String confirmLabel, Runnable onConfirm) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
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
        WmiTheme th = WmiDraw.theme();
        zones.clear();
        List<FormattedCharSequence> lines = font.split(WmiVector.text(message), BOX_W - 20);
        int boxH = 30 + lines.size() * 11 + 32;
        int x0 = (width - BOX_W) / 2;
        int y0 = (height - boxH) / 2;
        WmiDraw.drawFrostedPanel(g, x0, y0, BOX_W, boxH);
        WmiDraw.drawText(g, font, title.getString(), x0 + 10, y0 + 7, th.textMain);
        int y = y0 + 24;
        for (FormattedCharSequence line : lines) {
            g.text(font, line, x0 + 10, y, th.textMain, !th.lightTextShadowOff);
            y += 11;
        }
        int bw = 80;
        int by = y0 + boxH - 24;
        button(g, x0 + BOX_W / 2 - bw - 4, by, bw, 16, confirmLabel, true, mouseX, mouseY, () -> {
            onClose();
            onConfirm.run();
        });
        button(g, x0 + BOX_W / 2 + 4, by, bw, 16, WmiText.s("wmi.confirm.cancel"), false, mouseX, mouseY, this::onClose);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean danger,
                        int mouseX, int mouseY, Runnable action) {
        WmiTheme th = WmiDraw.theme();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = hover ? th.slotHover : th.slotBg;
        int brd = hover ? (danger ? 0xFFE05A50 : th.accent) : th.innerBorder;
        WmiDraw.drawBox(g, x, y, w, h, bg, brd);
        String shown = WmiVector.trim(font, label, w - 6);
        int col = danger ? 0xFFE05A50 : (hover ? th.accent : th.textMain);
        WmiDraw.drawText(g, font, shown, x + (w - WmiVector.width(font, shown)) / 2, y + (h - 8) / 2, col);
        zones.add(new WmiListScreen.Zone(x, y, w, h, action, null));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent rawEvent, boolean doubleClick) {
        MouseButtonEvent event = ui(rawEvent);
        if (WmiOverlayInput.isLeftClick(event.button())) {
            for (WmiListScreen.Zone z : zones) {
                if (z.contains(event.x(), event.y())) {
                    z.left().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(rawEvent, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
