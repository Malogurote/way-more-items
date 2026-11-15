package waymoreitems.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Accès unique aux textes traduits de WMI (clés {@code wmi.<zone>.<nom>} des fichiers de langue).
 * Minecraft empile la langue choisie sur {@code en_us} : une clé absente du français s'affiche en anglais.
 */
public final class WmiText {
    private WmiText() {}

    public static MutableComponent c(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static String s(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Pluriel simple : clé {@code key} au singulier, {@code key + ".many"} sinon. */
    public static String n(String key, int count, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = count;
        System.arraycopy(args, 0, all, 1, args.length);
        return s(count > 1 ? key + ".many" : key, all);
    }
}
