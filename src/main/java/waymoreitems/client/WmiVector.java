package waymoreitems.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;
import waymoreitems.WmiMod;

/**
 * Rendu vectoriel de l'interface de Way More Items.
 *
 * <p>Les formes sont des rectangles arrondis décrits par un champ de distance signée (SDF) :
 * le shader {@code wmi:core/sdf_rect} calcule leur couverture au pixel réel de l'écran, ce qui donne
 * des bords lissés quelle que soit l'échelle d'interface. Le shader passe par le pipeline de rendu
 * de Minecraft (compilé en SPIR-V) et fonctionne donc sous OpenGL comme sous Vulkan.</p>
 *
 * <p>Le texte utilise la police Roboto (SIL OFL 1.1), déclinée en une définition par échelle
 * d'interface ({@code wmi:roboto_x1} à {@code wmi:roboto_x6}) : le jeu échantillonne les glyphes
 * sans lissage, ils ne sont donc nets que si le suréchantillonnage égale l'échelle.</p>
 */
public final class WmiVector {

    /** Format de sommet : position, couleur, coordonnées de texture, position locale, demi-dimensions, rayon et paramètre. */
    public static final VertexFormat SDF_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV3", GpuFormat.RG32_FLOAT)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build();

    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(WmiMod.MODID, "core/sdf_rect");

