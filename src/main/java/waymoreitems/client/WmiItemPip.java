package waymoreitems.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Objets agrandis nets dans l'interface.
 * <p>En 26.3, le moteur rastérise chaque objet d'interface une seule fois dans un atlas, en cases de
 * 16 × échelle d'interface pixels ({@code GuiRenderer.prepareItemElements}) : un agrandissement par la matrice
 * étire cette petite image. Au-delà de 16 unités, l'objet est donc rendu dans sa propre texture, à la taille
 * réelle de la case, par un rendu « image dans l'image » enregistré auprès de NeoForge
 * ({@link RegisterPictureInPictureRenderersEvent}), sur le modèle de {@code OversizedItemRenderer}.
 * <p>Coût : une texture par objet agrandi visible ; elle est réutilisée d'une image à l'autre tant que l'objet
 * et sa position ne changent pas (le pool de NeoForge rapproche les états par égalité) et n'est redessinée
 * que pour les objets animés (boussole, horloge, reflet d'enchantement).
 */
final class WmiItemPip {
    private WmiItemPip() {
    }

    static void register(RegisterPictureInPictureRenderersEvent event) {
        event.register(State.class, Renderer::new);
    }

    /**
     * Dessine {@code stack} dans un carré de {@code size} unités d'interface à partir de ({@code x}, {@code y}).
     * Jusqu'à 16 unités, voie ordinaire de l'atlas (réduction sans perte visible) ; au-delà, rendu net.
     */
    static void drawItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
        if (stack.isEmpty() || size <= 0) {
            return;
        }
        if (size <= 16) {
            float scale = size / 16.0F;
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            g.fakeItem(stack, 0, 0);
            g.pose().popMatrix();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        TrackingItemStackRenderState item = new TrackingItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(item, stack, ItemDisplayContext.GUI, mc.level, null, 0);
        if (item.isEmpty()) {
            return;
        }
        // Taille propre de WMI (WmiUiScale) : la texture est calculée d'après la taille en unités, multipliée par la
        // taille du jeu ; le carré est donc exprimé en unités du jeu (matrice sans le facteur) pour rester net
        float f = WmiUiScale.active();
        if (f != 1.0F) {
            int rx = Math.round(x * f);
            int ry = Math.round(y * f);
            int rs = Math.max(1, Math.round(size * f));
            g.submitPictureInPictureRenderState(new State(item, rx, ry, rx + rs, ry + rs,
                    new Matrix3x2f(g.pose()).scale(1.0F / f, 1.0F / f), g.peekScissorStack()));
            return;
        }
        g.submitPictureInPictureRenderState(new State(item, x, y, x + size, y + size,
                new Matrix3x2f(g.pose()), g.peekScissorStack()));
    }

    /**
     * État d'un objet agrandi. L'égalité porte sur l'identité du modèle, la position et la taille, et non sur
     * l'instance de rendu (recréée à chaque image) : le pool de NeoForge retrouve ainsi le même rendu d'une
     * image à l'autre, et la texture déjà prête est simplement recopiée.
     */
    static final class State implements PictureInPictureRenderState {
        private final TrackingItemStackRenderState item;
        private final Object modelIdentity;
        private final int x0;
        private final int y0;
        private final int x1;
        private final int y1;
        private final Matrix3x2fc pose;
        private final @Nullable ScreenRectangle scissorArea;
        private final @Nullable ScreenRectangle bounds;

        State(TrackingItemStackRenderState item, int x0, int y0, int x1, int y1, Matrix3x2fc pose,
              @Nullable ScreenRectangle scissorArea) {
            this.item = item;
            this.modelIdentity = item.getModelIdentity();
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.pose = pose;
            this.scissorArea = scissorArea;
            this.bounds = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, pose, scissorArea);
        }

        TrackingItemStackRenderState item() {
            return item;
        }

        Object modelIdentity() {
            return modelIdentity;
        }

        @Override
        public int x0() {
            return x0;
        }

        @Override
        public int x1() {
            return x1;
        }

        @Override
        public int y0() {
            return y0;
        }

        @Override
        public int y1() {
            return y1;
        }

        /** Une unité de modèle (la largeur d'un objet) occupe toute la case. */
        @Override
        public float scale() {
            return x1 - x0;
        }

        @Override
        public Matrix3x2fc pose() {
            return pose;
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return scissorArea;
        }

        @Override
        public @Nullable ScreenRectangle bounds() {
            return bounds;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof State s && x0 == s.x0 && y0 == s.y0 && x1 == s.x1 && y1 == s.y1
                    && modelIdentity.equals(s.modelIdentity) && pose.equals(s.pose)
                    && Objects.equals(scissorArea, s.scissorArea);
        }

        @Override
        public int hashCode() {
            return Objects.hash(modelIdentity, x0, y0, x1, y1);
        }
    }

    /** Rendu d'un objet dans sa texture, aligné sur celui de l'atlas (même orientation et même éclairage). */
    static final class Renderer extends PictureInPictureRenderer<State> {
        private @Nullable Object modelOnTexture;

        @Override
        public Class<State> getRenderStateClass() {
            return State.class;
        }

        @Override
        protected void renderToTexture(State state, PoseStack poseStack, SubmitNodeCollector collector) {
            poseStack.scale(1.0F, -1.0F, -1.0F);
            TrackingItemStackRenderState item = state.item();
            Minecraft.getInstance().gameRenderer.lighting()
                    .setupFor(item.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
            item.submit(poseStack, collector, 15728880, OverlayTexture.NO_OVERLAY, 0);
            modelOnTexture = state.modelIdentity();
        }

        @Override
        protected boolean textureIsReadyToBlit(State state) {
            return !state.item().isAnimated() && state.modelIdentity().equals(modelOnTexture);
        }

        /** Objet centré verticalement dans la texture. */
        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2.0F;
        }

        @Override
        protected String getTextureLabel() {
            return "wmi_scaled_item";
        }
    }
}
