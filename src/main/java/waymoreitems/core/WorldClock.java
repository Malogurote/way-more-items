package waymoreitems.core;

/**
 * Heure du monde de Minecraft, sans dépendance au jeu : un jour dure 24 000 ticks et le tick 0 correspond à 6 h.
 * Le soleil se lève à 5 h (tick 23 000) et se couche à 19 h (tick 13 000) ; la lune occupe la nuit, de 19 h à 5 h.
 */
public final class WorldClock {

    public static final long TICKS_PER_DAY = 24000L;
    /** Décalage entre le tick 0 et minuit (le tick 0 est à 6 h). */
    private static final long MIDNIGHT_OFFSET = 6000L;
    /** Minute qui précède l'apparition des monstres par temps clair : ticks 12 000 à 13 188 du jour. */
    private static final long NIGHT_WARNING_START = 12000L;
    private static final long NIGHT_WARNING_END = 13188L;
    private static final double SUNRISE_HOUR = 5.0;
    private static final double SUNSET_HOUR = 19.0;

    private WorldClock() {
    }

    /** Heure du jour en heures décimales (0 à 24 non compris) pour un temps de jour positif. */
    public static double hourOfDay(long dayTime) {
        return ((dayTime + MIDNIGHT_OFFSET) % TICKS_PER_DAY) / 1000.0;
    }

    /** Heures (0 à 23) et minutes (0 à 59) affichées pour un temps de jour positif. */
    public static int hours(long dayTime) {
        return (int) (ticksSinceMidnight(dayTime) / 1000L);
    }

    public static int minutes(long dayTime) {
        return (int) ((ticksSinceMidnight(dayTime) % 1000L) * 60L / 1000L);
    }

    /** Numéro du jour, à partir de 1. */
    public static long dayNumber(long dayTime) {
        return dayTime / TICKS_PER_DAY + 1L;
    }

    /** Vrai pendant la minute qui précède l'apparition des monstres (coucher du soleil). */
    public static boolean nightWarning(long dayTime) {
        long t = Math.floorMod(dayTime, TICKS_PER_DAY);
        return t >= NIGHT_WARNING_START && t < NIGHT_WARNING_END;
    }

    /** Heure sur 12 : 12 pour midi et minuit, 1 à 11 sinon. */
    public static int twelveHour(int hours) {
        return hours % 12 == 0 ? 12 : hours % 12;
    }

    /** Course du soleil de 0 (lever, 5 h) à 1 (coucher, 19 h) ; hors de [0, 1], il est sous l'horizon. */
    public static double sunProgress(double hourF) {
        return (hourF - SUNRISE_HOUR) / (SUNSET_HOUR - SUNRISE_HOUR);
    }

    /** Course de la lune de 0 (19 h) à 1 (5 h) ; hors de [0, 1], elle est sous l'horizon. */
    public static double moonProgress(double hourF) {
        return ((hourF - SUNSET_HOUR + 24.0) % 24.0) / (24.0 - SUNSET_HOUR + SUNRISE_HOUR);
    }

    /** L'astre est au-dessus de l'horizon pour cette course. */
    public static boolean aboveHorizon(double progress) {
        return progress >= 0.0 && progress <= 1.0;
    }

    /** Hauteur de l'astre sur sa trajectoire en arc, de 0 (horizon) à 1 (zénith, à mi-course). */
    public static double arcHeight(double progress) {
        return Math.sin(Math.PI * progress);
    }

    private static long ticksSinceMidnight(long dayTime) {
        return (dayTime + MIDNIGHT_OFFSET) % TICKS_PER_DAY;
    }
}
