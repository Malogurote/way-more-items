package waymoreitems.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Monde ou serveur auquel une liste de Notes est rattachée, vu du client : dossier de sauvegarde en solo, adresse
 * en multijoueur, nom pour un Realm. Toutes les dimensions d'un même monde partagent la même clé.
 *
 * @param kind nature du monde
 * @param id   identifiant normalisé (dossier, adresse sans port par défaut, nom du Realm)
 */
public record WorldKey(Kind kind, String id) {
    /** Port par défaut de Minecraft Java : {@code serveur} et {@code serveur:25565} désignent le même serveur. */
    private static final String DEFAULT_PORT_SUFFIX = ":25565";
    /** Longueur maximale de la partie lisible d'un nom de fichier. */
    private static final int MAX_SLUG = 32;
    /** Nombre de caractères hexadécimaux du condensé ajouté au nom de fichier. */
    private static final int HASH_CHARS = 12;

    public enum Kind {
        WORLD("world"),
        SERVER("server"),
        REALM("realm");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }

        /** Nature d'après son préfixe, ou {@code null}. */
        public static Kind ofPrefix(String prefix) {
            for (Kind k : values()) {
                if (k.prefix.equals(prefix)) {
                    return k;
                }
            }
            return null;
        }
    }

    public WorldKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("blank world id");
        }
    }

    /** Monde solo d'après le nom de son dossier de sauvegarde ; {@code null} si le nom est vide. */
    public static WorldKey singleplayer(String folderName) {
        String id = folderName == null ? "" : folderName.strip();
        return id.isEmpty() ? null : new WorldKey(Kind.WORLD, id);
    }

    /** Serveur d'après son adresse ; {@code null} si elle est vide. */
    public static WorldKey server(String address) {
        String id = normalizeAddress(address);
        return id.isEmpty() ? null : new WorldKey(Kind.SERVER, id);
    }

    /** Realm d'après son nom ; {@code null} si le nom est vide. */
    public static WorldKey realm(String name) {
        String id = name == null ? "" : name.strip();
        return id.isEmpty() ? null : new WorldKey(Kind.REALM, id);
    }

    /**
     * Adresse comparable : sans espaces autour, en minuscules, sans le port par défaut ni le point final du nom
     * d'hôte ({@code Play.Example.com.:25565} et {@code play.example.com} sont le même serveur).
     */
    static String normalizeAddress(String address) {
        if (address == null) {
            return "";
        }
        String a = address.strip().toLowerCase(Locale.ROOT);
        if (a.endsWith(DEFAULT_PORT_SUFFIX)) {
            a = a.substring(0, a.length() - DEFAULT_PORT_SUFFIX.length());
        }
        while (a.endsWith(".")) {
            a = a.substring(0, a.length() - 1);
        }
        return a;
    }

    /**
     * Nom du fichier de Notes : préfixe de la nature, partie lisible limitée à {@code [a-z0-9_-]}, puis condensé
     * SHA-256 de la clé complète (deux mondes dont seuls les caractères filtrés diffèrent ont deux fichiers).
     * Exemple : {@code world-mon_monde-1a2b3c4d5e6f.json}.
     */
    public String fileName() {
        return kind.prefix() + "-" + slug(id) + "-" + hash(kind.prefix() + ":" + id) + ".json";
    }

    static String slug(String text) {
        StringBuilder sb = new StringBuilder();
        boolean lastUnderscore = false;
        for (int i = 0; i < text.length() && sb.length() < MAX_SLUG; i++) {
            char c = Character.toLowerCase(text.charAt(i));
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (keep) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore && sb.length() > 0) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? "x" : sb.toString();
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
