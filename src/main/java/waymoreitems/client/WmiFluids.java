package waymoreitems.client;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import waymoreitems.core.CatalogItem;
import waymoreitems.core.IngredientRef;
import waymoreitems.core.RecipeEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fluides du jeu dans Way More Items : fiches du catalogue, pseudo-recettes de seaux, dessin d'une case de fluide
 * (texture immobile du fluide teintée) et infobulle avec la quantité. Les fluides sont désignés par les clés
 * {@link IngredientRef#fluidKey} du noyau, leurs quantités en millibuckets.
 */
final class WmiFluids {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Fluid> FLUID_BY_KEY = new HashMap<>();

    private WmiFluids() {
    }

    /** Fluide désigné par une clé {@code "fluid@..."}, ou {@code null}. */
    static @Nullable Fluid fluid(@Nullable String key) {
        if (!IngredientRef.isFluid(key)) {
            return null;
        }
        Fluid cached = FLUID_BY_KEY.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Fluid f = BuiltInRegistries.FLUID.getValue(Identifier.parse(IngredientRef.idOf(key)));
            if (f != null && f != Fluids.EMPTY) {
                FLUID_BY_KEY.put(key, f);
                return f;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not resolve a fluid key", e);
        }
        return null;
    }

    /** Vrai si le fluide est une source (les variantes « qui coule » ne sont pas listées). */
    private static boolean isListed(Fluid f) {
        try {
            return f != Fluids.EMPTY && f.isSource(f.defaultFluidState());
        } catch (Exception e) {
            LOGGER.debug("Could not read the state of a fluid", e);
            return false;
        }
    }

    /** Fiches de catalogue des fluides sources du registre, dans l'ordre du registre. */
    static List<CatalogItem> buildCatalog(Map<String, String> modNames) {
        FLUID_BY_KEY.clear();
        List<CatalogItem> list = new ArrayList<>();
        for (Holder.Reference<Fluid> ref : BuiltInRegistries.FLUID.listElements().toList()) {
            Fluid f = ref.value();
            if (!isListed(f)) {
                continue;
            }
            Identifier id = ref.key().identifier();
            String key = IngredientRef.fluidKey(id.toString());
            FLUID_BY_KEY.put(key, f);
            Set<String> tags = new LinkedHashSet<>();
            ref.tags().forEach(t -> tags.add(t.location().toString()));
            String ns = id.getNamespace();
            list.add(new CatalogItem(key, ns, modNames.getOrDefault(ns, ns), name(f), CatalogItem.FLUID_CATEGORY,
                    1, tags, ""));
        }
        return list;
    }

    /** Nom traduit d'un fluide. */
    static String name(Fluid f) {
        try {
            return f.getFluidType().getDescription().getString();
        } catch (Exception e) {
            LOGGER.debug("Could not read the name of a fluid", e);
            return BuiltInRegistries.FLUID.getKey(f).toString();
        }
    }

    /**
     * Pseudo-recettes de remplissage et de vidange de chaque seau de fluide (seau vide + 1000 mB &lt;-&gt; seau plein),
     * lues sur le seau rattaché à chaque fluide source.
     */
    static List<RecipeEntry> containerRecipes() {
        List<RecipeEntry> out = new ArrayList<>();
        String emptyId = BuiltInRegistries.ITEM.getKey(Items.BUCKET).toString();
        for (Holder.Reference<Fluid> ref : BuiltInRegistries.FLUID.listElements().toList()) {
            Fluid f = ref.value();
            if (!isListed(f)) {
                continue;
            }
            Item bucket;
            try {
                bucket = f.getBucket();
            } catch (Exception e) {
                continue;
            }
            if (!(bucket instanceof BucketItem bi) || bi.content != f) {
                continue;
            }
            String fluidId = ref.key().identifier().toString();
            String fullId = BuiltInRegistries.ITEM.getKey(bucket).toString();
            String suffix = fullId.replace(':', '/');
            int volume = IngredientRef.BUCKET_VOLUME;
            out.add(new RecipeEntry("wmi:fluid_filling/" + suffix, RecipeEntry.FLUID_FILLING_TYPE,
                    WmiText.s("wmi.fluid.filling"), List.of(emptyId),
                    List.of(new RecipeEntry.IngredientSlot(List.of(emptyId), 1),
                            RecipeEntry.IngredientSlot.fluids(List.of(fluidId), volume, "")),
                    List.of(), List.of(new RecipeEntry.ItemAmount(fullId, 1)), 0, 0, 0));
            out.add(new RecipeEntry("wmi:fluid_emptying/" + suffix, RecipeEntry.FLUID_EMPTYING_TYPE,
                    WmiText.s("wmi.fluid.emptying"), List.of(emptyId),
                    List.of(new RecipeEntry.IngredientSlot(List.of(fullId), 1)),
                    List.of(), List.of(RecipeEntry.ItemAmount.fluid(fluidId, volume),
                    new RecipeEntry.ItemAmount(emptyId, 1)), 0, 0, 0));
        }
        return out;
    }

    /** Couleur de teinte du fluide (opaque), blanc si le fluide n'est pas teinté. */
    private static int tint(Fluid f, @Nullable FluidModel model) {
        int color = -1;
        FluidTintSource source = model == null ? null : model.fluidTintSource();
        if (source != null) {
            try {
                color = source.colorAsStack(new FluidStack(f, IngredientRef.BUCKET_VOLUME));
            } catch (Exception e) {
                LOGGER.debug("Could not read the tint of a fluid", e);
            }
        }
        return (color >>> 24) == 0 ? color | 0xFF000000 : color;
    }

    /**
     * Dessine la texture immobile du fluide de {@code key} sur un carré de {@code size} unités. Rend {@code false} si
     * la clé ne désigne pas un fluide connu.
     */
    static boolean draw(GuiGraphicsExtractor g, @Nullable String key, int x, int y, int size) {
        return drawArea(g, key, x, y, size, size);
    }

    /**
     * Remplit un rectangle de {@code w} sur {@code h} unités de la texture immobile du fluide de {@code key} (jauge de
     * recette), par carreaux carrés du petit côté ; le dernier carreau de chaque rangée et colonne est ajusté au reste.
     * Rend {@code false} si la clé ne désigne pas un fluide connu.
     */
    static boolean drawArea(GuiGraphicsExtractor g, @Nullable String key, int x, int y, int w, int h) {
        Fluid f = fluid(key);
        if (f == null || w <= 0 || h <= 0) {
            return false;
        }
        try {
            FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(f.defaultFluidState());
            TextureAtlasSprite sprite = model.stillMaterial().sprite();
            int color = tint(f, model);
            int tile = Math.min(w, h);
            for (int ty = 0; ty < h; ty += tile) {
                for (int tx = 0; tx < w; tx += tile) {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x + tx, y + ty, Math.min(tile, w - tx),
                            Math.min(tile, h - ty), color);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not draw a fluid", e);
            return false;
        }
        return true;
    }

    /** Quantité abrégée pour une case : {@code 250}, {@code 1B}, {@code 1.5B}, {@code 12kB}. */
    static String compactAmount(int mb) {
        if (mb < IngredientRef.BUCKET_VOLUME) {
            return Integer.toString(mb);
        }
        if (mb >= 1_000_000) {
            return (mb / 1_000_000) + "kB";
        }
        int whole = mb / IngredientRef.BUCKET_VOLUME;
        int tenth = (mb % IngredientRef.BUCKET_VOLUME) / 100;
        return tenth == 0 || whole >= 100 ? whole + "B" : whole + "." + tenth + "B";
    }

    /** Quantité abrégée en bas à droite d'une case de 16 unités posée en {@code (x, y)}, comme le nombre d'objets. */
    static void drawAmount(GuiGraphicsExtractor g, Font font, int mb, int x, int y) {
        if (mb <= 0) {
            return;
        }
        String s = compactAmount(mb);
        int w = font.width(s);
        float scale = w > 16 ? 16.0F / w : 1.0F;
        g.pose().pushMatrix();
        g.pose().translate(x + 17, y + 17);
        g.pose().scale(scale, scale);
        g.text(font, s, -w, -font.lineHeight + 1, 0xFFFFFFFF, true);
        g.pose().popMatrix();
    }

    /** Infobulle d'un fluide : nom, quantité exacte (si {@code mb > 0}), identifiant en mode avancé, nom du mod. */
    static boolean tooltip(GuiGraphicsExtractor g, Font font, @Nullable String key, int mb, int x, int y) {
        Fluid f = fluid(key);
        if (f == null) {
            return false;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(name(f)));
        if (mb > 0) {
            lines.add(Component.literal(IngredientRef.formatAmount(key, mb)).withStyle(ChatFormatting.GRAY));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.advancedItemTooltips) {
            lines.add(Component.literal(IngredientRef.idOf(key)).withStyle(ChatFormatting.DARK_GRAY));
        }
        CatalogItem item = WmiClientIndex.peek().item(key);
        String mod = item != null ? item.modName() : IngredientRef.idOf(key).split(":", 2)[0];
        lines.add(Component.literal(mod).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        g.setComponentTooltipForNextFrame(font, lines, WmiUiScale.real(x), WmiUiScale.real(y));
        return true;
    }

    /**
     * Objet à donner pour un ingrédient : le seau plein d'un fluide (rien si le fluide n'a pas de seau), l'objet
     * lui-même sinon.
     */
    static @Nullable String giveTarget(@Nullable String key) {
        if (key == null || IngredientRef.isItem(key)) {
            return key;
        }
        Fluid f = fluid(key);
        if (f == null) {
            return null;
        }
        try {
            Item bucket = f.getBucket();
            return bucket == null || bucket == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(bucket).toString();
        } catch (Exception e) {
            LOGGER.debug("Could not read the bucket of a fluid", e);
            return null;
        }
    }

    /** Nom d'un ingrédient (objet ou fluide) pour les listes de texte (Notes, arbre). */
    static String displayName(String key) {
        CatalogItem item = WmiClientIndex.peek().item(key);
        if (item != null) {
            return item.displayName();
        }
        Fluid f = fluid(key);
        return f != null ? name(f) : key.toLowerCase(Locale.ROOT);
    }
}
