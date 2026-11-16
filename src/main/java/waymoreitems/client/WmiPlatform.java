package waymoreitems.client;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Accès unique aux services du chargeur : dossier de configuration ({@code FMLPaths}) et liste des mods
 * ({@code ModList}). Seule classe du client à les citer, pour qu'une montée de version ou un autre chargeur
 * ne touche qu'ici.
 */
public final class WmiPlatform {
    private static final Logger LOGGER = LogUtils.getLogger();

    private WmiPlatform() {
    }

    /** Dossier de configuration du jeu ; {@code config} relatif si le chargeur ne répond pas. */
    public static Path configDir() {
        try {
            return FMLPaths.CONFIGDIR.get();
        } catch (RuntimeException e) {
            LOGGER.debug("Config directory not available yet", e);
            return Path.of("config");
        }
    }

    /** Fichier du dossier de configuration ; à défaut, le même nom dans le dossier courant. */
    public static Path configFile(String name) {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(name);
        } catch (RuntimeException e) {
            LOGGER.debug("Config directory not available yet", e);
            return Path.of(name);
        }
    }

    /** Vrai si le mod {@code modId} est chargé (faux si la liste des mods n'est pas encore prête). */
    public static boolean isModLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (RuntimeException e) {
            LOGGER.debug("Mod list not available yet", e);
            return false;
        }
    }

    public static boolean jeiInstalled() {
        return isModLoaded("jei");
    }

    /** Nom affiché du mod {@code modId}, ou l'identifiant lui-même s'il est inconnu. */
    public static String modDisplayName(String modId) {
        ModList ml = ModList.get();
        return ml == null ? modId : ml.getModContainerById(modId).map(c -> c.getModInfo().getDisplayName()).orElse(modId);
    }

    /** Parcourt les mods chargés : identifiant et nom affiché. */
    public static void forEachMod(BiConsumer<String, String> action) {
        ModList.get().getMods().forEach(info -> action.accept(info.getModId(), info.getDisplayName()));
    }
}
