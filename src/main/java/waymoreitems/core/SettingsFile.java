package waymoreitems.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Fichier de réglages {@code clé=valeur} (format {@link Properties}), en Java pur.
 * <ul>
 *   <li>Lecture clé par clé avec repli : une valeur illisible ou hors bornes donne la valeur par défaut de cette clé
 *       seule, les autres sont lues normalement ;</li>
 *   <li>fichier illisible : il est copié à côté ({@code .illisible}) avant d'être remplacé au prochain enregistrement,
 *       pour que les Notes et objectifs puissent être récupérés à la main ;</li>
 *   <li>écriture atomique : fichier temporaire, puis renommage.</li>
 * </ul>
 */
public final class SettingsFile {
    private static final System.Logger LOGGER = System.getLogger(SettingsFile.class.getName());

    /** Suffixe de la copie de secours d'un fichier illisible. */
    public static final String UNREADABLE_SUFFIX = ".illisible";

    private final Properties props;
    private final boolean unreadable;

    private SettingsFile(Properties props, boolean unreadable) {
        this.props = props;
        this.unreadable = unreadable;
    }

    /** Réglages vides (toutes les clés prendront leur valeur par défaut). */
    public static SettingsFile empty() {
        return new SettingsFile(new Properties(), false);
    }

    /** Réglages tirés d'un objet {@link Properties} déjà rempli (tests). */
    public static SettingsFile of(Properties props) {
        Properties copy = new Properties();
        copy.putAll(props);
        return new SettingsFile(copy, false);
    }

    /**
     * Lit {@code path}. Absent : réglages vides. Illisible : réglages vides, et copie de secours du fichier sous
     * {@code <nom>.illisible} (une copie de secours existante est remplacée).
     */
    public static SettingsFile load(Path path) {
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
            return new SettingsFile(p, false);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not read the settings file " + path, e);
            try {
                Files.copy(path, path.resolveSibling(path.getFileName() + UNREADABLE_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException copyFailure) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not back up the unreadable settings file " + path, copyFailure);
                // la copie de secours est un confort : sans elle, le chargement continue avec les valeurs par défaut
            }
            return new SettingsFile(new Properties(), true);
        }
    }

    /** Vrai si le fichier existait mais n'a pas pu être lu (une copie de secours a été tentée). */
    public boolean wasUnreadable() {
        return unreadable;
    }

    public String string(String key, String def) {
        String v = props.getProperty(key);
        return v == null ? def : v;
    }

    public boolean bool(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        String t = v.trim();
        if (t.equalsIgnoreCase("true")) {
            return true;
        }
        if (t.equalsIgnoreCase("false")) {
            return false;
        }
        return def;
    }

    public int integer(String key, int def, int min, int max) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Nombre fini borné à {@code [min, max]} ; illisible, infini ou NaN : {@code def}. */
    public double decimal(String key, double def, double min, double max) {
        double d = decimal(key, def);
        return Math.max(min, Math.min(max, d));
    }

    /** Nombre fini sans bornes ; illisible, infini ou NaN : {@code def}. */
    public double decimal(String key, double def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, v.trim());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /**
     * Écrit {@code props} dans {@code path} sans jamais laisser un fichier à moitié écrit : écriture dans
     * {@code <nom>.tmp}, puis renommage (atomique si le système de fichiers le permet).
     */
    public static void storeAtomic(Path path, Properties props, String comment) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, comment);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
