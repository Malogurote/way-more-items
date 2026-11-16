package waymoreitems.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Notes et objectifs du monde ou du serveur en cours : un fichier JSON par {@link WorldKey} dans {@code dir}, lu à
 * l'arrivée dans le monde et écrit dès qu'il change. Hors d'un monde, la liste est vide et rien n'est écrit.
 *
 * <p>Reprise de l'existant : les Notes enregistrées avant les listes par monde (texte des anciens réglages) sont
 * rattachées au premier monde ouvert qui n'a pas encore de fichier, puis abandonnées une fois ce fichier écrit.
 *
 * <p>Fichier illisible : il est renommé {@code <nom>.illisible} et le monde repart d'une liste vide ; si le renommage
 * échoue, le fichier n'est jamais réécrit pendant la visite, pour ne rien perdre.
 */
public final class WorldNotes {
    private static final System.Logger LOGGER = System.getLogger(WorldNotes.class.getName());

    public static final String FORMAT = "wmi_notes";
    public static final int VERSION = 1;

    private final PinnedChecklist checklist;
    private final Path dir;
    private WorldKey current;
    /** Contenu tel qu'écrit ou lu la dernière fois pour {@link #current} (l'écriture n'a lieu qu'en cas d'écart). */
    private String lastSaved = "";
    /** Vrai si le fichier du monde en cours n'a pu être ni lu ni mis de côté : il n'est pas réécrit. */
    private boolean writeBlocked;
    private String legacyNotes = "";
    private String legacyGoals = "";
    private Runnable onLegacyAdopted = () -> { };

