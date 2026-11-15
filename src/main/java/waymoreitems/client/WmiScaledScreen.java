package waymoreitems.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Écran de WMI dessiné à la taille propre de WMI ({@link WmiUiScale}) : pendant {@link #extractUi}, la matrice porte
 * le facteur, {@code width} et {@code height} sont en unités de WMI, la souris aussi. Les gestionnaires de souris des
 * sous-classes convertissent l'événement par {@link #ui(MouseButtonEvent)} avant de le comparer aux zones relevées au
 * dessin. Le fond de l'écran (dessiné par Minecraft avant {@code extractRenderState}) et les infobulles (différées)
 * restent en unités du jeu.
 */
abstract class WmiScaledScreen extends Screen {
    /** Facteur de la dernière image : unités de WMI vers unités du jeu. */
    private float uiFactor = 1.0F;

    WmiScaledScreen(Component title) {
        super(title);
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        float f = WmiUiScale.push(g);
        uiFactor = f;
        int realW = width;
        int realH = height;
        width = WmiUiScale.guiWidth(g);
        height = WmiUiScale.guiHeight(g);
        try {
            extractUi(g, Math.round(mouseX / f), Math.round(mouseY / f), partialTick);
        } finally {
            width = realW;
            height = realH;
            WmiUiScale.pop(g);
        }
    }

    /** Dessin de l'écran, en unités de WMI ({@code width}, {@code height} et souris compris). */
    protected abstract void extractUi(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick);

    /** Événement de souris converti en unités de WMI. */
    protected MouseButtonEvent ui(MouseButtonEvent event) {
        return uiFactor == 1.0F ? event : new MouseButtonEvent(event.x() / uiFactor, event.y() / uiFactor, event.buttonInfo());
    }
}