    public static final RenderPipeline SDF_RECT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(WmiMod.MODID, "pipeline/sdf_rect"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withVertexBinding(0, SDF_FORMAT)
            .build();

    public static final RenderPipeline SDF_RECT_TEXTURED = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(WmiMod.MODID, "pipeline/sdf_rect_textured"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("TEXTURED")
            .withVertexBinding(0, SDF_FORMAT)
            .build();

    private static final int MAX_FONT_SCALE = 6;
    private static final Style[] TEXT_STYLES = new Style[MAX_FONT_SCALE];
    private static final Style[] BIG_TEXT_STYLES = new Style[MAX_FONT_SCALE];
    private static final Style[] HUGE_TEXT_STYLES = new Style[MAX_FONT_SCALE];

    static {
        for (int i = 0; i < MAX_FONT_SCALE; i++) {
            TEXT_STYLES[i] = Style.EMPTY.withFont(new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath(WmiMod.MODID, "roboto_x" + (i + 1))));
            BIG_TEXT_STYLES[i] = Style.EMPTY.withFont(new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath(WmiMod.MODID, "roboto_big_x" + (i + 1))));
            HUGE_TEXT_STYLES[i] = Style.EMPTY.withFont(new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath(WmiMod.MODID, "roboto_huge_x" + (i + 1))));
        }
    }

    /**
     * Crans d'échelle du widget de l'heure (en pour cent) ; 50 % (widget du coin de l'écran de jeu) et 65 % (nom du mod de ce widget) sont réservés au coin. Chaque cran a ses propres polices (taille et suréchantillonnage
     * justes pour chaque échelle d'interface) : le texte reste net à toutes les tailles, au lieu d'être agrandi par matrice.
     */
    public static final int[] STEP_PERCENTS = {75, 100, 125, 150, 200, 250, 50, 65};
    private static final Style[][][] STEP_STYLES = new Style[3][STEP_PERCENTS.length][MAX_FONT_SCALE];

    /** Texte du widget : {@code kind} 0 = petit, 1 = grand, 2 = très grand ; {@code step} = indice dans {@link #STEP_PERCENTS}. */
    public static Component stepText(String s, int kind, int step) {
        int scale = Math.min(MAX_FONT_SCALE, guiScale()) - 1;
        if (STEP_PERCENTS[step] == 100) {
            return Component.literal(s).withStyle((kind == 0 ? TEXT_STYLES : kind == 1 ? BIG_TEXT_STYLES : HUGE_TEXT_STYLES)[scale]);
        }
        Style st = STEP_STYLES[kind][step][scale];
        if (st == null) {
            String base = kind == 0 ? "roboto" : kind == 1 ? "roboto_big" : "roboto_huge";
            st = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(WmiMod.MODID,
                    base + "_s" + STEP_PERCENTS[step] + "_x" + (scale + 1))));
            STEP_STYLES[kind][step][scale] = st;
        }
        return Component.literal(s).withStyle(st);
    }

    /** Préférence du joueur : rendu vectoriel lissé ou ancien rendu en pixels. */
    private static boolean enabled = true;
    /** Le thème « Minecraft Vanilla » impose le rendu en pixels et la police d'origine. */
    private static boolean vanillaTheme = false;

    private WmiVector() {
    }

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(SDF_RECT);
        event.registerPipeline(SDF_RECT_TEXTURED);
    }

    /** Rendu lissé effectivement actif (préférence du joueur, hors thème Vanilla). */
    public static boolean enabled() {
        return enabled && !vanillaTheme;
    }

    public static boolean preferred() {
        return enabled;
    }

    public static void setVanillaTheme(boolean value) {
        vanillaTheme = value;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    // ==================== FORMES ====================

    /** Rectangle arrondi plein. */
    public static void fill(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int color) {
        submit(g, SDF_RECT, TextureSetup.noTexture(), x, y, w, h, radius, 0.0F, 1.0F, color, false);
    }

    /** Contour d'un rectangle arrondi, d'une épaisseur donnée en pixels réels de l'écran. */
    public static void strokePx(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, float widthPx, int color) {
        float width = widthPx / guiScale();
        submit(g, SDF_RECT, TextureSetup.noTexture(), x, y, w, h, radius, width, 1.0F, color, false);
    }

    /** Ombre douce sous un rectangle arrondi ; {@code softness} en unités d'interface. */
    public static void shadow(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, float softness, int color) {
        float s = Math.max(0.5F, softness);
        submit(g, SDF_RECT, TextureSetup.noTexture(), x, y, w, h, radius, -s, s + 1.0F, color, false);
    }

    /** Plaque une texture plein écran (le flou) découpée à la forme d'un rectangle arrondi. */
    public static void texturedScreen(GuiGraphicsExtractor g, GpuTextureView view, GpuSampler sampler,
                                      float x, float y, float w, float h, float radius) {
        submit(g, SDF_RECT_TEXTURED, TextureSetup.singleTexture(view, sampler), x, y, w, h, radius, 0.0F, 1.0F, 0xFFFFFFFF, true);
    }

    private static void submit(GuiGraphicsExtractor g, RenderPipeline pipeline, TextureSetup textures,
                               float x, float y, float w, float h, float radius, float param, float margin,
                               int color, boolean screenUv) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        GuiRenderState state = renderState(g);
        float guiW = Math.max(1, g.guiWidth());
        float guiH = Math.max(1, g.guiHeight());
        state.addGuiElement(new SdfRectRenderState(
                pipeline, textures, new Matrix3x2f(g.pose()),
                x, y, w, h, radius, param, margin, color, screenUv, guiW, guiH, scissor(g)));
    }

    // Le transformateur d'accès (META-INF/accesstransformer.cfg) rend public le tampon d'interface.
    private static GuiRenderState renderState(GuiGraphicsExtractor g) {
        return g.guiRenderState;
    }

    private static @Nullable ScreenRectangle scissor(GuiGraphicsExtractor g) {
        return g.peekScissorStack();
    }

    /** Pixels réels par unité de dessin : taille du jeu, multipliée par le facteur de {@link WmiUiScale} dans sa portée. */
    public static int guiScale() {
        Minecraft mc = Minecraft.getInstance();
        int game = mc == null || mc.getWindow() == null ? 1 : Math.max(1, mc.getWindow().getGuiScale());
        return Math.max(1, Math.round(game * WmiUiScale.active()));
    }

    // ==================== TEXTE ====================

    public static Style textStyle() {
        return TEXT_STYLES[Math.min(MAX_FONT_SCALE, guiScale()) - 1];
    }

    public static Component text(String s) {
        return Component.literal(s).withStyle(textStyle());
    }

    /** Texte agrandi (widget central) : police Roboto de taille 13. */
    public static Component bigText(String s) {
        return Component.literal(s).withStyle(BIG_TEXT_STYLES[Math.min(MAX_FONT_SCALE, guiScale()) - 1]);
    }

    /** Texte très grand (heure du widget central) : police Roboto de taille 24. */
    public static Component hugeText(String s) {
        return Component.literal(s).withStyle(HUGE_TEXT_STYLES[Math.min(MAX_FONT_SCALE, guiScale()) - 1]);
    }

    public static int hugeWidth(Font font, String s) {
        return enabled() ? font.width(hugeText(s)) : Math.round(font.width(s) * 2.5F);
    }

    public static int bigWidth(Font font, String s) {
        return enabled() ? font.width(bigText(s)) : Math.round(font.width(s) * 1.5F);
    }

    public static int width(Font font, String s) {
        if (s == null) {
            return 0;
        }
        return enabled() ? font.width(text(s)) : font.width(s);
    }

    public static String trim(Font font, String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        return enabled() ? font.getSplitter().plainHeadByWidth(s, maxWidth, textStyle()) : font.plainSubstrByWidth(s, maxWidth);
    }

    /** Élément d'interface : un quadrilatère dont le shader découpe le rectangle arrondi. */
    private record SdfRectRenderState(
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            Matrix3x2f pose,
            float x,
            float y,
            float w,
            float h,
            float radius,
            float param,
            float margin,
            int color,
            boolean screenUv,
            float guiW,
            float guiH,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds
    ) implements GuiElementRenderState {
        SdfRectRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose,
                           float x, float y, float w, float h, float radius, float param, float margin,
                           int color, boolean screenUv, float guiW, float guiH, @Nullable ScreenRectangle scissorArea) {
            this(pipeline, textureSetup, pose, x, y, w, h, radius, param, margin, color, screenUv, guiW, guiH,
                    scissorArea, bounds(x, y, w, h, margin, pose, scissorArea));
        }

        private static @Nullable ScreenRectangle bounds(float x, float y, float w, float h, float margin,
                                                        Matrix3x2f pose, @Nullable ScreenRectangle scissorArea) {
            int x0 = (int) Math.floor(x - margin);
            int y0 = (int) Math.floor(y - margin);
            int x1 = (int) Math.ceil(x + w + margin);
            int y1 = (int) Math.ceil(y + h + margin);
            ScreenRectangle b = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
            return scissorArea != null ? scissorArea.intersection(b) : b;
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            float cx = x + w / 2.0F;
            float cy = y + h / 2.0F;
            int halfW = Math.round(w / 2.0F * 16.0F);
            int halfH = Math.round(h / 2.0F * 16.0F);
            int r = Math.round(Math.max(0.0F, radius) * 256.0F);
            int p = Math.round(param * 256.0F);
            float x0 = x - margin;
            float y0 = y - margin;
            float x1 = x + w + margin;
            float y1 = y + h + margin;
            vertex(consumer, x0, y0, cx, cy, halfW, halfH, r, p);
            vertex(consumer, x0, y1, cx, cy, halfW, halfH, r, p);
            vertex(consumer, x1, y1, cx, cy, halfW, halfH, r, p);
            vertex(consumer, x1, y0, cx, cy, halfW, halfH, r, p);
        }

        private void vertex(VertexConsumer consumer, float vx, float vy, float cx, float cy, int halfW, int halfH, int r, int p) {
            // Coordonnées d'écran après la matrice (mise à l'échelle de WMI, décalages) : la texture plein écran reste calée
            float sx = pose.m00() * vx + pose.m10() * vy + pose.m20();
            float sy = pose.m01() * vx + pose.m11() * vy + pose.m21();
            float u = screenUv ? sx / guiW : 0.0F;
            float v = screenUv ? 1.0F - sy / guiH : 0.0F;
            consumer.addVertexWith2DPose(pose, vx, vy)
                    .setColor(color)
                    .setUv(u, v)
                    .setUv3(vx - cx, vy - cy)
                    .setUv1(halfW, halfH)
                    .setUv2(r, p);
        }
    }
}
