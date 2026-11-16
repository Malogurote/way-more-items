package waymoreitems.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Contrôle des fichiers de langue (Java pur) : mêmes clés dans tous les fichiers de langue que {@code en_us} (référence), aucune valeur vide,
 * mêmes paramètres de format, et toute clé citée dans le code existe. Fichiers lus depuis la racine du projet.
 */
final class LangTests {
    /** Racine du projet : propriété système {@code wmi.projectDir} (posée par la tâche Gradle), sinon le dossier courant. */
    private static final Path ROOT = Path.of(System.getProperty("wmi.projectDir", "."));
    private static final Path LANG = ROOT.resolve("src/main/resources/assets/wmi/lang");
    private static final Path SRC = ROOT.resolve("src/main/java");
    private static final Pattern CODE_KEY = Pattern.compile("\"((?:gui\\.|key\\.)?wmi\\.[a-z0-9_.]*)\"");
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[sd]");

    private LangTests() {}

    @SuppressWarnings("unchecked")
    static void run(BiConsumer<String, Boolean> check) {
        try {
            Map<String, Object> en = (Map<String, Object>) MiniJson.parse(Files.readString(LANG.resolve("en_us.json"), StandardCharsets.UTF_8));
            int others = 0;
            try (Stream<Path> langs = Files.list(LANG)) {
                for (Path lp : (Iterable<Path>) langs.filter(f -> f.toString().endsWith(".json") && !f.getFileName().toString().equals("en_us.json")).sorted()::iterator) {
                    String name = lp.getFileName().toString();
                    Map<String, Object> fr = (Map<String, Object>) MiniJson.parse(Files.readString(lp, StandardCharsets.UTF_8));
                    others++;
                    TreeSet<String> onlyFr = new TreeSet<>(fr.keySet());
                    onlyFr.removeAll(en.keySet());
                    TreeSet<String> onlyEn = new TreeSet<>(en.keySet());
                    onlyEn.removeAll(fr.keySet());
                    check.accept("Langue " + name + " : clés en trop " + onlyFr, onlyFr.isEmpty());
                    check.accept("Langue " + name + " : clés manquantes " + onlyEn, onlyEn.isEmpty());
                    TreeSet<String> empty = new TreeSet<>();
                    TreeSet<String> badFormat = new TreeSet<>();
                    for (String k : en.keySet()) {
                        String e = String.valueOf(en.get(k));
                        String f = String.valueOf(fr.get(k));
                        if (e.isBlank() || f.isBlank()) {
                            empty.add(k);
                        }
                        if (fr.containsKey(k) && count(e) != count(f)) {
                            badFormat.add(k);
                        }
                    }
                    check.accept("Langue " + name + " : valeurs vides " + empty, empty.isEmpty());
                    check.accept("Langue " + name + " : paramètres de format différents " + badFormat, badFormat.isEmpty());
                }
            }
            check.accept("Langue : fr_fr présent avec les autres langues", others >= 1 && Files.exists(LANG.resolve("fr_fr.json")));

            TreeSet<String> missing = new TreeSet<>();
            try (Stream<Path> files = Files.walk(SRC)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    Matcher m = CODE_KEY.matcher(Files.readString(p, StandardCharsets.UTF_8));
                    while (m.find()) {
                        String key = m.group(1);
                        if (key.endsWith(".")) {
                            // Clé composée à l'exécution : au moins une clé doit commencer ainsi
                            if (en.keySet().stream().noneMatch(k -> k.startsWith(key))) {
                                missing.add(key + "*");
                            }
                        } else if (!en.containsKey(key)) {
                            missing.add(key);
                        }
                    }
                }
            }
            check.accept("Langue : clés citées dans le code mais absentes " + missing, missing.isEmpty());
        } catch (IOException | RuntimeException ex) {
            check.accept("Langue : lecture des fichiers (" + ex + ")", false);
        }
    }

    private static int count(String s) {
        int n = 0;
        Matcher m = FORMAT.matcher(s);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
