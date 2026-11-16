package waymoreitems.client;

import com.mojang.logging.LogUtils;
import waymoreitems.core.TimingStats;

import java.util.Locale;

/**
 * Mesures de performance pour le développement, désactivées par défaut : propriété système {@code -Dwaymoreitems.perf=true}
 * (propriété Gradle {@code wmiPerf=true} pour {@code runClient}) ou commande {@code /wmi perf}.
 * Toutes les dix secondes d'écran à inventaire, une ligne « WMI perf » est écrite dans le journal du jeu.
 * Désactivées, les appels se réduisent à la lecture d'un booléen.
 */
public final class WmiPerf {
    private static final long REPORT_NANOS = 10_000_000_000L;
    /** Au-delà, l'écart entre deux images n'est pas un temps d'image (écran fermé puis rouvert). */
    private static final long MAX_FRAME_GAP_NANOS = 1_000_000_000L;

    private static boolean enabled = Boolean.getBoolean("waymoreitems.perf");

    static final TimingStats FRAME = new TimingStats();
    static final TimingStats OVERLAY = new TimingStats();
    static final TimingStats RIGHT_PANEL = new TimingStats();
    static final TimingStats CATALOG_LIST = new TimingStats();
    static final TimingStats CATALOG_REBUILD = new TimingStats();

    private static long lastFrame;
    private static long windowStart;
    private static String windowMode = "";

    private WmiPerf() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        if (enabled && !on) {
            report("final");
        }
        enabled = on;
        resetWindow();
        lastFrame = 0;
    }

    /** Début d'une mesure : 0 si les mesures sont désactivées. */
    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void stop(TimingStats stats, long start) {
        if (start != 0L && enabled) {
            stats.add(System.nanoTime() - start);
        }
    }

    /**
     * Début du dessin de la surcouche d'un écran à inventaire : l'écart avec l'image précédente est le temps d'image
     * complet (jeu, JEI et WMI) ; écrit le rapport de la fenêtre écoulée.
     */
    static void frame(String mode) {
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        if (!mode.equals(windowMode)) {
            if (!windowMode.isEmpty()) {
                report("mode change");
            }
            resetWindow();
            windowMode = mode;
            windowStart = now;
        }
        if (lastFrame != 0L && now - lastFrame < MAX_FRAME_GAP_NANOS) {
            FRAME.add(now - lastFrame);
        }
        lastFrame = now;
        if (windowStart == 0L) {
            windowStart = now;
        } else if (now - windowStart >= REPORT_NANOS) {
            report("window");
            resetWindow();
            windowMode = mode;
            windowStart = now;
        }
    }

    private static void resetWindow() {
        FRAME.reset();
        OVERLAY.reset();
        RIGHT_PANEL.reset();
        CATALOG_LIST.reset();
        CATALOG_REBUILD.reset();
        windowStart = 0L;
        windowMode = "";
    }

    private static void report(String reason) {
        if (OVERLAY.count() == 0) {
            return;
        }
        double fps = FRAME.count() == 0 ? 0 : 1000.0 / FRAME.meanMillis();
        LogUtils.getLogger().info(String.format(Locale.ROOT,
                "WMI perf [%s, %s, %d items] frame: %s (%.1f fps) | overlay: %s | right panel: %s | catalog list: %s"
                        + " | catalog recomputed: %s",
                windowMode, reason, WmiClientIndex.peek().allItems().size(), FRAME.summary(), fps, OVERLAY.summary(),
                RIGHT_PANEL.summary(), CATALOG_LIST.summary(), CATALOG_REBUILD.summary()));
    }

    /**
     * Durées d'une construction de l'index, par phase, en nanosecondes : temps de travail effectif (la somme), puis
     * nombre de tranches et durée écoulée entre le début et la publication (construction étalée sur plusieurs ticks).
     */
    static void logRebuild(long catalog, long recipes, long extractors, long index, long tabs, int items,
                           int recipesRead, int recipesReceived, int steps, long wallNanos) {
        if (!enabled) {
            return;
        }
        LogUtils.getLogger().info(String.format(Locale.ROOT,
                "WMI perf rebuildNow: total %.1f ms (catalog %.1f, recipes %.1f, extractors %.1f, index %.1f,"
                        + " creative tabs %.1f) | %d steps, %.1f ms wall | %d items, %d/%d recipes read",
                (catalog + recipes + extractors + index + tabs) / 1e6, catalog / 1e6, recipes / 1e6,
                extractors / 1e6, index / 1e6, tabs / 1e6, steps, wallNanos / 1e6, items, recipesRead,
                recipesReceived));
    }
}
