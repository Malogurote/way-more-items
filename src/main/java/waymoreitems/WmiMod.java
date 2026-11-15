package waymoreitems;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Point d'entrée commun de Way More Items (WMI, identifiant {@code wmi}).
 * Indépendant de tout autre mod : demande au serveur d'envoyer tous les types de recettes enregistrés
 * (vanilla et mods tiers) aux clients lors de la synchronisation des paquets de données, et enregistre
 * les paquets réseau sécurisés d'actions rapides, de don d'objets, de tri et de transfert de recettes.
 */
@Mod(WmiMod.MODID)
public final class WmiMod {
    public static final String MODID = "wmi";

    public WmiMod(IEventBus modBus) {
        modBus.addListener(WmiPayloads::register);
        NeoForge.EVENT_BUS.addListener(WmiMod::onDatapackSync);
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        List<RecipeType<?>> allTypes = new ArrayList<>();
        for (RecipeType<?> type : BuiltInRegistries.RECIPE_TYPE) {
            allTypes.add(type);
        }
        if (!allTypes.isEmpty()) {
            event.sendRecipes(allTypes);
        }
    }
}
