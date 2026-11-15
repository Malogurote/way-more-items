package waymoreitems.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lecteur et écrivain JSON minimal, en Java pur, pour les fichiers de mise en page de WMI.
 * <p>Objets : {@link LinkedHashMap} (ordre des clés conservé) ; tableaux : {@link ArrayList} ; nombres :
 * {@link Double} ; chaînes, booléens et {@code null} tels quels. Les commentaires ne sont pas admis.
 */
public final class MiniJson {
    /** Profondeur maximale d'imbrication des objets et tableaux (au-delà : erreur, au lieu d'un débordement de pile). */
    public static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private MiniJson(String src) {
        this.src = src;
    }

    /**
     * Analyse un texte JSON ; lève {@link IllegalArgumentException} s'il est invalide. Le message est neutre,
     * de la forme {@code json|ligne|colonne|clé|argument} : le client le traduit (clés {@code wmi.layout.err.json.*}).
     */
    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw p.error("trailing");
        }
        return v;
    }

    private IllegalArgumentException error(String what) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(pos, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException("json|" + line + "|" + col + "|" + what);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Object value() {
        if (pos >= src.length()) {
            throw error("fin de texte inattendue");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                enter();
                Map<String, Object> obj = object();
                depth--;
                return obj;
            case '[':
                enter();
                List<Object> arr = array();
                depth--;
                return arr;
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected_char|" + c);
        }
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("too_deep|" + MAX_DEPTH);
        }
    }

    private Object literal(String word, Object v) {
        if (!src.startsWith(word, pos)) {
            throw error("mot inconnu");
        }
        pos += word.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw error("key_expected");
            }
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            map.put(key, value());
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw error("char_expected|" + c);
        }
        pos++;
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    break;
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw error("unicode_incomplete");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated");
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error("nombre invalide");
        }
    }

    // ==================== ÉCRITURE ====================

    /** Écrit une valeur en JSON indenté de deux espaces ; les tableaux de nombres restent sur une ligne. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(number(n));
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            boolean flat = l.stream().allMatch(x -> x instanceof Number || x instanceof String || x instanceof Boolean);
            if (flat) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    write(sb, l.get(i), indent);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(sb, indent + 1);
                write(sb, l.get(i), indent + 1);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static String number(Number n) {
        double d = n.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return String.format(Locale.ROOT, "%s", d);
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
