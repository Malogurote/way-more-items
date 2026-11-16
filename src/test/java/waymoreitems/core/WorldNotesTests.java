package waymoreitems.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Tests des Notes par monde ou serveur : clé du monde, nom de fichier, format, changement de monde, reprise. */
final class WorldNotesTests {
    private WorldNotesTests() {
    }

    static void run(BiConsumer<String, Boolean> check) {
        testKeys(check);
        testFileNames(check);
        testFormat(check);
        Path dir;
        try {
            dir = Files.createTempDirectory("wmi-notes-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            testSwitching(check, dir.resolve("a"));
            testLegacy(check, dir.resolve("b"));
            testUnreadable(check, dir.resolve("c"));
        } finally {
            deleteTree(dir);
        }
    }

    private static void testKeys(BiConsumer<String, Boolean> check) {
        check.accept("serveur : port par defaut, casse et point final ignores",
                WorldKey.server(" Play.Example.com.:25565 ").equals(WorldKey.server("play.example.com")));
        check.accept("serveur : autre port, autre liste",
                !WorldKey.server("play.example.com:25566").equals(WorldKey.server("play.example.com")));
        check.accept("monde et serveur de meme nom distincts",
                !WorldKey.singleplayer("lobby").equals(WorldKey.server("lobby")));
        check.accept("cle vide refusee", WorldKey.singleplayer("  ") == null && WorldKey.server(null) == null
                && WorldKey.realm("") == null);
    }

    private static void testFileNames(BiConsumer<String, Boolean> check) {
        String n = WorldKey.singleplayer("Mon Monde é/../CON").fileName();
        check.accept("nom de fichier sur : caracteres filtres", n.matches("world-[a-z0-9_-]+-[0-9a-f]{12}\\.json"));
        check.accept("nom de fichier lisible", n.startsWith("world-mon_monde_con-"));
        check.accept("noms ne differant que par un caractere filtre : fichiers distincts",
                !WorldKey.singleplayer("A/B").fileName().equals(WorldKey.singleplayer("A:B").fileName()));
        check.accept("nom de fichier borne", WorldKey.server("x".repeat(500)).fileName().length() <= 60);
        check.accept("nom fait seulement de signes", WorldKey.realm("***").fileName().startsWith("realm-x-"));
        check.accept("nom de fichier stable", WorldKey.server("a.b").fileName().equals(WorldKey.server("A.B:25565").fileName()));
    }

    private static void testFormat(BiConsumer<String, Boolean> check) {
        PinnedChecklist c = new PinnedChecklist();
        c.addNote("Chercher \"du fer\"\tet du \\ charbon");
        c.addNote("Faite");
        c.toggleNote(1);
        c.pinCraft("minecraft:piston", 2, Map.of("minecraft:oak_planks", "minecraft:oak_planks_recipe"), null);
        c.pinOrIncrementItem("minecraft:stone", 64);
        String text = WorldNotes.serialize(c, WorldKey.server("play.example.com"));
        PinnedChecklist back = new PinnedChecklist();
        WorldNotes.deserialize(text, back);
        check.accept("format : notes relues", back.notes().equals(c.notes()));
        check.accept("format : objectifs relus", back.savedGoals().equals(c.savedGoals()));
        check.accept("format : relecture identique", WorldNotes.serialize(back, WorldKey.server("play.example.com")).equals(text));
        boolean refused = false;
        try {
            WorldNotes.deserialize("{\"format\": \"autre\"}", new PinnedChecklist());
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.accept("format : autre fichier refuse", refused);
        PinnedChecklist partial = new PinnedChecklist();
        WorldNotes.deserialize("{\"format\": \"wmi_notes\", \"notes\": [{\"text\": \"ok\"}, 3, {\"done\": true}],"
                + " \"goals\": [{\"type\": \"item\", \"count\": 0, \"item\": \"a:b\"}, {\"type\": \"item\", \"count\": 2, \"item\": \"a:c\"},"
                + " {\"type\": \"?\", \"count\": 1, \"item\": \"a:d\"}]}", partial);
        check.accept("format : entrees illisibles ignorees", partial.notes().size() == 1 && partial.savedGoals().size() == 1
                && "a:c".equals(partial.savedGoals().get(0).itemId()));
    }

    private static void testSwitching(BiConsumer<String, Boolean> check, Path dir) {
        PinnedChecklist c = new PinnedChecklist();
        WorldNotes w = new WorldNotes(c, dir);
        WorldKey a = WorldKey.singleplayer("Monde A");
        WorldKey b = WorldKey.server("serveur.example");
        check.accept("hors d'un monde : rien d'ecrit", !w.flush() && !Files.exists(dir));
        check.accept("arrivee dans un monde neuf", w.switchTo(a) && c.isEmpty());
        c.addNote("note de A");
        c.pinOrIncrementItem("minecraft:stone", 3);
        check.accept("meme monde (autre dimension) : liste gardee", !w.switchTo(WorldKey.singleplayer("Monde A"))
                && c.notes().size() == 1);
        check.accept("autre monde : liste neuve", w.switchTo(b) && c.isEmpty());
        check.accept("liste du monde quitte ecrite", Files.isRegularFile(w.fileOf(a)));
        c.addNote("note du serveur");
        w.switchTo(a);
        check.accept("retour au monde : sa liste relue", c.notes().size() == 1 && "note de A".equals(c.notes().get(0).text())
                && c.goalCount() == 1);
        w.switchTo(null);
        check.accept("sortie du monde : liste videe", c.isEmpty() && w.current() == null);
        w.switchTo(b);
        check.accept("serveur : sa liste relue", c.notes().size() == 1 && "note du serveur".equals(c.notes().get(0).text()));
    }

    private static void testLegacy(BiConsumer<String, Boolean> check, Path dir) {
        PinnedChecklist c = new PinnedChecklist();
        WorldNotes w = new WorldNotes(c, dir);
        AtomicInteger adopted = new AtomicInteger();
        w.onLegacyAdopted(adopted::incrementAndGet);
        WorldKey existing = WorldKey.singleplayer("Ancien");
        writeQuietly(w.fileOf(existing), "{\"format\": \"wmi_notes\", \"notes\": [{\"text\": \"deja la\"}]}");
        w.setLegacy("1ancienne note\n0autre", "I\t3\tminecraft:stone");
        check.accept("reprise en attente", w.legacyPending());
        w.switchTo(existing);
        check.accept("monde deja pourvu : anciennes Notes non melees", c.notes().size() == 1 && w.legacyPending()
                && adopted.get() == 0);
        w.switchTo(WorldKey.singleplayer("Premier"));
        check.accept("anciennes Notes reprises dans le premier monde neuf", c.notes().size() == 2 && c.notes().get(0).done()
                && c.goalCount() == 1 && !w.legacyPending() && adopted.get() == 1);
        check.accept("anciennes Notes ecrites", Files.isRegularFile(w.fileOf(WorldKey.singleplayer("Premier"))));
        w.switchTo(WorldKey.singleplayer("Second"));
        check.accept("reprise faite une seule fois", c.isEmpty() && adopted.get() == 1);
    }

    private static void testUnreadable(BiConsumer<String, Boolean> check, Path dir) {
        PinnedChecklist c = new PinnedChecklist();
        WorldNotes w = new WorldNotes(c, dir);
        WorldKey k = WorldKey.server("casse.example");
        Path file = w.fileOf(k);
        writeQuietly(file, "{ illisible");
        w.switchTo(k);
        check.accept("fichier illisible : liste vide", c.isEmpty());
        check.accept("fichier illisible mis de cote", Files.isRegularFile(file.resolveSibling(file.getFileName() + SettingsFile.UNREADABLE_SUFFIX)));
        c.addNote("nouvelle");
        check.accept("fichier illisible : nouvelle liste ecrite", w.flush() && readQuietly(file).contains("nouvelle"));
    }

    private static void writeQuietly(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // dossier temporaire : laissé au système en cas d'échec
        }
    }

}
