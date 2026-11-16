package waymoreitems.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import waymoreitems.core.GuiLayout;
import waymoreitems.core.MiniJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mise en page appliquée à la surcouche, rechargement à chaud et relevé de l'écran ouvert.
 * <ul>
 * <li>Rechargement : si {@code config/wmi-layout.json} existe, il est relu dès qu'il change (contrôle chaque
 * seconde) et appliqué sans recompiler ; en cas d'erreur, la mise en page précédente reste en place et la cause
 * s'affiche dans la discussion. Fichier retiré : retour à la mise en page d'origine.</li>
 * <li>Relevé : {@code /wmi releve} arme un relevé ; à la prochaine image d'un écran à inventaire, les rectangles
 * réellement calculés et le contexte (taille, échelle, thème, états) sont écrits dans
 * {@code config/wmi-releves/}, pour le concepteur d'interfaces.</li>
 * </ul>
 */
public final class WmiLayoutRuntime {
    public static final String LAYOUT_FILE = "wmi-layout.json";
    public static final String CAPTURE_DIR = "wmi-releves";

    private static GuiLayout layout = GuiLayout.defaults();
    private static boolean fromFile;
    private static long lastModified = Long.MIN_VALUE;
    private static int tickCounter;
    private static boolean captureArmed;

    private WmiLayoutRuntime() {
    }

    public static GuiLayout layout() {
        return layout;
    }

    private static Path configDir() {
        return WmiPlatform.configDir();
    }

    /** Appelé à chaque tick client : relit le fichier de mise en page s'il a changé. */
    public static void tick() {
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        reload(false);
    }

    /** Relit {@code wmi-layout.json} ; {@code force} relit même sans changement de date. Rend un compte rendu. */
    public static String reload(boolean force) {
        Path path = configDir().resolve(LAYOUT_FILE);
        try {
            if (!Files.isRegularFile(path)) {
                if (fromFile) {
                    layout = GuiLayout.defaults();
                    fromFile = false;
                    lastModified = Long.MIN_VALUE;
                    tell(WmiText.s("wmi.layout.restored", LAYOUT_FILE), ChatFormatting.YELLOW);
                }
                return noLayoutFileMessage();
            }
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (!force && mtime == lastModified) {
                return "";
            }
            lastModified = mtime;
            GuiLayout next = GuiLayout.fromJson(Files.readString(path, StandardCharsets.UTF_8));
            List<String> problems = next.validate();
            if (!problems.isEmpty()) {
                String msg = WmiText.s("wmi.layout.refused", problems.subList(0, Math.min(3, problems.size())).stream().map(WmiLayoutRuntime::localize).collect(Collectors.joining(" ; ")));
                tell(msg, ChatFormatting.RED);
                return msg;
            }
            layout = next;
            fromFile = true;
            String msg = WmiText.s("wmi.layout.reloaded", LAYOUT_FILE);
            tell(msg, ChatFormatting.GREEN);
            return msg;
        } catch (Exception ex) {
            String msg = WmiText.s("wmi.layout.refused", localize(String.valueOf(ex.getMessage())));
            tell(msg, ChatFormatting.RED);
            return msg;
        }
    }

    /** Message de l'absence de fichier de mise en page (comparé par {@code /wmi mise_en_page}). */
    public static String noLayoutFileMessage() {
        return WmiText.s("wmi.layout.none", LAYOUT_FILE);
    }

    /**
     * Traduit un message neutre du noyau ({@code clé|argument|...}, voir {@code GuiLayout} et {@code MiniJson}) ;
     * un message inconnu est rendu tel quel.
     */
    static String localize(String raw) {
        String[] p = raw.split("\\|", -1);
        if ("json".equals(p[0]) && p.length >= 4) {
            String sub = String.join("|", Arrays.copyOfRange(p, 3, p.length));
            return WmiText.s("wmi.layout.err.json", p[1], p[2], localize(sub));
        }
        String key = "wmi.layout.err." + p[0];
        if (!Language.getInstance().has(key)) {
            return raw;
        }
        return WmiText.s(key, (Object[]) Arrays.copyOfRange(p, 1, p.length));
    }

