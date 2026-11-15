package waymoreitems.client;

import java.util.List;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

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
        return screen == null ? null : find(screen.children(), 0);
    }

    private static @Nullable GuiEventListener find(List<? extends GuiEventListener> listeners, int depth) {
        for (GuiEventListener listener : listeners) {
            if (listener.capturesInput()) {
                return listener;
            }
            if (depth < MAX_DEPTH && listener instanceof ContainerEventHandler container) {
                GuiEventListener inner = find(container.children(), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }
}
