package waymoreitems.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fc;
import waymoreitems.WmiMod;

/**
 * Gestionnaire de flou GPU localisé pour les panneaux mats et transparents de Way More Items.
 * Capture la scène 3D à la fin du rendu du monde ({@code RenderLevelStageEvent.AfterLevel}),
 * lui applique la chaîne de flou en six passes {@code wmi:frosted_blur} dans une cible dédiée,
 * puis plaque uniquement les régions correspondant aux panneaux de WMI.
 */
public final class WmiBlurHelper {
    private static final Identifier FROSTED_BLUR_ID = Identifier.fromNamespaceAndPath(WmiMod.MODID, "frosted_blur");
    private static final Identifier VANILLA_BLUR_ID = Identifier.withDefaultNamespace("blur");
    private static final CrossFrameResourcePool POOL = new CrossFrameResourcePool(3);

    private static TextureTarget blurredTarget;
    private static boolean requestedThisFrame = false;

    private WmiBlurHelper() {
    }

    private static TextureTarget ensureTarget(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return null;
        }
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        try {
            if (blurredTarget == null) {
                blurredTarget = new TextureTarget("WMI Frosted Blur", w, h, GpuFormat.RGBA8_UNORM, null);
            } else if (blurredTarget.width != w || blurredTarget.height != h) {
                blurredTarget.resize(w, h);
            }
            return blurredTarget;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Plaque la portion correspondant au rectangle {@code (x, y, w, h)} de la scène floutée.
     */
    public static void blitBlurredRegion(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        TextureTarget target = ensureTarget(mc);
        requestedThisFrame = true;
        if (target == null) {
            return;
        }
        GpuTextureView view = target.getColorTextureView();
        if (view == null) {
            return;
        }
        int guiW = Math.max(1, g.guiWidth());
        int guiH = Math.max(1, g.guiHeight());
        // Matrice en cours (échelle et décalage, sans rotation) : bornes de l'écran en unités locales, coordonnées de
        // texture d'après la position réelle à l'écran
        Matrix3x2fc p = g.pose();
        float sx = p.m00() == 0.0F ? 1.0F : p.m00();
        float sy = p.m11() == 0.0F ? 1.0F : p.m11();
        int localW = (int) Math.floor((guiW - p.m20()) / sx);
        int localH = (int) Math.floor((guiH - p.m21()) / sy);
        int cx0 = Math.max(0, Math.min(localW, x));
        int cy0 = Math.max(0, Math.min(localH, y));
        int cx1 = Math.max(0, Math.min(localW, x + w));
        int cy1 = Math.max(0, Math.min(localH, y + h));
        if (cx1 <= cx0 || cy1 <= cy0) {
            return;
        }
        float u0 = (cx0 * sx + p.m20()) / guiW;
        float u1 = (cx1 * sx + p.m20()) / guiW;
        float v0 = 1.0F - (cy0 * sy + p.m21()) / guiH;
        float v1 = 1.0F - (cy1 * sy + p.m21()) / guiH;
        try {
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            g.blit(view, sampler, cx0, cy0, cx1, cy1, u0, u1, v0, v1);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Variante vectorielle : la scène floutée est découpée à la forme d'un rectangle arrondi de rayon {@code radius}.
     */
    public static void blitBlurredRegion(GuiGraphicsExtractor g, int x, int y, int w, int h, float radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        TextureTarget target = ensureTarget(mc);
        requestedThisFrame = true;
        if (target == null) {
            return;
        }
        GpuTextureView view = target.getColorTextureView();
        if (view == null) {
            return;
        }
        try {
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            WmiVector.texturedScreen(g, view, sampler, x, y, w, h, radius);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Exécuté à la fin du rendu du monde 3D ({@code RenderLevelStageEvent.AfterLevel}) :
     * copie la scène 3D dans {@code blurredTarget} et exécute les 6 passes de flou GPU.
     */
    @SuppressWarnings("deprecation")
    public static void onAfterLevel() {
        if (!requestedThisFrame || blurredTarget == null) {
            POOL.endFrame();
            return;
        }
        requestedThisFrame = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) {
            POOL.endFrame();
            return;
        }
        try {
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            if (main == null
                    || main.getColorTexture() == null
                    || blurredTarget.getColorTexture() == null
                    || main.width != blurredTarget.width
                    || main.height != blurredTarget.height) {
                POOL.endFrame();
                return;
            }
            blurredTarget.copyColorFrom(main);
            PostChain chain = mc.getShaderManager().getPostChain(FROSTED_BLUR_ID, LevelTargetBundle.MAIN_TARGETS);
            if (chain == null) {
                chain = mc.getShaderManager().getPostChain(VANILLA_BLUR_ID, LevelTargetBundle.MAIN_TARGETS);
            }
            if (chain != null) {
                chain.process(blurredTarget, POOL);
            }
        } catch (Throwable ignored) {
        } finally {
            POOL.endFrame();
        }
    }
}
