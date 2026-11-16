package waymoreitems.client;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import waymoreitems.core.DedicatedRecipeReader;
import waymoreitems.core.MiniJson;
import waymoreitems.core.RecipeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptateur de {@link DedicatedRecipeReader} : la recette reçue du serveur est réécrite en JSON par le codec de son
 * sérialiseur ({@link Recipe#DIRECT_CODEC}), puis lue par le noyau. Aucune classe du mod qui déclare la recette n'est
 * utilisée. Les lignes de texte des fiches sont gardées ici, par identifiant de recette, pour la fenêtre des recettes.
 */
final class WmiDedicatedRecipes {
    private static final Map<String, List<DedicatedRecipeReader.Note>> NOTES = new ConcurrentHashMap<>();
    private static final DedicatedRecipeReader.Resolver RESOLVER = new GameResolver();

    private WmiDedicatedRecipes() {
    }

    /** Oublie les lignes de texte (nouvel index). */
    static void clear() {
        NOTES.clear();
    }

    /** Lignes de texte de la recette, traduites, vide si elle n'en a pas. */
    static List<String> notes(String recipeId) {
        List<DedicatedRecipeReader.Note> notes = recipeId == null ? null : NOTES.get(recipeId);
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(notes.size());
        for (DedicatedRecipeReader.Note n : notes) {
            lines.add(WmiText.s(n.key(), n.args().toArray()));
        }
        return lines;
    }

    /** Vrai si la recette est une fiche d'information (aucun produit, mais des lignes de texte). */
    static boolean isInfo(RecipeEntry entry) {
        return entry != null && entry.outputs().isEmpty() && NOTES.containsKey(entry.id());
    }

    /** Recette lue par le noyau, ou {@code null} si son sérialiseur n'y est pas pris en charge. */
    static RecipeEntry convert(RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        Identifier serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializer == null || !DedicatedRecipeReader.handles(serializer.toString())) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return null;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
        Optional<JsonElement> json = Recipe.DIRECT_CODEC.encodeStart(ops, recipe).result();
        if (json.isEmpty() || !(MiniJson.parse(json.get().toString()) instanceof Map<?, ?> m)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) m;
        String id = holder.id().identifier().toString();
        DedicatedRecipeReader.Result result = DedicatedRecipeReader.read(id, data, RESOLVER);
        if (result == null) {
            return null;
        }
        if (!result.notes().isEmpty()) {
            NOTES.put(id, result.notes());
        }
        return result.entry();
    }

    /** Données du jeu par les registres. */
    private static final class GameResolver implements DedicatedRecipeReader.Resolver {
        @Override
        public List<String> itemsOfTag(String tagId) {
            Identifier id = Identifier.tryParse(tagId);
            if (id == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, id))) {
                addItem(out, h.value());
            }
            return out;
        }

        @Override
        public boolean itemExists(String itemId) {
            Identifier id = Identifier.tryParse(itemId);
            return id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.getValue(id) != Items.AIR;
        }

        @Override
        public String itemOfBlock(String blockId) {
            Identifier id = Identifier.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return "";
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            return block == null ? "" : itemId(block.asItem());
        }

        @Override
        public String itemOfFluid(String fluidId) {
            Fluid fluid = fluid(fluidId);
            return fluid == null ? "" : itemId(fluid.getBucket());
        }

        @Override
        public List<String> itemsOfFluidTag(String tagId) {
            Identifier id = Identifier.tryParse(tagId);
            if (id == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Holder<Fluid> h : BuiltInRegistries.FLUID.getTagOrEmpty(TagKey.create(Registries.FLUID, id))) {
                addItem(out, h.value().getBucket());
            }
            return out;
        }

        @Override
        public String fluidName(String fluidId) {
            Fluid fluid = fluid(fluidId);
            return fluid == null ? fluidId : fluid.getFluidType().getDescription().getString();
        }

        @Override
        public String text(String key) {
            return WmiText.s(key);
        }

        private static Fluid fluid(String fluidId) {
            Identifier id = Identifier.tryParse(fluidId);
            if (id == null || !BuiltInRegistries.FLUID.containsKey(id)) {
                return null;
            }
            Fluid f = BuiltInRegistries.FLUID.getValue(id);
            return f == null || f == Fluids.EMPTY ? null : f;
        }

        private static void addItem(List<String> out, Item item) {
            String id = itemId(item);
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }

        private static String itemId(Item item) {
            if (item == null || item == Items.AIR) {
                return "";
            }
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }
}
