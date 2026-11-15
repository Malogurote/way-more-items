package waymoreitems.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Taille propre de l'interface de WMI, indépendante de la taille d'interface du jeu : WMI garde l'apparence qu'il aurait
 * à la taille choisie ({@link WmiSettings#uiScale}, 3 par défaut), quelle que soit celle du jeu. Le facteur
 * {@code taille visée / taille du jeu} est posé sur la pile de matrices pendant le dessin ; dans cette portée, les
 * dimensions d'écran se lisent en unités virtuelles ({@link #guiWidth}) et {@link WmiVector#guiScale()} rend la taille
 * effective, pour que les polices et les traits restent nets. La taille visée est bornée par la plus grande que la
 * fenêtre admet (même calcul que le jeu), et 0 signifie « comme le jeu » (facteur 1).
 */
public final class WmiUiScale {
    /** Facteur actuellement posé sur la pile ({@code 1} hors d'une portée mise à l'échelle). */
    private static float active = 1.0F;

    private WmiUiScale() {
    }

    /** Pixels de hauteur de fenêtre par cran du mode automatique : 1080 p donne la taille 3. */
    static final int AUTO_PIXELS_PER_STEP = 360;

    /**
     * Taille du mode automatique proportionnel : une taille par tranche de {@link #AUTO_PIXELS_PER_STEP} pixels de
     * hauteur (720 p : 2, 1080 p : 3, 1440 p : 4, 2160 p : 6), pour que WMI garde à peu près la même part de l'écran.
     */
    static int autoScale(int framebufferHeight) {
        return Math.max(1, Math.min(WmiSettings.UI_SCALE_MAX, Math.round(framebufferHeight / (float) AUTO_PIXELS_PER_STEP)));
    }

    /** Taille d'interface visée, bornée par la fenêtre ; taille du jeu si le réglage vaut 0, automatique s'il vaut -1. */
    public static int targetScale() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return 1;
        }
        Window window = mc.getWindow();
        int game = Math.max(1, window.getGuiScale());
        int wanted = WmiClient.overlay().settings().uiScale();
        if (wanted == WmiSettings.UI_SCALE_GAME) {
            return game;
        }
        if (wanted == WmiSettings.UI_SCALE_AUTO) {
            wanted = autoScale(window.getHeight());
        }
        // Window.calculateScale(max, unicode) : la plus grande taille, au plus « max », qui laisse 320 × 240 unités
        return Math.max(1, window.calculateScale(wanted, false));
    }

    /** Facteur entre la taille visée et celle du jeu (1 si elles sont égales). */
    public static float factor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return 1.0F;
        }
        return targetScale() / (float) Math.max(1, mc.getWindow().getGuiScale());
    }

    /** Ouvre une portée mise à l'échelle (à refermer par {@link #pop}) et rend le facteur posé. */
    public static float push(GuiGraphicsExtractor g) {
        float f = factor();
        g.pose().pushMatrix();
        g.pose().scale(f, f);
        active = f;
        return f;
    }

    public static void pop(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
        active = 1.0F;
    }

    /** Facteur de la portée en cours (1 hors portée). */
    public static float active() {
        return active;
    }

    /**
     * Coordonnée de la portée en cours convertie en unités du jeu : les infobulles sont différées et dessinées hors de
     * la portée ({@code GuiGraphicsExtractor.extractDeferredElements}), elles attendent des unités du jeu.
     */
    public static int real(int v) {
        return Math.round(v * active);
    }

    /** Largeur de l'écran en unités de la portée en cours. */
    public static int guiWidth(GuiGraphicsExtractor g) {
        return Math.round(g.guiWidth() / active);
    }

    /** Hauteur de l'écran en unités de la portée en cours. */
    public static int guiHeight(GuiGraphicsExtractor g) {
        return Math.round(g.guiHeight() / active);
    }
}