    public static boolean fromFile() {
        return fromFile;
    }

    public static void armCapture() {
        captureArmed = true;
    }

    public static boolean captureArmed() {
        return captureArmed;
    }

    private static void tell(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(WmiText.c("wmi.cmd.prefix", text).withStyle(color));
        }
    }

    /** Type d'écran de référence, pour le concepteur. */
    static String screenType(AbstractContainerScreen<?> screen) {
        if (screen instanceof CreativeModeInventoryScreen) {
            return "creative";
        }
        if (screen instanceof InventoryScreen) {
            return "inventory";
        }
        String n = screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return n.contains("container") || n.contains("chest") || n.contains("shulker") ? "chest" : "container";
    }

    /** Écrit le relevé si un relevé est armé ; {@code extra} porte le contexte (thème, états, entrées de barre). */
    static void captureIfArmed(AbstractContainerScreen<?> screen, GuiLayout.Resolver frame, int screenW, int screenH,
                               Map<String, Object> extra, Map<String, Boolean> drawn) {
        if (!captureArmed || frame == null) {
            return;
        }
        captureArmed = false;
        Minecraft mc = Minecraft.getInstance();
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            var window = mc.getWindow();
            context.put("window", List.of((double) window.getWidth(), (double) window.getHeight()));
            context.put("guiScale", (double) window.getGuiScale());
            context.put("guiScaleOption", (double) mc.options.guiScale().get());
            context.put("gui", List.of((double) screenW, (double) screenH));
            context.put("screenType", screenType(screen));
            context.put("screenClass", screen.getClass().getName());
            context.put("inventory", List.of((double) screen.getLeftPos(), (double) screen.getTopPos(),
                    (double) screen.getImageWidth(), (double) screen.getImageHeight()));
            Map<String, Object> resolvedConstants = new LinkedHashMap<>();
            for (String c : List.of("margin", "gap", "rightPanelRatio")) {
                resolvedConstants.put(c, frame.constant(c));
            }
            context.put("constants", resolvedConstants);
            context.put("layoutFromFile", fromFile);
            context.putAll(extra);

            Map<String, Object> rects = new LinkedHashMap<>();
            frame.rects().forEach((k, r) -> rects.put(k, r.toList()));
            Map<String, Object> sub = new LinkedHashMap<>();
            frame.measuredOnly().forEach((k, r) -> sub.put(k, r.toList()));
            Map<String, Object> contents = new LinkedHashMap<>();
            frame.contents().forEach((k, v) -> contents.put(k, List.of((double) v[0], (double) v[1])));

            Map<String, Object> measured = new LinkedHashMap<>();
            measured.put("context", context);
            measured.put("rects", rects);
            measured.put("drawn", new LinkedHashMap<>(drawn));
            measured.put("contents", contents);
            measured.put("subRects", sub);

            Map<String, Object> doc = layout.toDocument();
            doc.put("kind", "releve");
            doc.put("measured", measured);

            Path dir = configDir().resolve(CAPTURE_DIR);
            Files.createDirectories(dir);
            String name = String.format(Locale.ROOT, "releve_%s_%dx%d_x%d.json", screenType(screen),
                    window.getWidth(), window.getHeight(), window.getGuiScale());
            Path out = dir.resolve(name);
            Files.writeString(out, MiniJson.write(doc), StandardCharsets.UTF_8);
            tell(WmiText.s("wmi.layout.capture_saved", CAPTURE_DIR + "/" + name), ChatFormatting.GREEN);
        } catch (Exception ex) {
            tell(WmiText.s("wmi.layout.capture_failed", String.valueOf(ex.getMessage())), ChatFormatting.RED);
        }
    }

    /** Couleur ARGB au format #AARRGGBB, pour le relevé. */
    static String hex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }
}