    public WorldNotes(PinnedChecklist checklist, Path dir) {
        this.checklist = Objects.requireNonNull(checklist, "checklist");
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    // ==================== REPRISE DES ANCIENNES NOTES ====================

    /**
     * Anciennes Notes à reprendre : notes au format de {@link ClientSettings#encodeNotes}, objectifs au format de
     * {@link PinnedChecklist#serializeGoals()}. Sans effet si les deux sont vides.
     */
    public void setLegacy(String notes, String goals) {
        legacyNotes = notes == null ? "" : notes;
        legacyGoals = goals == null ? "" : goals;
    }

    /** Vrai tant que d'anciennes Notes attendent un monde (elles restent alors à conserver dans les réglages). */
    public boolean legacyPending() {
        return !legacyNotes.isBlank() || !legacyGoals.isBlank();
    }

    public String legacyNotes() {
        return legacyNotes;
    }

    public String legacyGoals() {
        return legacyGoals;
    }

    /** Appelé une fois les anciennes Notes écrites dans le fichier d'un monde (les réglages peuvent les oublier). */
    public void onLegacyAdopted(Runnable callback) {
        onLegacyAdopted = callback == null ? () -> { } : callback;
    }

    // ==================== MONDE EN COURS ====================

    public WorldKey current() {
        return current;
    }

    public Path fileOf(WorldKey key) {
        return dir.resolve(key.fileName());
    }

    /**
     * Passe au monde {@code key} ({@code null} : hors d'un monde) : la liste du monde quitté est écrite, puis celle
     * du nouveau monde est lue. Rend vrai si la liste affichée a changé.
     */
    public boolean switchTo(WorldKey key) {
        if (Objects.equals(key, current)) {
            return false;
        }
        flush();
        checklist.clear();
        current = key;
        lastSaved = "";
        writeBlocked = false;
        if (key == null) {
            return true;
        }
        Path file = fileOf(key);
        if (Files.isRegularFile(file)) {
            readFile(file);
            lastSaved = serialize(checklist, key);
        } else if (legacyPending()) {
            ClientSettings.decodeNotes(legacyNotes, checklist);
            try {
                checklist.loadGoals(legacyGoals);
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not read the saved goals", e);
                // objectifs illisibles : les notes sont reprises quand même
            }
            if (flush()) {
                legacyNotes = "";
                legacyGoals = "";
                onLegacyAdopted.run();
            }
        }
        return true;
    }

    private void readFile(Path file) {
        try {
            deserialize(Files.readString(file, StandardCharsets.UTF_8), checklist);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not read the notes file " + file, e);
            checklist.clear();
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + SettingsFile.UNREADABLE_SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException moveFailure) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not set aside the unreadable notes file " + file, moveFailure);
                writeBlocked = true;
            }
        }
    }

    /** Écrit la liste du monde en cours si elle a changé ; rend vrai si le fichier est à jour. */
    public boolean flush() {
        if (current == null || writeBlocked) {
            return false;
        }
        String text = serialize(checklist, current);
        if (text.equals(lastSaved)) {
            return true;
        }
        Path file = fileOf(current);
        try {
            writeAtomic(file, text);
            lastSaved = text;
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not save the notes file " + file, e);
            // nouvel essai au prochain enregistrement ; l'ancien fichier reste intact (écriture atomique)
            return false;
        }
    }

    private static void writeAtomic(Path path, String text) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================== FORMAT ====================

    /**
     * Texte JSON des Notes et objectifs de {@code checklist} :
     * <pre>{"format": "wmi_notes", "version": 1, "world": {"kind": "server", "id": "..."},
     *  "notes": [{"text": "...", "done": false}],
     *  "goals": [{"type": "craft", "count": 2, "item": "...", "recipes": {"objet": "recette"}},
     *            {"type": "item", "count": 4, "item": "..."}]}</pre>
     * Le monde n'est écrit que pour s'y retrouver dans le dossier : la lecture ne s'en sert pas.
     */
    public static String serialize(PinnedChecklist checklist, WorldKey key) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        if (key != null) {
            Map<String, Object> world = new LinkedHashMap<>();
            world.put("kind", key.kind().prefix());
            world.put("id", key.id());
            root.put("world", world);
        }
        List<Object> notes = new ArrayList<>();
        for (PinnedChecklist.NoteEntry n : checklist.notes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", n.text());
            m.put("done", n.done());
            notes.add(m);
        }
        root.put("notes", notes);
        List<Object> goals = new ArrayList<>();
        for (PinnedChecklist.SavedGoal g : checklist.savedGoals()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", g.craft() ? "craft" : "item");
            m.put("count", g.count());
            m.put("item", g.itemId());
            if (g.craft() && !g.preferred().isEmpty()) {
                m.put("recipes", new LinkedHashMap<String, Object>(g.preferred()));
            }
            goals.add(m);
        }
        root.put("goals", goals);
        return MiniJson.write(root);
    }

    /**
     * Ajoute à {@code into} les Notes et objectifs d'un texte écrit par {@link #serialize}. Texte qui n'est pas un
     * fichier de Notes : {@link IllegalArgumentException} ; entrée isolée illisible : ignorée.
     */
    public static void deserialize(String text, PinnedChecklist into) {
        if (!(MiniJson.parse(text) instanceof Map<?, ?> root) || !FORMAT.equals(root.get("format"))) {
            throw new IllegalArgumentException("not a notes file");
        }
        if (root.get("notes") instanceof List<?> notes) {
            for (Object o : notes) {
                if (o instanceof Map<?, ?> m && m.get("text") instanceof String t && !t.isBlank()) {
                    into.addNote(t);
                    if (Boolean.TRUE.equals(m.get("done"))) {
                        into.toggleNote(into.notes().size() - 1);
                    }
                }
            }
        }
        if (root.get("goals") instanceof List<?> goals) {
            for (Object o : goals) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("item") instanceof String item)
                        || !(m.get("count") instanceof Number n) || n.doubleValue() < 1 || n.doubleValue() > Integer.MAX_VALUE) {
                    continue;
                }
                boolean craft = "craft".equals(m.get("type"));
                if (!craft && !"item".equals(m.get("type"))) {
                    continue;
                }
                Map<String, String> recipes = new LinkedHashMap<>();
                if (m.get("recipes") instanceof Map<?, ?> r) {
                    for (Map.Entry<?, ?> e : r.entrySet()) {
                        if (e.getKey() instanceof String k && e.getValue() instanceof String v && !k.isBlank() && !v.isBlank()) {
                            recipes.put(k, v);
                        }
                    }
                }
                into.addSavedGoal(new PinnedChecklist.SavedGoal(craft, (int) n.doubleValue(), item, recipes));
            }
        }
    }
}
