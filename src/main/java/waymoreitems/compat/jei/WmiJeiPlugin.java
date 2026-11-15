package waymoreitems.compat.jei;

import java.util.Collection;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.Identifier;
import waymoreitems.WmiMod;
import waymoreitems.client.WmiClient;

/**
 * Greffon de Way More Items pour JEI, chargé par JEI seulement s'il est installé (classe jamais appelée sinon).
 *
 * <p>Il déclare à JEI les zones occupées par WMI : en mode WMI, les deux côtés de l'inventaire (la liste et les
 * favoris de JEI s'effacent) ; en mode JEI, seulement les éléments de gauche de WMI (barre d'outils, Notes,
 * widget, boutons du haut), que les favoris de JEI évitent alors.</p>
 */
@JeiPlugin
public final class WmiJeiPlugin implements IModPlugin {
    private static final Identifier UID = Identifier.fromNamespaceAndPath(WmiMod.MODID, "jei_cohabitation");

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGlobalGuiHandler(new IGlobalGuiHandler() {
            @Override
            public Collection<Rect2i> getGuiExtraAreas() {
                return WmiClient.overlay().jeiExclusionAreas();
            }
        });
    }
}
