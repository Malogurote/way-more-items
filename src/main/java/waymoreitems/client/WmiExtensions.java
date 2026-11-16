package waymoreitems.client;

import com.mojang.logging.LogUtils;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import waymoreitems.api.RecipeLayoutSpec;
import waymoreitems.api.WmiApi;
import waymoreitems.api.WmiExtension;
import waymoreitems.api.WmiPlugin;
import waymoreitems.core.ApiRecipes;
import waymoreitems.core.RecipeEntry;
import waymoreitems.core.RecipeLayoutJson;
import waymoreitems.core.WmiRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Extensions des autres mods : découverte des classes {@link WmiPlugin}, lecture des mises en page JSON
 * ({@code assets/<modid>/wmi_layouts/}) et recettes fournies par l'API.
 */
public final class WmiExtensions {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loaded;
    private static volatile Map<String, List<String>> notes = Map.of();

    private WmiExtensions() {
    }

    /** Découvre et inscrit les extensions, une seule fois (au premier appel, avant la première indexation). */
    public static synchronized void loadOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        int count = 0;
        for (WmiPlatform.AnnotatedClass c : WmiPlatform.annotatedClasses(WmiPlugin.class)) {
            Object required = c.values().get("api");
            if (!(required instanceof String version) || !WmiApi.isCompatible(version)) {
                LOGGER.warn("WMI: extension {} skipped, built for API {} but this is API {}",
                        c.className(), required, WmiApi.VERSION);
                continue;
            }
            try {
                Class<?> type = Class.forName(c.className(), true, WmiExtensions.class.getClassLoader());
                if (!WmiExtension.class.isAssignableFrom(type)) {
                    LOGGER.warn("WMI: {} is annotated with @WmiPlugin but does not implement WmiExtension", c.className());
                    continue;
                }
                WmiExtension ext = (WmiExtension) type.getDeclaredConstructor().newInstance();
                ext.register(WmiRegistry.registration());
                count++;
                LOGGER.info("WMI: extension {} ({}) registered", ext.id(), c.className());
            } catch (Throwable t) {
                // Code d'un mod tiers : son échec ne doit pas empêcher WMI de démarrer
                LOGGER.error("WMI: extension {} failed to register", c.className(), t);
            }
        }
        if (count > 0) {
            LOGGER.info("WMI: {} extension(s) loaded (API {})", count, WmiApi.VERSION);
        }
    }

    /** Relit toutes les mises en page JSON des ressources ; un fichier invalide est ignoré et journalisé. */
    public static void reloadJsonLayouts(ResourceManager resources) {
        if (resources == null) {
            return;
        }
        Map<String, RecipeLayoutSpec> layouts = new HashMap<>();
        Map<Identifier, Resource> found = resources.listResources(RecipeLayoutJson.DIRECTORY,
                id -> id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> e : found.entrySet()) {
            Identifier id = e.getKey();
            try (BufferedReader reader = e.getValue().openAsReader()) {
                String text = reader.lines().collect(Collectors.joining("\n"));
                RecipeLayoutSpec spec = RecipeLayoutJson.parse(text,
                        RecipeLayoutJson.typeIdFor(id.getNamespace(), id.getPath()));
                layouts.put(spec.recipeTypeId(), spec);
            } catch (IOException | IllegalArgumentException ex) {
                LOGGER.warn("WMI: recipe layout {} ignored: {}", id, ex.getMessage());
            }
        }
        WmiRegistry.setJsonLayouts(layouts);
        if (!layouts.isEmpty()) {
            LOGGER.info("WMI: {} recipe layout(s) read from resources", layouts.size());
        }
    }

    /** Recettes et fiches des extensions, converties pour l'index ; garde leurs lignes de texte pour l'affichage. */
    public static List<RecipeEntry> collectRecipes(Function<String, String> categoryName) {
        ApiRecipes.Collected c = ApiRecipes.collect(WmiRegistry.recipeSuppliers(), WmiRegistry.allInfo(), categoryName);
        for (String err : c.errors()) {
            LOGGER.warn("WMI: extension recipe ignored: {}", err);
        }
        notes = Map.copyOf(c.notes());
        return new ArrayList<>(c.recipes());
    }

    /** Lignes de texte traduites d'une recette d'extension (vide si aucune). */
    public static List<String> notes(String recipeId) {
        List<String> keys = recipeId == null ? null : notes.get(recipeId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Language lang = Language.getInstance();
        List<String> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            out.add(lang.getOrDefault(k, k));
        }
        return out;
    }
}
