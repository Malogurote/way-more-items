package waymoreitems.client;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import waymoreitems.core.FocusSearch;

/**
 * Détecte un champ de texte actif sur l'écran courant (recherche du mode créatif, enclume, terminal d'un mod...).
 * S'appuie sur {@link GuiEventListener#capturesInput()}, que les champs de saisie de Minecraft
 * ({@code EditBox}) rendent vrai tant qu'ils ont le focus et acceptent la saisie.
 */
final class WmiInputGuard {
    private static final int MAX_DEPTH = 4;

    private WmiInputGuard() {
    }

    static boolean anyWidgetCapturesInput(@Nullable Screen screen) {
        return capturingWidget(screen) != null;
    }

    /** Le champ de texte de l'écran qui a le focus et accepte la saisie, ou {@code null}. */
    static @Nullable GuiEventListener capturingWidget(@Nullable Screen screen) {
        return screen == null ? null : FocusSearch.<GuiEventListener>find(screen.children(), GuiEventListener::capturesInput,
                l -> l instanceof ContainerEventHandler container ? container.children() : null, MAX_DEPTH);
    }
}
