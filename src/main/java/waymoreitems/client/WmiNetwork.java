package waymoreitems.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import waymoreitems.WmiPayloads;

/**
 * Point d'envoi unique des paquets de WMI vers le serveur.
 *
 * <p>Les charges utiles sont enregistrées en {@code optional()} : la connexion à un serveur sans WMI est admise, mais
 * {@code ClientCommonPacketListenerImpl.send} appelle {@code NetworkRegistry.checkPacket}, qui lève
 * {@code UnsupportedOperationException} si le canal n'a pas été négocié (sources NeoForge 26.3.0.103). On teste donc
 * le canal avant tout envoi ({@code ICommonPacketListener.hasChannel}) ; à défaut, rien n'est envoyé et le joueur
 * en est averti dans la barre d'action. Les fonctions serveur se masquent d'après {@link #serverHasWmi()}.</p>
 */
public final class WmiNetwork {

    private WmiNetwork() {
    }

    /** Vrai si la connexion en cours a négocié le canal de WMI (le serveur a WMI). */
    public static boolean serverHasWmi() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(WmiPayloads.ActionPayload.TYPE);
    }

    /**
     * Envoie {@code payload} si le serveur l'accepte. Renvoie faux, sans rien envoyer, sinon (serveur sans WMI ou
     * hors connexion) ; le joueur est alors averti.
     */
    public static boolean send(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || !connection.hasChannel(payload)) {
            if (mc.player != null) {
                mc.player.sendOverlayMessage(WmiText.c("wmi.network.unavailable"));
            }
            return false;
        }
        ClientPacketDistributor.sendToServer(payload);
        return true;
    }
}
