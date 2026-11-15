package waymoreitems.core;

/**
 * Palette d'un thème d'interface (couleurs ARGB), et dérivation d'une palette translucide complète à partir
 * d'une seule couleur de base. Java pur, sans dépendance à Minecraft.
 * <p>Style conservé : fonds de panneau, de carte et de case semi-transparents (le flou de l'arrière-plan
 * transparaît), contours fins, une couleur d'accent de même teinte que la base. Une base claire donne un thème
 * clair à texte foncé ; une base sombre, un thème sombre à texte clair.
 */
public record ThemePalette(
        int panelBg, int cardBg, int slotBg, int slotHover, int border, int innerBorder,
        int textMain, int textMuted, int accent, int accentSoft,
        int purple, int purpleSoft, int gold, int green, boolean light) {

    /** Seuil de luminance (0 à 1) au-dessus duquel la base donne un thème clair. */
    public static final double LIGHT_THRESHOLD = 0.55;

    /** Palette translucide dérivée de {@code baseRgb} (0xRRGGBB, l'alpha éventuel est ignoré). */
    public static ThemePalette fromBase(int baseRgb) {
        int base = baseRgb & 0xFFFFFF;
        float[] hsv = rgbToHsv(base);
        boolean light = luminance(base) >= LIGHT_THRESHOLD;
        // Accent : même teinte, assez saturé ; bleu par défaut si la base est presque grise
        boolean grey = hsv[1] < 0.12F;
        if (light) {
            int accent = grey ? 0x2563EB : hsvToRgb(hsv[0], Math.max(hsv[1], 0.65F), 0.62F);
            return new ThemePalette(
                    argb(0xA8, mix(base, 0xFFFFFF, 0.55)),
                    argb(0xC6, mix(base, 0xFFFFFF, 0.75)),
                    argb(0x8F, mix(base, 0xFFFFFF, 0.85)),
                    argb(0xF2, mix(base, 0xFFFFFF, 0.93)),
                    argb(0xE6, mix(base, 0xFFFFFF, 0.90)),
                    argb(0x24, mix(base, 0x000000, 0.70)),
                    argb(0xFF, mix(0x18202C, accent, 0.10)),
                    argb(0xFF, mix(0x526075, accent, 0.25)),
                    argb(0xFF, accent),
                    argb(0x30, accent),
                    0xFF7C3AED, 0x307C3AED, 0xFFD97706, 0xFF059669, true);
        }
        int accent = grey ? 0x38BDF8 : hsvToRgb(hsv[0], Math.max(hsv[1], 0.55F), 0.98F);
        return new ThemePalette(
                argb(0xB8, mix(base, 0x000000, 0.15)),
                argb(0xCC, mix(base, 0xFFFFFF, 0.08)),
                argb(0x94, mix(base, 0x000000, 0.35)),
                argb(0xE8, mix(base, 0xFFFFFF, 0.18)),
                argb(0x40, mix(accent, 0xFFFFFF, 0.60)),
                argb(0x24, mix(accent, 0xFFFFFF, 0.60)),
                argb(0xFF, mix(0xF8FAFC, accent, 0.06)),
                argb(0xFF, mix(mix(base, 0xFFFFFF, 0.60), accent, 0.15)),
                argb(0xFF, accent),
                argb(0x38, accent),
                0xFFC084FC, 0x36C084FC, 0xFFFBBF24, 0xFF34D399, false);
    }

    /** Luminance perçue de 0 (noir) à 1 (blanc). */
    public static double luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    /** Mélange linéaire de deux couleurs RGB : {@code t} = 0 donne {@code a}, 1 donne {@code b}. */
    public static int mix(int a, int b, double t) {
        double k = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    public static int argb(int alpha, int rgb) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    /** Teinte, saturation et valeur (chacune de 0 à 1) vers 0xRRGGBB. */
    public static int hsvToRgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6.0F;
        float ss = Math.max(0.0F, Math.min(1.0F, s));
        float vv = Math.max(0.0F, Math.min(1.0F, v));
        int sector = (int) Math.floor(hh) % 6;
        float f = hh - (float) Math.floor(hh);
        float p = vv * (1 - ss);
        float q = vv * (1 - ss * f);
        float t = vv * (1 - ss * (1 - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = vv; g = t; b = p; }
            case 1 -> { r = q; g = vv; b = p; }
            case 2 -> { r = p; g = vv; b = t; }
            case 3 -> { r = p; g = q; b = vv; }
            case 4 -> { r = t; g = p; b = vv; }
            default -> { r = vv; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** 0xRRGGBB vers {teinte, saturation, valeur}, chacune de 0 à 1. */
    public static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0) {
            h = 0;
        } else if (max == r) {
            h = ((g - b) / d) / 6.0F;
        } else if (max == g) {
            h = ((b - r) / d + 2) / 6.0F;
        } else {
            h = ((r - g) / d + 4) / 6.0F;
        }
        if (h < 0) {
            h += 1;
        }
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }
}
